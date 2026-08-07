#[cfg(feature = "partner-window")]
pub mod partner_window;
#[cfg(feature = "partner-window")]
pub mod partner_window_bridge;

#[cfg(not(feature = "partner-window"))]
pub mod partner_window_bridge {
    /// No-op controller used when the optional FTDI partner display is not compiled in.
    #[derive(Default)]
    pub struct PartnerWindowController {
        enabled: bool,
    }

    impl PartnerWindowController {
        pub fn start(&mut self) {}
        pub fn update_text(&self, _text: impl Into<String>) {}
        pub fn set_enabled(&mut self, enabled: bool) {
            self.enabled = enabled;
        }
        pub fn set_font_size(&mut self, _font: i32) {}
        pub fn set_idle_enabled(&mut self, _enabled: bool) {}
        pub fn clear(&self) {}
        pub fn shutdown(&self) {}
        pub fn state(&self) -> (bool, bool) {
            (false, false)
        }
    }

    pub fn send_global_shutdown() {}
}

#[cfg(feature = "tracking")]
pub mod tracking;

#[cfg(not(feature = "tracking"))]
pub mod tracking {
    //! No-op module when the optional MediaPipe tracking pipeline is not
    //! compiled in. Mirrors the `partner_window_bridge` feature-stub pattern so
    //! `main.rs` can reference these types unconditionally.

    #[derive(Debug, Clone, Copy, PartialEq, Default)]
    pub struct Point {
        pub x: f32,
        pub y: f32,
    }

    pub mod camera {
        pub struct Frame {
            pub width: u32,
            pub height: u32,
            pub rgba: Vec<u8>,
        }

        pub trait FrameSource {
            fn next_frame(&mut self) -> Result<Option<Frame>, String>;
        }

        pub struct WebcamSource;

        impl WebcamSource {
            pub fn open(_index: u32) -> Result<Self, String> {
                Err("tracking feature disabled".into())
            }
        }

        impl FrameSource for WebcamSource {
            fn next_frame(&mut self) -> Result<Option<Frame>, String> {
                Err("tracking feature disabled".into())
            }
        }
    }

    pub mod geometry {
        #[derive(Debug, Clone, Copy, PartialEq, Default)]
        pub struct AffineCalibration {
            pub scale_x: f32,
            pub offset_x: f32,
            pub scale_y: f32,
            pub offset_y: f32,
        }

        impl AffineCalibration {
            pub const IDENTITY: Self = Self {
                scale_x: 1.0,
                offset_x: 0.0,
                scale_y: 1.0,
                offset_y: 0.0,
            };
        }

        #[derive(Debug, Clone, Copy, Default)]
        pub struct OneEuro;

        #[derive(Debug, Clone, Default)]
        pub struct CalibrationCollection;

        impl CalibrationCollection {
            pub fn new() -> Self {
                Self
            }
            pub fn add(&mut self, _raw: super::Point, _target: super::Point) {}
            pub fn len(&self) -> usize {
                0
            }
            pub fn clear(&mut self) {}
            pub fn solve(&self) -> AffineCalibration {
                AffineCalibration::IDENTITY
            }
        }
    }

    pub mod landmarker {
        pub struct HeadLandmarker;

        impl HeadLandmarker {
            pub fn build(_path: impl AsRef<std::path::Path>) -> Result<Self, String> {
                Err("tracking feature disabled".into())
            }
        }
    }

    #[derive(Debug, Clone, Copy, PartialEq)]
    pub struct TrackPoint {
        pub x: f32,
        pub y: f32,
        pub confidence: f32,
    }

    #[derive(Debug, Clone, Copy, PartialEq)]
    pub enum TrackEvent {
        Position(TrackPoint),
        Lost,
    }

    #[derive(Debug, Clone, Copy, PartialEq)]
    pub enum TrackCommand {
        SetEnabled(bool),
        Reset,
        Shutdown,
    }

    pub struct Tracker;

    impl Tracker {
        pub fn set_enabled(&self, _enabled: bool) {}
        pub fn reset(&self) {}
    }

    pub fn default_model_path() -> std::path::PathBuf {
        std::path::PathBuf::from("models/face_landmarker.task")
    }

    pub fn start_default(
        _camera_index: u32,
        _calibration: geometry::AffineCalibration,
        _smoothing_alpha: f32,
    ) -> Result<(Tracker, std::sync::mpsc::Receiver<TrackEvent>), String> {
        Err("tracking feature disabled".into())
    }

    pub fn cell_for(_point: Point, _rows: usize, _columns: usize) -> Option<(usize, usize)> {
        None
    }
}
