//! Background tracking loop: capture → MediaPipe → smoothing → normalized point.
//!
//! Mirrors the partner-window driver-thread pattern: one thread owns the camera
//! and the (non-`Send`) MediaPipe graph exclusively, and communicates with the
//! UI through a channel.

use std::sync::mpsc::{channel, Receiver, Sender, TryRecvError};
use std::thread::JoinHandle;

pub mod camera;
mod geometry;
mod landmarker;

pub use crate::tracking::camera::{Frame, FrameSource, WebcamSource};
pub use crate::tracking::geometry::{AffineCalibration, CalibrationCollection, OneEuro, Point};
use crate::tracking::geometry::OneEuro as Smoothing;
use crate::tracking::landmarker::HeadLandmarker;

/// A normalized (0..1) pointer position delivered to the UI thread.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TrackPoint {
    pub x: f32,
    pub y: f32,
    /// Estimated presence/confidence; `0` means no face is currently tracked.
    pub confidence: f32,
}

/// Messages the tracker emits to the UI.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum TrackEvent {
    /// A new smoothed pointer position.
    Position(TrackPoint),
    /// No face currently tracked (idle state — the UI may keep last position).
    Lost,
}

/// Control messages the UI can send into the tracker thread.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum TrackCommand {
    /// Pause/resume capture and inference.
    SetEnabled(bool),
    /// Drop smoothing history (e.g. after calibration).
    Reset,
    /// Stop the thread and exit cleanly.
    Shutdown,
}

/// Handle owned by the UI thread.
pub struct Tracker {
    cmd_tx: Sender<TrackCommand>,
    join: Option<JoinHandle<()>>,
}

impl Tracker {
    /// Spawn a tracker thread. The camera source is opened by `open_source`
    /// *inside* the thread because webcam handles are not `Send`; only the
    /// (sendable) factory closure crosses the thread boundary.
    ///
    /// On camera/model failure the thread logs and exits; the `events` receiver
    /// then returns `RecvError::Disconnected`. The caller can surface that.
    pub fn start<S: FrameSource + 'static>(
        open_source: impl FnOnce() -> Result<S, String> + Send + 'static,
        model: impl AsRef<std::path::Path> + Send + 'static,
        calibration: AffineCalibration,
        smoothing_alpha: f32,
    ) -> Result<(Tracker, Receiver<TrackEvent>), String> {
        let (cmd_tx, cmd_rx) = channel::<TrackCommand>();
        let (evt_tx, evt_rx) = channel::<TrackEvent>();

        let model_path = model.as_ref().to_path_buf();

        let join = std::thread::Builder::new()
            .name("wingmate-headtrack".into())
            .spawn(move || {
                let mut source = match open_source() {
                    Ok(s) => s,
                    Err(e) => {
                        eprintln!("[HeadTrack] source open failed: {e}");
                        return;
                    }
                };
                run_loop(
                    &mut source,
                    &model_path,
                    calibration,
                    smoothing_alpha,
                    &cmd_rx,
                    &evt_tx,
                );
            })
            .map_err(|e| e.to_string())?;

        Ok((Tracker { cmd_tx, join: Some(join) }, evt_rx))
    }

    pub fn set_enabled(&self, enabled: bool) {
        let _ = self.cmd_tx.send(TrackCommand::SetEnabled(enabled));
    }

    pub fn reset(&self) {
        let _ = self.cmd_tx.send(TrackCommand::Reset);
    }
}

