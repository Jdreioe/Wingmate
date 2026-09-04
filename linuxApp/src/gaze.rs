//! Native gaze input from `tobiifreed` (TD-I13 on Linux).
//!
//! P1 of the native gaze roadmap (issues #123 / #129). The OS-pointer path
//! remains the fallback: gaze only drives semantic target transitions, and the
//! shared `AccessInputController` (behind the local bridge) keeps owning dwell,
//! Rest mode, debounce, and activation.
//!
//! Wire summary, kept deliberately close to the daemon's own client
//! (`SocketSource` in `Aetherall/tobiifree`):
//! - Unix socket `$XDG_RUNTIME_DIR/tobiifreed/gaze.sock`
//! - Framing `[u8 type][u32 LE payload_len][payload]`
//! - Client sends `subscribe` (`0x01`) with a `u32 LE` stream id `0x500`
//! - Daemon streams `gaze` (`0x01`) frames holding one `GazeSample` each
//!
//! Privacy: gaze coordinates and eye measurements are memory-only operational
//! data. They are never logged, persisted, or sent anywhere except as semantic
//! target transitions to the local bridge. Status strings carry no coordinates.

use std::collections::VecDeque;
use std::env;
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::time::{Duration, Instant};

// ---------------------------------------------------------------------------
// Protocol constants
// ---------------------------------------------------------------------------

/// Client -> daemon: start the gaze stream.
pub const CMD_SUBSCRIBE: u8 = 0x01;
/// TTP stream id for gaze inside the subscribe payload (u32 LE).
pub const STREAM_GAZE: u32 = 0x500;

/// Daemon -> client: one [`GazeSample`] per frame.
pub const SRV_GAZE: u8 = 0x01;
/// Daemon -> client: command response (consumed and ignored here).
pub const SRV_RESPONSE: u8 = 0x02;
/// Daemon -> client: display-area description (consumed and ignored here).
pub const SRV_DISPLAY_AREA: u8 = 0x03;
/// Daemon -> client: error frame (consumed and ignored here).
pub const SRV_ERR: u8 = 0xFF;

pub const HEADER_SIZE: usize = 5;
/// Upper bound for any single frame payload. The gaze sample is 392 bytes;
/// anything larger is a protocol violation and fails closed.
pub const MAX_PAYLOAD_LEN: usize = 4096;
/// Reconnect backoff: 1s, 2s, 4s … capped here.
pub const MAX_RECONNECT_DELAY: Duration = Duration::from_secs(15);
/// A connected stream with no usable sample for this long reports gaze lost.
pub const GAZE_LOSS_TIMEOUT: Duration = Duration::from_millis(500);

// ---------------------------------------------------------------------------
// GazeSample: pinned copy of the daemon's `extern struct`
// ---------------------------------------------------------------------------
//
// Field order and types mirror `GazeSample` in
// `tobiifree/driver/src/tobiifree_core.zig`. The daemon's ARCHITECTURE.md
// still documents 232 bytes; the inspected struct is 392 bytes, so the size
// is asserted here and any other payload length is rejected as incompatible.

pub const GAZE_SAMPLE_SIZE: usize = 392;

// Present-mask bits (one per field). Only the bits the first slice needs
// are read today; the rest pin the daemon contract for follow-up phases
// (P2 diagnostics and interaction tuning) and are kept by design.
#[allow(dead_code)]
pub const GAZE_BIT_TIMESTAMP: u32 = 1 << 0;
#[allow(dead_code)]
pub const GAZE_BIT_FRAME_COUNTER: u32 = 1 << 1;
pub const GAZE_BIT_VALIDITY_L: u32 = 1 << 2;
pub const GAZE_BIT_VALIDITY_R: u32 = 1 << 3;
#[allow(dead_code)]
pub const GAZE_BIT_PUPIL_L: u32 = 1 << 4;
#[allow(dead_code)]
pub const GAZE_BIT_PUPIL_R: u32 = 1 << 5;
pub const GAZE_BIT_GAZE_2D: u32 = 1 << 6;
#[allow(dead_code)]
pub const GAZE_BIT_GAZE_2D_L: u32 = 1 << 7;
#[allow(dead_code)]
pub const GAZE_BIT_GAZE_2D_R: u32 = 1 << 8;

