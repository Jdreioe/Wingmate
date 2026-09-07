//! Local EyeTrax process, owned by the cancellable gaze subscription.
use super::{
    Status,
    protocol::{Point, Sample},
    runner::Source,
};
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Instant;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Camera {
    pub path: String,
    pub name: String,
}
impl std::fmt::Display for Camera {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} ({})", self.name, self.path)
    }
}

pub fn cameras() -> Vec<Camera> {
    let mut cameras = Vec::new();
    if let Ok(entries) = std::fs::read_dir("/sys/class/video4linux") {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            // V4L2 may expose additional metadata nodes; opening a non-capture
            // node reports a recoverable camera failure without auto-switching.
            if name.starts_with("video") {
                cameras.push(Camera {
                    path: format!("/dev/{name}"),
                    name: std::fs::read_to_string(entry.path().join("name"))
                        .unwrap_or_else(|_| name.clone())
                        .trim()
                        .into(),
                });
            }
        }
    }
    cameras.sort_by(|a, b| a.path.cmp(&b.path));
    cameras
}

pub struct Control {
    pub camera: Camera,
    pub presented: AtomicU32,
}
impl Control {
    pub fn new(camera: Camera) -> Self {
        Self {
            camera,
            presented: AtomicU32::new(0),
        }
    }
}

#[derive(Debug, Clone)]
pub enum Progress {
    Target {
        index: u32,
        point: Point,
        validation: bool,
    },
    Validated,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Failure {
    Tracking,
    Accuracy,
    Estimator,
}

#[derive(serde::Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Event {
    Target {
        index: u32,
        x: f64,
        y: f64,
        validation: bool,
    },
    Validated,
    Sample {
        point: Option<[f64; 2]>,
        age_ms: f64,
    },
    RuntimeMissing,
    CameraUnavailable,
    CalibrationFailed {
        reason: Failure,
    },
}

#[cfg(target_os = "linux")]
pub async fn run(source: Source) {
    if run_inner(&source).await.is_err() {
        source.unavailable(Status::CameraUnavailable);
    }
    // Keep the failure visible until an explicit retry; don't reopen a camera.
    std::future::pending::<()>().await;
}

#[cfg(target_os = "linux")]
async fn run_inner(source: &Source) -> Result<(), ()> {
    use std::process::Stdio;
    let control = source.webcam.as_ref().ok_or(())?;
    let runtime = crate::data_directory().join("webcam");
    let python = runtime.join("venv/bin/python");
    let model = runtime.join("face_landmarker.task");
    if !python.is_file() || !model.is_file() {
        source.unavailable(Status::WebcamRuntimeMissing);
        return Ok(());
    }
    let child = tokio::process::Command::new(python)
        .args(["-u", "-c", include_str!("../../../webcam/provider.py")])
        .arg(&control.camera.path)
        .arg(model)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(|_| ())?;
    read_child(source, child).await
}

#[cfg(target_os = "linux")]
async fn read_child(source: &Source, mut child: tokio::process::Child) -> Result<(), ()> {
    use std::time::Duration;
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
    let control = source.webcam.as_ref().ok_or(())?;
    let mut input = child.stdin.take().ok_or(())?;
    let mut output = BufReader::new(child.stdout.take().ok_or(())?);
    let mut frame = 0u32;
    let start = Instant::now();
    let mut validated = false;
    let mut expected_target = 1;
    let mut requested = Instant::now();
    loop {
        // A stalled child cannot keep an old gaze sample alive. Bound each
        // record too, so a broken helper cannot grow the mailbox indefinitely.
        let mut line = String::new();
        let result = tokio::time::timeout(
            Duration::from_secs(15),
            (&mut output).take(4097).read_line(&mut line),
        )
        .await;
        if !matches!(result, Ok(Ok(1..))) || line.len() > 4096 || !line.ends_with('\n') {
            return Err(());
        }
        let event: Event = serde_json::from_str(&line).map_err(|_| ())?;
        match event {
            Event::Target {
                index,
                x,
                y,
                validation,
            } if !validated
                && index == expected_target
                && index <= 13
                && validation == (index > 9) =>
            {
                let point = checked_point(Some([x, y]), 0.0).ok_or(())?;
                source.progress(Some(Progress::Target {
                    index,
                    point,
                    validation,
                }));
                while control.presented.load(Ordering::Acquire) != index {
                    tokio::time::sleep(Duration::from_millis(16)).await;
                }
                expected_target += 1;
                input.write_all(b"ready\n").await.map_err(|_| ())?;
            }
            Event::Validated if !validated && expected_target == 14 => {
                source.progress(Some(Progress::Validated));
                while control.presented.load(Ordering::Acquire) != 14 {
                    tokio::time::sleep(Duration::from_millis(16)).await;
                }
                validated = true;
                source.progress(None);
                requested = Instant::now();
                input.write_all(b"ready\n").await.map_err(|_| ())?;
            }
            Event::Sample { point, age_ms } if validated => {
                frame = frame.wrapping_add(1);
                let now = Instant::now();
                let age_valid = age_ms.is_finite() && (0.0..=100.0).contains(&age_ms);
                // Expiry is measured from capture/request time, not arrival.
                // Otherwise a 90 ms estimate could survive another 100 ms.
                let received = if age_valid {
                    requested.min(now - Duration::from_secs_f64(age_ms / 1000.0))
                } else {
                    now
                };
                source.sample(
                    Sample {
                        frame_counter: frame,
                        timestamp_us: start.elapsed().as_micros() as i64,
                        point: checked_point(
                            point,
                            if age_valid {
                                now.saturating_duration_since(received).as_secs_f64() * 1000.0
                            } else {
                                f64::INFINITY
                            },
                        ),
                    },
                    received,
                );
                requested = Instant::now();
                input.write_all(b"next\n").await.map_err(|_| ())?;
            }
            Event::RuntimeMissing => {
                source.unavailable(Status::WebcamRuntimeMissing);
                return Ok(());
            }
            Event::CameraUnavailable => return Err(()),
            Event::CalibrationFailed { reason } => {
                source.unavailable(Status::CalibrationFailed(reason));
                return Ok(());
            }
            _ => return Err(()),
        }
    }
}

