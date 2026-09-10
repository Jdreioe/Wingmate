//! Cancellable gaze sources and a latest-value mailbox. The UI never queues
//! raw frames. Every loss increments a counter even if gaze recovers between UI ticks.
use super::{
    Status,
    protocol::{Point, Sample},
};
use iced::{Subscription, futures::Stream};
use std::{
    hash::{Hash, Hasher},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

pub const STALE_AFTER: Duration = Duration::from_millis(100);

#[derive(Clone)]
pub struct Snapshot {
    pub status: Status,
    pub sample: Option<Sample>,
    pub received: Instant,
    pub losses: u64,
    pub progress: Option<super::webcam::Progress>,
    pub validation: Vec<super::webcam::ValidationSummary>,
    pub kept_previous: bool,
}

impl Snapshot {
    pub fn point(&self, now: Instant) -> Option<Point> {
        if self.status != Status::Connected
            || now.saturating_duration_since(self.received) > STALE_AFTER
        {
            return None;
        }
        self.sample?.point
    }
}

#[derive(Clone)]
pub struct Source {
    state: Arc<Mutex<Snapshot>>,
    pub webcam: Option<Arc<super::webcam::Control>>,
}

impl Hash for Source {
    fn hash<H: Hasher>(&self, state: &mut H) {
        Arc::as_ptr(&self.state).hash(state);
    }
}

impl Source {
    pub fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(Snapshot {
                status: Status::Connecting,
                sample: None,
                received: Instant::now(),
                losses: 0,
                progress: None,
                validation: Vec::new(),
                kept_previous: false,
            })),
            webcam: None,
        }
    }
    pub fn camera(camera: super::webcam::Camera) -> Self {
        let mut source = Self::new();
        source.webcam = Some(Arc::new(super::webcam::Control::new(camera)));
        source
    }
    pub fn progress(&self, progress: Option<super::webcam::Progress>) {
        self.unavailable(Status::Calibrating);
        let mut state = self.state.lock().expect("gaze mailbox");
        state.progress = progress;
        state.received = Instant::now();
    }
    pub fn snapshot(&self) -> Snapshot {
        self.state.lock().expect("gaze mailbox").clone()
    }
    pub fn target_feedback(&self, index: u32, feedback: super::webcam::Feedback) {
        let mut state = self.state.lock().expect("gaze mailbox");
        if let Some(super::webcam::Progress::Target {
            index: current,
            feedback: value,
            ..
        }) = &mut state.progress
            && *current == index
        {
            *value = feedback;
            state.received = Instant::now();
        }
    }
    pub fn calibration_complete(
        &self,
        summaries: Vec<super::webcam::ValidationSummary>,
        kept_previous: bool,
    ) {
        let passed = summaries
            .iter()
            .all(super::webcam::ValidationSummary::passed);
        self.unavailable(if passed {
            Status::Calibrating
        } else {
            Status::CalibrationFailed(super::webcam::Failure::Accuracy)
        });
        let mut state = self.state.lock().expect("gaze mailbox");
        state.validation = summaries;
        state.kept_previous = kept_previous;
        state.progress = Some(super::webcam::Progress::Validated);
    }
    pub(crate) fn unavailable(&self, status: Status) {
        let mut state = self.state.lock().expect("gaze mailbox");
        state.status = status;
        state.sample = None;
        state.progress = None;
        state.validation.clear();
        state.kept_previous = false;
        state.losses = state.losses.wrapping_add(1);
    }
    pub(crate) fn sample(&self, sample: Sample, received: Instant) {
        let mut state = self.state.lock().expect("gaze mailbox");
        if sample.point.is_none()
            || received.saturating_duration_since(state.received) > STALE_AFTER
        {
            state.losses = state.losses.wrapping_add(1);
        }
        state.status = if sample.point.is_some() {
            Status::Connected
        } else {
            Status::GazeLost
        };
        state.sample = Some(sample);
        state.received = received;
    }
    pub fn subscription(&self) -> Subscription<()> {
        Subscription::run_with(self.clone(), stream)
    }
}

fn stream(source: &Source) -> impl Stream<Item = ()> + use<> {
    let source = source.clone();
    iced::stream::channel(1, move |_output| async move {
        #[cfg(target_os = "linux")]
        if source.webcam.is_some() {
            super::webcam::run(source).await;
            return;
        }
        #[cfg(unix)]
        run(source, super::client::socket_path()).await;
        #[cfg(not(unix))]
        {
            let _ = source;
            std::future::pending::<()>().await;
        }
    })
}