/// Per-eye validity value meaning "valid". Anything else (e.g. 4 = not
/// detected) means that eye contributes nothing to this sample.
pub const VALIDITY_VALID: u32 = 0;

/// Decoded gaze frame. Only the fields the first slice needs are kept as
/// named values; the remainder is validated by size and skipped.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GazeSample {
    pub present_mask: u32,
    pub frame_counter: u32,
    pub validity_l: u32,
    pub validity_r: u32,
    pub timestamp_us: i64,
    /// Combined binocular 2D gaze on the display area, temporally filtered,
    /// in normalized `[0, 1]^2` coordinates.
    pub gaze_x: f64,
    pub gaze_y: f64,
}

impl GazeSample {
    /// Fails closed: wrong length or unreadable fields yield `None` and the
    /// caller reports an incompatible protocol instead of guessing.
    pub fn from_bytes(payload: &[u8]) -> Option<Self> {
        if payload.len() != GAZE_SAMPLE_SIZE {
            return None;
        }
        let u32_at = |off: usize| -> Option<u32> {
            payload.get(off..off + 4).and_then(|s| s.try_into().ok()).map(u32::from_le_bytes)
        };
        let i64_at = |off: usize| -> Option<i64> {
            payload.get(off..off + 8).and_then(|s| s.try_into().ok()).map(i64::from_le_bytes)
        };
        let f64_at = |off: usize| -> Option<f64> {
            payload.get(off..off + 8).and_then(|s| s.try_into().ok()).map(f64::from_le_bytes)
        };
        Some(Self {
            present_mask: u32_at(0)?,
            frame_counter: u32_at(4)?,
            validity_l: u32_at(8)?,
            validity_r: u32_at(12)?,
            timestamp_us: i64_at(16)?,
            // gaze_point_2d_norm lives at byte offset 40 (see module docs).
            gaze_x: f64_at(40)?,
            gaze_y: f64_at(48)?,
        })
    }

    fn left_valid(&self) -> bool {
        self.present_mask & GAZE_BIT_VALIDITY_L != 0 && self.validity_l == VALIDITY_VALID
    }

    fn right_valid(&self) -> bool {
        self.present_mask & GAZE_BIT_VALIDITY_R != 0 && self.validity_r == VALIDITY_VALID
    }

    /// The single normalized point driving target resolution, or `None` when
    /// the sample must not select anything: missing combined gaze, both eyes
    /// lost, non-finite data, or gaze outside the display area.
    pub fn usable_point(&self) -> Option<(f64, f64)> {
        if self.present_mask & GAZE_BIT_GAZE_2D == 0 {
            return None;
        }
        if !self.left_valid() && !self.right_valid() {
            return None;
        }
        if !self.gaze_x.is_finite() || !self.gaze_y.is_finite() {
            return None;
        }
        if !(0.0..=1.0).contains(&self.gaze_x) || !(0.0..=1.0).contains(&self.gaze_y) {
            return None;
        }
        Some((self.gaze_x, self.gaze_y))
    }
}

// ---------------------------------------------------------------------------
// Incremental frame decoder
// ---------------------------------------------------------------------------

/// Outcome of feeding newly read socket bytes into [`FrameDecoder`].
#[derive(Debug, PartialEq)]
pub enum FrameEvent {
    /// A well-formed gaze sample (validity still has to be checked).
    Gaze(GazeSample),
    /// A frame the pinned protocol cannot interpret. The caller surfaces an
    /// incompatible-protocol status; the stream stays open so a subsequent
    /// valid sample can recover without reconnecting.
    Incompatible { frame_type: u8, payload_len: usize },
}

/// The decoder is unrecoverable after this: byte alignment can no longer be
/// trusted, so the caller must drop the connection and reconnect.
#[derive(Debug, PartialEq)]
pub struct OverlengthError {
    pub payload_len: u32,
}

/// Accumulates partial socket reads into whole frames.
#[derive(Debug, Default)]
pub struct FrameDecoder {
    buf: Vec<u8>,
}

