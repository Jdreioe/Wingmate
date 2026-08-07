//! MediaPipe face-landmark inference via the maintained `mediapipe` crate
//! (bindings to Google's MediaPipe Tasks C API, dlopened `libmediapipe`).
//!
//! The crate is not linked at build time; the native library is fetched from
//! the official PyPI wheel on first use and cached under
//! `$XDG_CACHE_HOME/mediapipe-rs/`. Set `MEDIAPIPE_LIB` to override. The model
//! file itself is supplied by the caller (typically `face_landmarker.task`,
//! downloaded separately — it is not redistributed here).

use std::num::NonZeroU32;

use mediapipe::{FaceLandmarker, Image, ModelSource, Size};

use crate::tracking::geometry::Point;

/// Indices in the 478-landmark MediaPipe face mesh that define a coarse
/// "nose-forward" heading, from which a pointer offset is derived.
///
/// - 1: nose tip
/// - 4: nose bridge (between the eyes)
pub const LANDMARK_NOSE_TIP: usize = 1;
pub const LANDMARK_NOSE_BRIDGE: usize = 4;

/// How much of the nose position delta becomes pointer motion.
///
/// A larger divisor makes the pointer less twitchy for the same head
/// movement; this is a pragmatic default that calibration refines further.
const NOSE_TO_POINTER_FACTOR: f32 = 3.0;

/// A MediaPipe-based head tracker. It is `!Send`/`!Sync` (the underlying
/// bindings hold the dlopened graph), so it must live on one thread and be
/// driven from there — exactly like the partner-window driver thread.
pub struct HeadLandmarker {
    landmarker: FaceLandmarker,
}

/// Error returned when a frame cannot be fed to MediaPipe.
#[derive(Debug)]
pub enum LandmarkError {
    /// Model file missing or unreadable.
    Model(String),
    /// Pixel buffer had an unexpected size.
    BadFrame,
    /// MediaPipe inference failed.
    Inference(String),
}

impl std::fmt::Display for LandmarkError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            LandmarkError::Model(m) => write!(f, "landmarker model error: {m}"),
            LandmarkError::BadFrame => write!(f, "landmarker frame buffer invalid"),
            LandmarkError::Inference(e) => write!(f, "landmarker inference failed: {e}"),
        }
    }
}

impl HeadLandmarker {
    /// Build from a model path (e.g. `models/face_landmarker.task`).
    pub fn build(model_path: impl AsRef<std::path::Path>) -> Result<Self, LandmarkError> {
        if !model_path.as_ref().exists() {
            return Err(LandmarkError::Model(format!(
                "{}",
                model_path.as_ref().display()
            )));
        }
        let landmarker = FaceLandmarker::builder(ModelSource::path(model_path.as_ref()))
            .num_faces(NonZeroU32::new(1).unwrap())
            .output_blendshapes(true)
            .build()
            .map_err(|e| LandmarkError::Model(e.to_string()))?;
        Ok(Self { landmarker })
    }

    /// Run inference on one RGBA frame. Returns the normalized head direction
    /// as a [`Point`] (relative to the frame, 0..1) plus a presence flag.
    pub fn detect_rgba(
        &mut self,
        width: u32,
        height: u32,
        rgba: &[u8],
    ) -> Result<Option<Point>, LandmarkError> {
        if rgba.len() as u64 != u64::from(width) * u64::from(height) * 4 {
            return Err(LandmarkError::BadFrame);
        }
        let image = Image::from_rgba(Size { width, height }, rgba)
            .map_err(|e| LandmarkError::Inference(e.to_string()))?;
        let result = self
            .landmarker
            .detect(&image)
            .map_err(|e| LandmarkError::Inference(e.to_string()))?;

        let Some(face) = result.landmarks.first() else {
            return Ok(None);
        };

        let tip = face.get(LANDMARK_NOSE_TIP);
        let Some(tip) = tip else {
            return Ok(None);
        };
        let bridge = face.get(LANDMARK_NOSE_BRIDGE);

        Ok(Some(derive_pointer(
            (tip.point.x(), tip.point.y()),
            bridge.map(|b| (b.point.x(), b.point.y())),
        )))
    }
}

/// Derive a normalized pointer position from nose landmarks.
///
/// Pure and testable. `nose_tip`/`nose_bridge` are in MediaPipe's normalized
/// frame coordinates (0..1). The camera image is mirrored, so the x axis is
/// flipped: moving the head right moves the pointer right.
pub fn derive_pointer(nose_tip: (f32, f32), nose_bridge: Option<(f32, f32)>) -> Point {
    let (tx, ty) = match nose_bridge {
        Some((bx, by)) => {
            let dx = nose_tip.0 - bx;
            let dy = nose_tip.1 - by;
            // Bridge is the stable origin; the tip delta is dampened so small
            // tilts produce proportional but controlled cursor movement.
            (bx + dx / NOSE_TO_POINTER_FACTOR, by + dy / NOSE_TO_POINTER_FACTOR)
        }
        None => nose_tip,
    };
    Point {
        x: (1.0 - tx).clamp(0.0, 1.0),
        y: ty.clamp(0.0, 1.0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Missing model must error, never panic.
    #[test]
    fn missing_model_errors() {
        assert!(HeadLandmarker::build("/nonexistent/face_landmarker.task").is_err());
    }

    /// The webcam feed is mirrored: moving the head right puts the nose at a
    /// *lower* raw x, and the flip turns that into a higher (rightward) pointer x.
    #[test]
    fn mirror_flip_moves_with_head() {
        // Head right → nose raw x lower → pointer should be more right.
        let head_right = derive_pointer((0.4, 0.5), Some((0.5, 0.5)));
        let head_left = derive_pointer((0.6, 0.5), Some((0.5, 0.5)));
        assert!(
            head_right.x > head_left.x,
            "head right should move pointer right (got {} vs {})",
            head_right.x,
            head_left.x
        );
    }

    /// A centered nose with no bridge yields the mirrored center.
    #[test]
    fn centered_nose_centers_pointer() {
        let p = derive_pointer((0.5, 0.5), None);
        assert!((p.x - 0.5).abs() < 1e-4);
    }

    /// Outputs are always clamped into the valid range.
    #[test]
    fn output_is_clamped() {
        let p = derive_pointer((0.01, 0.99), None);
        assert!(p.x >= 0.0 && p.x <= 1.0 && p.y >= 0.0 && p.y <= 1.0);
    }
}