impl Drop for Tracker {
    fn drop(&mut self) {
        let _ = self.cmd_tx.send(TrackCommand::Shutdown);
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

/// Default model path, overridable via `WINGMATE_FACE_MODEL`.
pub fn default_model_path() -> std::path::PathBuf {
    std::env::var_os("WINGMATE_FACE_MODEL")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| std::path::PathBuf::from("models/face_landmarker.task"))
}

/// Convenience: spawn a tracker with the default webcam and model.
///
/// Returns `Err` only when the thread cannot be spawned; camera/model problems
/// surface as the receiver disconnecting. Wraps a [`Tracker`] for lifetime and
/// a [`Receiver`] for events.
pub fn start_default(
    camera_index: u32,
    calibration: AffineCalibration,
    smoothing_alpha: f32,
) -> Result<(Tracker, Receiver<TrackEvent>), String> {
    Tracker::start(
        move || camera::WebcamSource::open(camera_index),
        default_model_path(),
        calibration,
        smoothing_alpha,
    )
}

fn run_loop(
    source: &mut impl FrameSource,
    model: &std::path::Path,
    calibration: AffineCalibration,
    smoothing_alpha: f32,
    cmd_rx: &Receiver<TrackCommand>,
    evt_tx: &Sender<TrackEvent>,
) {
    let mut enabled = true;
    let mut smoother = Smoothing::new(smoothing_alpha);
    let mut landmarker = match HeadLandmarker::build(model) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("[HeadTrack] failed to load model: {e}");
            return;
        }
    };

    loop {
        // Drain pending commands without blocking the frame loop.
        loop {
            match cmd_rx.try_recv() {
                Ok(TrackCommand::SetEnabled(en)) => {
                    enabled = en;
                    if !en {
                        smoother.reset();
                    }
                }
                Ok(TrackCommand::Reset) => smoother.reset(),
                Ok(TrackCommand::Shutdown) => {
                    eprintln!("[HeadTrack] shutdown");
                    return;
                }
                Err(TryRecvError::Disconnected) => return,
                Err(TryRecvError::Empty) => break,
            }
        }

        if !enabled {
            std::thread::sleep(std::time::Duration::from_millis(50));
            continue;
        }

        let frame = match source.next_frame() {
            Ok(Some(frame)) => frame,
            Ok(None) => {
                eprintln!("[HeadTrack] source ended");
                return;
            }
            Err(e) => {
                eprintln!("[HeadTrack] frame error: {e}");
                std::thread::sleep(std::time::Duration::from_millis(100));
                continue;
            }
        };

        match landmarker.detect_rgba(frame.width, frame.height, &frame.rgba) {
            Ok(Some(raw)) => {
                let calibrated = calibration.apply(raw);
                let smoothed = smoother.push(calibrated);
                let _ = evt_tx.send(TrackEvent::Position(TrackPoint {
                    x: smoothed.x,
                    y: smoothed.y,
                    confidence: 1.0,
                }));
            }
            Ok(None) => {
                let _ = evt_tx.send(TrackEvent::Lost);
            }
            Err(e) => {
                eprintln!("[HeadTrack] inference error: {e}");
                let _ = evt_tx.send(TrackEvent::Lost);
            }
        }
    }
}

/// Convert a normalized point to a board grid cell `(row, column)`.
pub fn cell_for(point: Point, rows: usize, columns: usize) -> Option<(usize, usize)> {
    if rows == 0 || columns == 0 {
        return None;
    }
    let row = (point.y * rows as f32).floor() as usize;
    let col = (point.x * columns as f32).floor() as usize;
    if row >= rows || col >= columns {
        return None;
    }
    Some((row, col))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tracking::geometry::Point;

    #[test]
    fn cell_for_maps_normalized_to_grid() {
        assert_eq!(cell_for(Point { x: 0.0, y: 0.0 }, 4, 4), Some((0, 0)));
        assert_eq!(cell_for(Point { x: 0.99, y: 0.99 }, 4, 4), Some((3, 3)));
        assert_eq!(cell_for(Point { x: 0.5, y: 0.5 }, 4, 4), Some((2, 2)));
        assert_eq!(cell_for(Point { x: 0.5, y: 0.5 }, 0, 4), None);
    }

    #[test]
    fn cell_for_pointing_past_the_grid_is_none() {
        // A pointer exactly at the bottom/right edge maps to a cell outside the
        // grid; the guard must return None rather than overflow the index.
        assert_eq!(cell_for(Point { x: 1.0, y: 1.0 }, 4, 4), None);
        assert_eq!(cell_for(Point { x: 0.0, y: 1.0 }, 4, 4), None);
    }
}