impl FrameDecoder {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed raw bytes; returns at most one event per complete frame. Unknown
    /// daemon messages are consumed (bounded length) and reported instead of
    /// interpreted.
    pub fn feed(&mut self, bytes: &[u8]) -> Result<Vec<FrameEvent>, OverlengthError> {
        self.buf.extend_from_slice(bytes);
        let mut events = Vec::new();
        loop {
            if self.buf.len() < HEADER_SIZE {
                break;
            }
            let frame_type = self.buf[0];
            let payload_len =
                u32::from_le_bytes(self.buf[1..HEADER_SIZE].try_into().expect("header size"))
                    as usize;
            if payload_len > MAX_PAYLOAD_LEN {
                self.buf.clear();
                return Err(OverlengthError {
                    payload_len: payload_len as u32,
                });
            }
            if self.buf.len() < HEADER_SIZE + payload_len {
                break; // Incomplete frame; wait for more bytes.
            }
            let payload_start = HEADER_SIZE;
            let payload_end = HEADER_SIZE + payload_len;
            let event = match frame_type {
                SRV_GAZE => match GazeSample::from_bytes(&self.buf[payload_start..payload_end]) {
                    Some(sample) => FrameEvent::Gaze(sample),
                    None => FrameEvent::Incompatible {
                        frame_type,
                        payload_len,
                    },
                },
                SRV_RESPONSE | SRV_DISPLAY_AREA | SRV_ERR => {
                    // Consumed and ignored: responses need no client action in
                    // the first slice, and payloads are never logged.
                    continue_after_drain(&mut self.buf, payload_end);
                    continue;
                }
                _ => FrameEvent::Incompatible {
                    frame_type,
                    payload_len,
                },
            };
            continue_after_drain(&mut self.buf, payload_end);
            events.push(event);
        }
        Ok(events)
    }
}

fn continue_after_drain(buf: &mut Vec<u8>, end: usize) {
    buf.drain(..end);
}

// ---------------------------------------------------------------------------
// Client: connect, subscribe, poll, reconnect
// ---------------------------------------------------------------------------

/// User-facing connection state. Never carries coordinates or eye data.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GazeStatus {
    Disabled,
    /// Enabled but no live stream (dialing or backing off).
    Connecting,
    /// Live stream with a fresh usable sample.
    Connected,
    /// Live stream but no usable sample recently (both eyes lost, gaze
    /// off-screen, or stalled daemon).
    GazeLost,
    /// The daemon speaks a protocol this client cannot interpret.
    Incompatible,
    /// No daemon is listening; retrying with bounded backoff.
    DaemonUnavailable,
}

impl GazeStatus {
    pub fn label(&self) -> &'static str {
        match self {
            GazeStatus::Disabled => "Eye tracking off",
            GazeStatus::Connecting => "Eye tracking: connecting…",
            GazeStatus::Connected => "Eye tracking: connected",
            GazeStatus::GazeLost => "Eye tracking: gaze lost — look at the screen",
            GazeStatus::Incompatible => {
                "Eye tracking: incompatible tracker protocol — update Wingmate or tobiifreed"
            }
            GazeStatus::DaemonUnavailable => {
                "Eye tracking: tracker daemon unavailable — start tobiifreed"
            }
        }
    }
}

/// Outcome of one non-blocking [`TobiifreeClient::poll`].
#[derive(Debug, Default)]
pub struct GazePoll {
    /// Newest usable normalized point, if any sample in this batch qualifies.
    pub point: Option<(f64, f64)>,
    /// A well-formed sample arrived but carried no usable point.
    pub saw_unusable_sample: bool,
}

pub fn socket_path() -> String {
    let runtime = env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".to_string());
    format!("{runtime}/tobiifreed/gaze.sock")
}

fn subscribe_frame() -> [u8; HEADER_SIZE + 4] {
    let mut frame = [0u8; HEADER_SIZE + 4];
    frame[0] = CMD_SUBSCRIBE;
    frame[1..HEADER_SIZE].copy_from_slice(&4u32.to_le_bytes());
    frame[HEADER_SIZE..].copy_from_slice(&STREAM_GAZE.to_le_bytes());
    frame
}

/// Non-blocking Unix-socket client. Owns no threads: the UI calls [`poll`](Self::poll)
/// on its gaze timer and drains whatever the daemon has buffered.
pub struct TobiifreeClient {
    stream: Option<UnixStream>,
    decoder: FrameDecoder,
    reconnect_attempts: u32,
    next_attempt_at: Option<Instant>,
    last_usable_at: Option<Instant>,
    incompatible: bool,
    // Test seam: bypass the real socket path.
    #[cfg(test)]
    test_path: Option<String>,
}

