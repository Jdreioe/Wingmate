pub mod i18n;

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
        pub fn is_available(&self) -> bool {
            false
        }
    }

    pub fn send_global_shutdown() {}
}