/// Device timestamps only establish ordering. Dwell uses the host monotonic
/// clock shared with pointer/key input, never a mixture of device and host time.
#[derive(Default)]
struct Order {
    previous: Option<(u32, i64)>,
}
impl Order {
    fn accept(&mut self, sample: Sample) -> bool {
        let valid = self.previous.is_none_or(|(frame, time)| {
            let delta = sample.frame_counter.wrapping_sub(frame);
            delta > 0 && delta < (1 << 31) && sample.timestamp_us > time
        });
        if valid {
            self.previous = Some((sample.frame_counter, sample.timestamp_us));
        }
        valid
    }
}

#[cfg(unix)]
async fn run(source: Source, path: std::path::PathBuf) {
    use tokio::{io::AsyncWriteExt, net::UnixStream};
    let mut backoff = super::Backoff::new();
    loop {
        let connection =
            tokio::time::timeout(Duration::from_secs(1), UnixStream::connect(&path)).await;
        match connection {
            Ok(Ok(mut socket)) => {
                let subscribed = tokio::time::timeout(
                    Duration::from_secs(1),
                    socket.write_all(&super::protocol::subscribe_command()),
                )
                .await;
                if matches!(subscribed, Ok(Ok(()))) {
                    let status = read(&source, &mut socket, &mut backoff).await;
                    source.unavailable(status);
                } else {
                    source.unavailable(Status::DaemonUnavailable);
                }
            }
            _ => source.unavailable(Status::DaemonUnavailable),
        }
        tokio::time::sleep(backoff.next_delay()).await;
    }
}

#[cfg(unix)]
async fn read(
    source: &Source,
    socket: &mut tokio::net::UnixStream,
    backoff: &mut super::Backoff,
) -> Status {
    use tokio::io::AsyncReadExt;
    let mut decoder = super::protocol::Decoder::new();
    let mut order = Order::default();
    let mut buffer = [0; 8192];
    loop {
        let read = tokio::time::timeout(STALE_AFTER, socket.read(&mut buffer)).await;
        match read {
            Err(_) => {
                source.unavailable(Status::GazeLost);
                continue;
            }
            Ok(Ok(0)) | Ok(Err(_)) => return super::ReadError::Disconnected.status(),
            Ok(Ok(count)) => decoder.push(&buffer[..count]),
        }
        loop {
            match decoder.next() {
                Ok(Some((_, Some(sample)))) => {
                    if order.accept(sample) {
                        source.sample(sample, Instant::now());
                        backoff.reset();
                    } else {
                        // Duplicate/reordered frames cannot keep a target alive.
                        source.unavailable(Status::GazeLost);
                    }
                }
                Ok(Some(_)) => {}
                Ok(None) => break,
                Err(error) => return super::ReadError::Protocol(error).status(),
            }
        }
    }
}