impl TobiifreeClient {
    pub fn new() -> Self {
        Self {
            stream: None,
            decoder: FrameDecoder::new(),
            reconnect_attempts: 0,
            next_attempt_at: None,
            last_usable_at: None,
            incompatible: false,
            #[cfg(test)]
            test_path: None,
        }
    }

    fn path(&self) -> String {
        #[cfg(test)]
        if let Some(path) = &self.test_path {
            return path.clone();
        }
        socket_path()
    }

    /// Backoff delay for the current attempt count (1s doubling, capped).
    pub fn backoff_delay(attempts: u32) -> Duration {
        let shift = attempts.min(4);
        Duration::from_secs(1 << shift).min(MAX_RECONNECT_DELAY)
    }

    fn try_connect(&mut self, now: Instant) {
        match UnixStream::connect(self.path()) {
            Ok(stream) => {
                if stream.set_nonblocking(true).is_err() {
                    return;
                }
                let mut stream = stream;
                if stream.write_all(&subscribe_frame()).is_err() {
                    return;
                }
                self.stream = Some(stream);
                self.decoder = FrameDecoder::new();
                self.reconnect_attempts = 0;
                self.next_attempt_at = None;
            }
            Err(_) => {
                self.reconnect_attempts = self.reconnect_attempts.saturating_add(1);
                self.next_attempt_at =
                    Some(now + Self::backoff_delay(self.reconnect_attempts));
            }
        }
    }

    /// Drop the live stream and forget backoff state (used when gaze is
    /// disabled). The next poll starts over from a fresh connect.
    pub fn disconnect(&mut self) {
        self.stream = None;
        self.decoder = FrameDecoder::new();
        self.reconnect_attempts = 0;
        self.next_attempt_at = None;
        self.last_usable_at = None;
        self.incompatible = false;
    }

    pub fn poll(&mut self, now: Instant) -> (GazePoll, bool) {
        // `bool` reports whether a live socket exists (for status display).
        if self.stream.is_none() {
            let due = self.next_attempt_at.is_none_or(|at| now >= at);
            if due {
                self.try_connect(now);
            }
            return (GazePoll::default(), false);
        }

        let mut outcome = GazePoll::default();
        let mut socket_live = true;
        let mut read_buf = [0u8; 2048];
        let mut pending: VecDeque<u8> = VecDeque::new();

        // Drain everything the daemon buffered; only the newest usable point
        // matters for target resolution.
        while let Some(stream) = self.stream.as_mut() {
            match stream.read(&mut read_buf) {
                Ok(0) => {
                    // Orderly shutdown: reconnect instead of spinning.
                    self.stream = None;
                    self.reconnect_attempts = self.reconnect_attempts.saturating_add(1);
                    self.next_attempt_at =
                        Some(now + Self::backoff_delay(self.reconnect_attempts));
                    socket_live = false;
                    break;
                }
                Ok(n) => pending.extend(&read_buf[..n]),
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(_) => {
                    self.stream = None;
                    self.reconnect_attempts = self.reconnect_attempts.saturating_add(1);
                    self.next_attempt_at =
                        Some(now + Self::backoff_delay(self.reconnect_attempts));
                    socket_live = false;
                    break;
                }
            }
        }

        if !pending.is_empty() {
            let bytes: Vec<u8> = pending.into_iter().collect();
            match self.decoder.feed(&bytes) {
                Ok(events) => {
                    for event in events {
                        match event {
                            FrameEvent::Gaze(sample) => match sample.usable_point() {
                                Some(point) => {
                                    outcome.point = Some(point);
                                    self.last_usable_at = Some(now);
                                    self.incompatible = false;
                                }
                                None => outcome.saw_unusable_sample = true,
                            },
                            FrameEvent::Incompatible { .. } => self.incompatible = true,
                        }
                    }
                }
                Err(_) => {
                    // Byte alignment lost: drop the connection and redial.
                    // Reported as incompatible so the cause stays visible.
                    self.stream = None;
                    self.incompatible = true;
                    self.reconnect_attempts = self.reconnect_attempts.saturating_add(1);
                    self.next_attempt_at =
                        Some(now + Self::backoff_delay(self.reconnect_attempts));
                    socket_live = false;
                }
            }
        }
        (outcome, socket_live)
    }

