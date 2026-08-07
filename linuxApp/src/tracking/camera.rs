//! Webcam capture via `nokhwa`, decoupled behind a trait so the TD-I13 gaze
//! stream and future sources can share the same pipeline.

use nokhwa::Camera;
use nokhwa::pixel_format::RgbAFormat;
use nokhwa::utils::{
    CameraFormat, CameraIndex, FrameFormat, RequestedFormat, RequestedFormatType, Resolution,
};

/// A single captured frame: RGBA bytes plus size.
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

/// Something that produces frames. Kept tiny and sync so a camera thread,
/// a UDP stream, or a test double can implement it.
pub trait FrameSource {
    /// Block until the next frame is available (or return `None` on EOF).
    fn next_frame(&mut self) -> Result<Option<Frame>, String>;
}

/// Default webcam source. The native input is enabled via the `input-native`
/// Cargo feature (see `Cargo.toml`).
pub struct WebcamSource {
    camera: Camera,
}

impl WebcamSource {
    /// Open the camera with the given index (0 = first device) at 640x480.
    pub fn open(index: u32) -> Result<Self, String> {
        let requested = RequestedFormat::new::<RgbAFormat>(RequestedFormatType::Closest(
            CameraFormat::new(Resolution::new(640, 480), FrameFormat::RAWRGB, 30),
        ));
        let mut camera =
            Camera::new(CameraIndex::Index(index), requested).map_err(|e| e.to_string())?;
        camera.open_stream().map_err(|e| e.to_string())?;
        Ok(Self { camera })
    }
}

impl FrameSource for WebcamSource {
    fn next_frame(&mut self) -> Result<Option<Frame>, String> {
        let buffer = self.camera.frame().map_err(|e| e.to_string())?;
        let resolution = buffer.resolution();
        let image = buffer.decode_image::<RgbAFormat>().map_err(|e| e.to_string())?;
        Ok(Some(Frame {
            width: resolution.width_x,
            height: resolution.height_y,
            rgba: image.into_raw(),
        }))
    }
}