#[derive(Default)]
pub struct Session {
    pub source: Option<Source>,
    pub epoch: u64,
    pub losses: u64,
    pub status: Status,
    pub camera_size: Option<iced::Size>,
}
impl Session {
    pub fn enabled(&self) -> bool {
        self.source.is_some()
    }
    pub fn start(&mut self) {
        self.epoch = self.epoch.wrapping_add(1);
        self.source = Some(Source::new());
        self.losses = 0;
        self.status = Status::Connecting;
    }
    pub fn stop(&mut self) {
        self.epoch = self.epoch.wrapping_add(1);
        self.source = None;
        self.camera_size = None;
        self.status = Status::Disabled;
    }
    pub fn label(&self) -> &'static str {
        match self.status {
            Status::Calibrating => "Camera calibration in progress. Selection is paused.",
            Status::CameraUnavailable => {
                "Camera unavailable or busy. Check camera access, then retry camera calibration."
            }
            Status::WebcamRuntimeMissing => {
                "Camera runtime missing. Run scripts/install-webcam-gaze.sh, then retry."
            }
            Status::CalibrationFailed(super::webcam::Failure::Tracking) => {
                "Not enough usable eye tracking within 10 seconds. Keep both eyes visible, then retry camera calibration. Blinks are allowed."
            }
            Status::CalibrationFailed(super::webcam::Failure::Accuracy) => {
                "Validation could not reliably match your gaze to the target. Selection is off. Adjust lighting or position, then retry camera calibration."
            }
            Status::CalibrationFailed(super::webcam::Failure::Estimator) => {
                "The camera gaze estimator failed while processing calibration. Retry camera calibration."
            }
            Status::Disabled => "Gaze off",
            Status::Connecting => "Connecting to gaze tracker",
            Status::Connected => "Gaze connected",
            Status::GazeLost => "Gaze lost. Look at the display to resume.",
            Status::DaemonUnavailable => {
                "Gaze daemon unavailable. Start tobiifreed; Wingmate will retry."
            }
            Status::IncompatibleProtocol => "Gaze protocol incompatible. Check the daemon version.",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample(frame: u32, timestamp_us: i64, point: Option<Point>) -> Sample {
        Sample {
            frame_counter: frame,
            timestamp_us,
            point,
        }
    }
    const POINT: Option<Point> = Some(Point { x: 0.5, y: 0.5 });

    #[test]
    fn rejects_reordered_duplicate_and_backwards_device_time_but_accepts_counter_wrap() {
        let mut order = Order::default();
        assert!(order.accept(sample(u32::MAX, 10, POINT)));
        assert!(order.accept(sample(0, 20, POINT)));
        assert!(!order.accept(sample(0, 30, POINT)));
        assert!(!order.accept(sample(u32::MAX, 30, POINT)));
        assert!(!order.accept(sample(1, 15, POINT)));
        assert!(order.accept(sample(1, 30, POINT)));
    }

    #[test]
    fn mailbox_keeps_loss_between_ui_reads_and_expires_silent_streams() {
        let source = Source::new();
        let now = Instant::now();
        source.sample(sample(1, 1, POINT), now);
        let first = source.snapshot();
        source.sample(sample(2, 2, None), now);
        source.sample(sample(3, 3, POINT), now);
        let recovered = source.snapshot();
        assert!(recovered.losses > first.losses);
        assert_eq!(recovered.point(now), POINT);
        assert!(
            recovered
                .point(now + STALE_AFTER + Duration::from_millis(1))
                .is_none()
        );
        source.sample(
            sample(4, 4, POINT),
            now + STALE_AFTER + Duration::from_millis(1),
        );
        assert!(source.snapshot().losses > recovered.losses);
    }

    #[cfg(unix)]
    #[test]
    fn reader_recovers_from_disconnect_and_releases_socket_on_cancellation() {
        use tokio::{
            io::{AsyncReadExt, AsyncWriteExt},
            net::UnixListener,
        };
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let directory = tempfile::tempdir().unwrap();
                let path = directory.path().join("gaze.sock");
                let listener = UnixListener::bind(&path).unwrap();
                let source = Source::new();
                let worker = tokio::spawn(run(source.clone(), path));
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut subscribe = [0; 9];
                socket.read_exact(&mut subscribe).await.unwrap();
                assert_eq!(subscribe, super::super::protocol::subscribe_command());
                let mut bytes = vec![0x01];
                bytes.extend_from_slice(&392u32.to_le_bytes());
                let mut payload = vec![0; 392];
                payload[0..4].copy_from_slice(&0b100_1111u32.to_le_bytes());
                payload[4..8].copy_from_slice(&1u32.to_le_bytes());
                payload[16..24].copy_from_slice(&1i64.to_le_bytes());
                payload[40..48].copy_from_slice(&0.5f64.to_le_bytes());
                payload[48..56].copy_from_slice(&0.5f64.to_le_bytes());
                bytes.extend(payload);
                socket.write_all(&bytes[..20]).await.unwrap();
                socket.write_all(&bytes[20..]).await.unwrap();
                tokio::time::timeout(Duration::from_secs(1), async {
                    while source.snapshot().status != Status::Connected {
                        tokio::task::yield_now().await;
                    }
                })
                .await
                .unwrap();
                assert_eq!(source.snapshot().point(Instant::now()), POINT);
                let before = source.snapshot().losses;
                drop(socket);
                let (mut second, _) =
                    tokio::time::timeout(Duration::from_secs(2), listener.accept())
                        .await
                        .unwrap()
                        .unwrap();
                second.read_exact(&mut subscribe).await.unwrap();
                // A new daemon may restart its device clock and frame counter.
                second.write_all(&bytes).await.unwrap();
                tokio::time::timeout(Duration::from_secs(1), async {
                    while source.snapshot().status != Status::Connected {
                        tokio::task::yield_now().await;
                    }
                })
                .await
                .unwrap();
                assert!(source.snapshot().losses > before);
                worker.abort();
                let _ = worker.await;
                assert_eq!(second.read(&mut [0; 1]).await.unwrap(), 0);
            });
    }
}