    pub fn status(&self, enabled: bool, now: Instant) -> GazeStatus {
        if !enabled {
            return GazeStatus::Disabled;
        }
        if self.incompatible {
            return GazeStatus::Incompatible;
        }
        match &self.stream {
            None => {
                if self.reconnect_attempts == 0 && self.next_attempt_at.is_none() {
                    GazeStatus::Connecting
                } else {
                    GazeStatus::DaemonUnavailable
                }
            }
            Some(_) => {
                let fresh = self
                    .last_usable_at
                    .is_some_and(|at| now.duration_since(at) < GAZE_LOSS_TIMEOUT);
                if fresh {
                    GazeStatus::Connected
                } else {
                    GazeStatus::GazeLost
                }
            }
        }
    }
}

impl Default for TobiifreeClient {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// Target resolution: normalized gaze -> fullscreen communication targets
// ---------------------------------------------------------------------------
//
// The gaze communication surface divides the fullscreen window into horizontal
// bands (draft display, control row, category strip, main grid), each holding
// an ordered row of equal-width cells. The view builds its widgets from the
// same [`GazeLayout`] so resolution and rendering cannot drift apart.
//
// Mapping is exact fractional arithmetic (`floor(x * n)`), matching equal
// `Fill`-width cells. Widget padding makes boundary pixels approximate; cells
// are large by design and dwell requires sustained gaze, so a straddled
// boundary resolves deterministically to one side instead of flapping.

/// One horizontal band of the gaze surface, described as a share of the total
/// vertical portions plus its ordered cell count.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GazeBand {
    /// Vertical share, in the same units as the view's `FillPortion` values.
    pub portion: u16,
    /// Number of equal-width selectable cells in this band (0 = display only).
    pub cells: usize,
}

impl GazeBand {
    pub const fn display(portion: u16) -> Self {
        Self { portion, cells: 0 }
    }

    pub const fn interactive(portion: u16, cells: usize) -> Self {
        Self { portion, cells }
    }
}

/// Fractional geometry shared by the gaze view and the resolver.
#[derive(Debug, Clone, PartialEq)]
pub struct GazeLayout {
    bands: Vec<GazeBand>,
    total_portion: f32,
}

impl GazeLayout {
    pub fn new(bands: Vec<GazeBand>) -> Self {
        let total_portion = bands.iter().map(|band| band.portion as f32).sum::<f32>().max(1.0);
        Self {
            bands,
            total_portion,
        }
    }

    /// Vertical share of `band_index`, for building the matching view.
    pub fn portion(&self, band_index: usize) -> Option<u16> {
        self.bands.get(band_index).map(|band| band.portion)
    }

    /// Vertical fraction range `[start, end)` of `band_index`.
    pub fn band_range(&self, band_index: usize) -> Option<(f32, f32)> {
        if band_index >= self.bands.len() {
            return None;
        }
        let mut start = 0.0;
        for band in self.bands.iter().take(band_index) {
            start += band.portion as f32 / self.total_portion;
        }
        let end = start + self.bands[band_index].portion as f32 / self.total_portion;
        Some((start, end))
    }

    /// Resolve a normalized gaze point to `(band, cell)`. Returns `None` for
    /// display-only bands and for coordinates outside `[0, 1]^2`. The extreme
    /// edge (`1.0`) clamps to the last cell: edge gaze is common and must
    /// select something rather than nothing.
    pub fn resolve(&self, x: f64, y: f64) -> Option<(usize, usize)> {
        if !x.is_finite() || !y.is_finite() {
            return None;
        }
        if !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
            return None;
        }
        let mut start = 0.0;
        for (index, band) in self.bands.iter().enumerate() {
            let end = start + band.portion as f32 / self.total_portion;
            let inside = if index + 1 == self.bands.len() {
                (start as f64) <= y && y <= (end as f64)
            } else {
                (start as f64) <= y && y < (end as f64)
            };
            if inside {
                if band.cells == 0 {
                    return None;
                }
                let cell = ((x * band.cells as f64).floor() as usize).min(band.cells - 1);
                return Some((index, cell));
            }
            start = end;
        }
        None
    }
}