fn checked_point(point: Option<[f64; 2]>, age_ms: f64) -> Option<Point> {
    let [x, y] = point?;
    (age_ms.is_finite()
        && (0.0..=100.0).contains(&age_ms)
        && x.is_finite()
        && y.is_finite()
        && (0.0..=1.0).contains(&x)
        && (0.0..=1.0).contains(&y))
    .then_some(Point { x, y })
}

pub fn calibration_view(source: &Source) -> Option<iced::Element<'static, crate::Message>> {
    use iced::{
        Fill,
        widget::{Space, button, column, container, responsive, row, stack, text},
    };
    let snapshot = source.snapshot();
    let progress = snapshot.progress?;
    match progress {
        Progress::Target { index, point, validation } => {
            source.webcam.as_ref()?.presented.store(index, Ordering::Release);
            Some(responsive(move |size| {
                let target = column![
                    Space::new().height((point.y as f32 * size.height - 32.0).max(0.0)),
                    row![Space::new().width((point.x as f32 * size.width - 32.0).max(0.0)),
                        container(text("+").size(48)).center_x(64).center_y(64)
                            .style(container::bordered_box)]
                ];
                let help = container(column![
                    text(if validation { "Validation: look at the +" } else { "Calibration: look at the +" }).size(24),
                    text(format!("Target {index} of 13. Blink normally; collection waits for your eyes.")),
                    button("Cancel camera gaze (Esc)").height(48).on_press(crate::Message::ToggleGaze)
                ].spacing(8)).padding(12);
                let help = container(help).width(Fill).height(Fill).center_x(Fill);
                let help = if point.y > 0.5 { help } else { help.align_bottom(Fill) };
                stack![target, help].width(Fill).height(Fill).into()
            }).into())
        }
        Progress::Validated => Some(container(column![
            text("Calibration passed the large-target check").size(28),
            text("Press Enter to start. Camera gaze is experimental. Start with a small grid of large buttons. Recalibrate after moving yourself, the camera, or the display."),
            button("Start camera gaze").height(56).on_press(crate::Message::WebcamContinue),
            button("Cancel camera gaze").height(56).on_press(crate::Message::ToggleGaze),
        ].spacing(20).max_width(700)).center_x(Fill).center_y(Fill).into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn calibration_instructions_use_the_full_display() {
        use iced::advanced::{layout::Limits, renderer::Headless, widget::Tree};
        let renderer = iced::futures::executor::block_on(iced::Renderer::new(
            iced::Font::default(),
            iced::Pixels(16.0),
            Some("tiny-skia"),
        ))
        .expect("headless renderer");
        let source = Source::camera(Camera {
            path: "/dev/unused".into(),
            name: "Test".into(),
        });
        source.progress(Some(Progress::Target {
            index: 10,
            point: Point { x: 0.25, y: 0.25 },
            validation: true,
        }));
        let mut view = calibration_view(&source).unwrap();
        let mut tree = Tree::new(view.as_widget());
        let size = iced::Size::new(1100.0, 760.0);
        let layout =
            view.as_widget_mut()
                .layout(&mut tree, &renderer, &Limits::new(iced::Size::ZERO, size));
        let stack = &layout.children()[0];
        assert_eq!(stack.size(), size, "instructions must use the full display");
        let instructions = stack.children()[1].children()[0].bounds();
        assert!(
            instructions.y > size.height * 0.25 + 32.0,
            "instructions must not cover the validation target"
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn child_protocol_gates_calibration_and_cancels_owned_process() {
        use std::{process::Stdio, time::Duration};
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
            let source = Source::camera(Camera { path: "/dev/unused".into(), name: "Test".into() });
            // A helper must never send samples before all calibration stages.
            let premature = tokio::process::Command::new("sh")
                .args(["-c", "printf '%s\\n' '{\"kind\":\"sample\",\"point\":[0.5,0.5],\"age_ms\":0}'"])
                .stdin(Stdio::piped()).stdout(Stdio::piped()).kill_on_drop(true).spawn().unwrap();
            assert!(read_child(&source, premature).await.is_err());
            assert!(source.snapshot().sample.is_none());

            let child = tokio::process::Command::new("sh")
                .args(["-c", "printf '%s\\n' '{\"kind\":\"target\",\"index\":1,\"x\":0.1,\"y\":0.1,\"validation\":false}'; read ready; read next"])
                .stdin(Stdio::piped()).stdout(Stdio::piped()).kill_on_drop(true).spawn().unwrap();
            let pid = child.id().unwrap();
            let worker_source = source.clone();
            let worker = tokio::spawn(async move { read_child(&worker_source, child).await });
            tokio::time::timeout(Duration::from_secs(2), async {
                while source.snapshot().progress.is_none() { tokio::task::yield_now().await; }
            }).await.unwrap();
            assert_eq!(source.snapshot().status, Status::Calibrating);
            assert!(source.snapshot().point(Instant::now()).is_none());
            worker.abort();
            let _ = worker.await;
            tokio::time::timeout(Duration::from_secs(2), async {
                while std::path::Path::new(&format!("/proc/{pid}")).exists() {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            }).await.expect("owned camera process must be reaped on cancellation");
        });
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn validated_child_reaches_the_shared_mailbox_and_reports_losses() {
        use std::{process::Stdio, time::Duration};
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
            let source = Source::camera(Camera { path: "/dev/unused".into(), name: "Test".into() });
            let mut script = String::new();
            for index in 1..=13 {
                let event = serde_json::json!({"kind":"target", "index":index, "x":0.5, "y":0.5, "validation": index > 9});
                script.push_str(&format!("printf '%s\\n' '{event}'; read ready || exit; "));
            }
            script.push_str("printf '%s\\n' '{\"kind\":\"validated\"}'; read ready || exit; ");
            for point in [Some([0.25, 0.25]), None, Some([0.75, 0.75])] {
                let event = serde_json::json!({"kind":"sample", "point":point, "age_ms":0});
                script.push_str(&format!("printf '%s\\n' '{event}'; read next || exit; "));
            }
            script.push_str("read end");
            let child = tokio::process::Command::new("sh").args(["-c", &script])
                .stdin(Stdio::piped()).stdout(Stdio::piped()).kill_on_drop(true).spawn().unwrap();
            let worker_source = source.clone();
            let worker = tokio::spawn(async move { read_child(&worker_source, child).await });
            tokio::time::timeout(Duration::from_secs(3), async {
                loop {
                    let snapshot = source.snapshot();
                    match snapshot.progress {
                        Some(Progress::Target { index, .. }) => source.webcam.as_ref().unwrap().presented.store(index, Ordering::Release),
                        Some(Progress::Validated) => source.webcam.as_ref().unwrap().presented.store(14, Ordering::Release),
                        None => {},
                    }
                    if snapshot.sample.is_some_and(|sample| sample.frame_counter == 3) { break; }
                    tokio::task::yield_now().await;
                }
            }).await.unwrap();
            let snapshot = source.snapshot();
            assert_eq!(snapshot.point(Instant::now()), Some(Point { x: 0.75, y: 0.75 }));
            assert!(snapshot.losses >= 16); // 15 setup resets plus the invalid frame.
            worker.abort();
            let _ = worker.await;
        });
    }

    #[test]
    fn rejects_invalid_offscreen_and_stale_estimates() {
        for point in [
            [f64::NAN, 0.5],
            [0.5, f64::INFINITY],
            [-0.01, 0.5],
            [1.01, 0.5],
        ] {
            assert!(checked_point(Some(point), 0.0).is_none());
        }
        for age in [-1.0, 101.0, f64::NAN] {
            assert!(checked_point(Some([0.5, 0.5]), age).is_none());
        }
        assert_eq!(
            checked_point(Some([0.25, 0.75]), 50.0),
            Some(Point { x: 0.25, y: 0.75 })
        );
    }
}