/// Resolve a normalized point into a flat cell index of a `cols × rows` grid
/// (row-major), or `None` outside the unit square.
pub fn resolve_grid_cell(x: f64, y: f64, cols: usize, rows: usize) -> Option<usize> {
    if cols == 0 || rows == 0 {
        return None;
    }
    if !x.is_finite() || !y.is_finite() {
        return None;
    }
    if !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
        return None;
    }
    let col = ((x * cols as f64).floor() as usize).min(cols - 1);
    let row = ((y * rows as f64).floor() as usize).min(rows - 1);
    Some(row * cols + col)
}

// ---------------------------------------------------------------------------
// Local persistence for the gaze toggle (Linux-only, pre-provider-boundary)
// ---------------------------------------------------------------------------

const GAZE_CONFIG_FILE: &str = "gaze.json";

fn gaze_config_path() -> Option<std::path::PathBuf> {
    let base = env::var_os("XDG_CONFIG_HOME")
        .map(std::path::PathBuf::from)
        .or_else(|| env::var_os("HOME").map(|home| std::path::PathBuf::from(home).join(".config")))?;
    Some(base.join("wingmate").join(GAZE_CONFIG_FILE))
}

/// Best-effort load; any failure means "disabled" (fail-closed, no error UI).
pub fn load_gaze_enabled() -> bool {
    let Some(path) = gaze_config_path() else {
        return false;
    };
    let Ok(bytes) = std::fs::read(path) else {
        return false;
    };
    serde_json::from_slice::<serde_json::Value>(&bytes)
        .ok()
        .and_then(|value| value.get("enabled")?.as_bool())
        .unwrap_or(false)
}

/// Best-effort save; failures are silent (the toggle still applies live).
pub fn save_gaze_enabled(enabled: bool) {
    let Some(path) = gaze_config_path() else {
        return;
    };
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    // Only the toggle is stored: no coordinates, no gaze history, no phrases.
    let _ = std::fs::write(path, serde_json::json!({"enabled": enabled}).to_string());
}

// ---------------------------------------------------------------------------
// Tests: framing, compatibility, validity, resolution (synthetic fixtures)
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::net::UnixListener;

    fn sample_bytes(configure: impl FnOnce(&mut [u8; GAZE_SAMPLE_SIZE])) -> [u8; GAZE_SAMPLE_SIZE] {
        let mut payload = [0u8; GAZE_SAMPLE_SIZE];
        // present: GAZE_2D + both validity bits; both eyes valid.
        payload[0..4].copy_from_slice(
            &(GAZE_BIT_GAZE_2D | GAZE_BIT_VALIDITY_L | GAZE_BIT_VALIDITY_R).to_le_bytes(),
        );
        payload[8..16].copy_from_slice(&[0u8; 8]);
        payload[40..48].copy_from_slice(&0.5f64.to_le_bytes());
        payload[48..56].copy_from_slice(&0.25f64.to_le_bytes());
        configure(&mut payload);
        payload
    }

    fn framed(frame_type: u8, payload: &[u8]) -> Vec<u8> {
        let mut frame = vec![frame_type];
        frame.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        frame.extend_from_slice(payload);
        frame
    }

    #[test]
    fn decodes_pinned_sample_layout() {
        let payload = sample_bytes(|_| {});
        let sample = GazeSample::from_bytes(&payload).expect("pinned layout parses");
        assert_eq!(sample.gaze_x, 0.5);
        assert_eq!(sample.gaze_y, 0.25);
        assert_eq!(sample.usable_point(), Some((0.5, 0.25)));
    }

    #[test]
    fn rejects_wrong_sample_size_as_incompatible() {
        let mut decoder = FrameDecoder::new();
        // The stale documented size (232) must not parse as gaze.
        let short = vec![0u8; 232];
        let events = decoder.feed(&framed(SRV_GAZE, &short)).expect("framing ok");
        assert_eq!(
            events,
            vec![FrameEvent::Incompatible {
                frame_type: SRV_GAZE,
                payload_len: 232
            }]
        );
    }

    #[test]
    fn handles_header_split_across_reads() {
        let mut decoder = FrameDecoder::new();
        let payload = sample_bytes(|_| {});
        let frame = framed(SRV_GAZE, &payload);
        // Split inside the 5-byte header, then inside the payload.
        let first = decoder.feed(&frame[..2]).expect("partial header ok");
        assert!(first.is_empty());
        let second = decoder.feed(&frame[2..100]).expect("partial payload ok");
        assert!(second.is_empty());
        let third = decoder.feed(&frame[100..]).expect("remainder ok");
        assert_eq!(third.len(), 1);
        assert!(matches!(third[0], FrameEvent::Gaze(_)));
    }

    #[test]
    fn batches_multiple_frames_in_one_read() {
        let mut decoder = FrameDecoder::new();
        let payload = sample_bytes(|_| {});
        let mut batch = framed(SRV_GAZE, &payload);
        batch.extend_from_slice(&framed(SRV_GAZE, &payload));
        let events = decoder.feed(&batch).expect("batch ok");
        assert_eq!(events.len(), 2);
    }

    #[test]
    fn ignores_response_and_error_frames() {
        let mut decoder = FrameDecoder::new();
        let mut batch = framed(SRV_RESPONSE, &[CMD_SUBSCRIBE, 1, 2, 3]);
        batch.extend_from_slice(&framed(SRV_ERR, &[1, 0, 0, 0]));
        let events = decoder.feed(&batch).expect("non-gaze frames ok");
        assert!(events.is_empty());
    }

    #[test]
    fn flags_unknown_frame_types_without_interpreting_them() {
        let mut decoder = FrameDecoder::new();
        let events = decoder.feed(&framed(0x42, &[9, 9, 9])).expect("framing ok");
        assert_eq!(
            events,
            vec![FrameEvent::Incompatible {
                frame_type: 0x42,
                payload_len: 3
            }]
        );
    }

    #[test]
    fn fails_closed_on_overlength_payload() {
        let mut decoder = FrameDecoder::new();
        let mut frame = vec![SRV_GAZE];
        frame.extend_from_slice(&(MAX_PAYLOAD_LEN as u32 + 1).to_le_bytes());
        let result = decoder.feed(&frame);
        assert!(matches!(
            result,
            Err(OverlengthError { .. })
        ));
    }

    #[test]
    fn both_eyes_lost_yields_no_point() {
        let payload = sample_bytes(|bytes| {
            bytes[8..12].copy_from_slice(&4u32.to_le_bytes());
            bytes[12..16].copy_from_slice(&4u32.to_le_bytes());
        });
        let sample = GazeSample::from_bytes(&payload).expect("parses");
        assert_eq!(sample.usable_point(), None);
    }

    #[test]
    fn one_valid_eye_is_enough() {
        let payload = sample_bytes(|bytes| {
            bytes[8..12].copy_from_slice(&4u32.to_le_bytes());
        });
        let sample = GazeSample::from_bytes(&payload).expect("parses");
        assert_eq!(sample.usable_point(), Some((0.5, 0.25)));
    }

    #[test]
    fn missing_gaze_bit_yields_no_point() {
        let payload = sample_bytes(|bytes| {
            bytes[0..4].copy_from_slice(
                &(GAZE_BIT_VALIDITY_L | GAZE_BIT_VALIDITY_R).to_le_bytes(),
            );
        });
        let sample = GazeSample::from_bytes(&payload).expect("parses");
        assert_eq!(sample.usable_point(), None);
    }

    #[test]
    fn off_screen_gaze_yields_no_point() {
        let cases: [(f64, f64); 4] = [(-0.1, 0.5), (1.2, 0.5), (0.5, -0.01), (0.5, 1.01)];
        for (x, y) in cases {
            let payload = sample_bytes(|bytes| {
                bytes[40..48].copy_from_slice(&x.to_le_bytes());
                bytes[48..56].copy_from_slice(&y.to_le_bytes());
            });
            let sample = GazeSample::from_bytes(&payload).expect("parses");
            assert_eq!(sample.usable_point(), None, "({x}, {y})");
        }
    }

    #[test]
    fn non_finite_gaze_yields_no_point() {
        let payload = sample_bytes(|bytes| {
            bytes[40..48].copy_from_slice(&f64::NAN.to_le_bytes());
        });
        let sample = GazeSample::from_bytes(&payload).expect("parses");
        assert_eq!(sample.usable_point(), None);
    }

    #[test]
    fn layout_resolves_bands_and_cells() {
        let layout = GazeLayout::new(vec![
            GazeBand::display(2),       // draft
            GazeBand::interactive(1, 4), // controls
            GazeBand::interactive(7, 6), // grid
        ]);
        assert_eq!(layout.resolve(0.5, 0.05), None); // display band
        assert_eq!(layout.resolve(0.1, 0.25), Some((1, 0)));
        assert_eq!(layout.resolve(0.9, 0.25), Some((1, 3)));
        assert_eq!(layout.resolve(0.0, 0.9), Some((2, 0)));
        // Extreme edge clamps to the last cell instead of selecting nothing.
        assert_eq!(layout.resolve(1.0, 0.9), Some((2, 5)));
        // Band boundary belongs to the band below it.
        let (start, _) = layout.band_range(2).expect("band range");
        assert_eq!(layout.resolve(0.5, start as f64), Some((2, 3)));
    }

    #[test]
    fn grid_resolution_covers_corners() {
        assert_eq!(resolve_grid_cell(0.0, 0.0, 3, 2), Some(0));
        assert_eq!(resolve_grid_cell(0.99, 0.0, 3, 2), Some(2));
        assert_eq!(resolve_grid_cell(0.0, 0.99, 3, 2), Some(3));
        assert_eq!(resolve_grid_cell(1.0, 1.0, 3, 2), Some(5));
        assert_eq!(resolve_grid_cell(1.5, 0.5, 3, 2), None);
        assert_eq!(resolve_grid_cell(0.5, 0.5, 0, 2), None);
    }

    #[test]
    fn backoff_is_bounded() {
        assert_eq!(TobiifreeClient::backoff_delay(0), Duration::from_secs(1));
        assert_eq!(TobiifreeClient::backoff_delay(2), Duration::from_secs(4));
        assert_eq!(
            TobiifreeClient::backoff_delay(100),
            Duration::from_secs(15)
        );
    }

    #[test]
    fn subscribe_frame_carries_gaze_stream_id() {
        let frame = subscribe_frame();
        assert_eq!(frame[0], CMD_SUBSCRIBE);
        assert_eq!(u32::from_le_bytes(frame[1..5].try_into().unwrap()), 4);
        assert_eq!(
            u32::from_le_bytes(frame[5..9].try_into().unwrap()),
            STREAM_GAZE
        );
    }

    #[test]
    fn client_streams_gaze_end_to_end_over_unix_socket() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("gaze.sock");
        let listener = UnixListener::bind(&path).expect("bind");
        let mut client = TobiifreeClient::new();
        client.test_path = Some(path.to_string_lossy().into_owned());

        let now = Instant::now();
        let (_, live) = client.poll(now);
        assert!(!live, "no daemon yet");

        // Daemon side: accept, read subscribe, send one gaze frame split in two.
        let (mut daemon, _) = listener.accept().expect("accept");
        daemon.set_nonblocking(false).expect("blocking daemon");
        let mut hello = [0u8; 9];
        daemon.read_exact(&mut hello).expect("subscribe read");
        assert_eq!(hello[0], CMD_SUBSCRIBE);

        let payload = sample_bytes(|_| {});
        let frame = framed(SRV_GAZE, &payload);
        daemon.write_all(&frame[..100]).expect("partial write");
        daemon.write_all(&frame[100..]).expect("remainder write");

        // The next poll drains both parts, reassembles the split frame, and
        // resolves the newest usable point.
        let (poll2, live2) = client.poll(now);
        assert!(live2);
        assert_eq!(poll2.point, Some((0.5, 0.25)));
        assert_eq!(client.status(true, now), GazeStatus::Connected);
    }

    #[test]
    fn status_reports_daemon_unavailable_and_gaze_loss() {
        let mut client = TobiifreeClient::new();
        client.test_path = Some("/nonexistent-wingmate-gaze.sock".to_string());
        let now = Instant::now();
        let (_, live) = client.poll(now);
        assert!(!live);
        assert_eq!(client.status(true, now), GazeStatus::DaemonUnavailable);
        assert_eq!(client.status(false, now), GazeStatus::Disabled);
        // A connected stream with no samples yet reports gaze lost, never a
        // stale target: reacquisition always starts fresh.
        client.stream = UnixStream::pair().ok().map(|(a, _)| a);
        assert_eq!(client.status(true, now), GazeStatus::GazeLost);
    }
}
