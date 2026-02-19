//! QObject bridge exposing the Partner Window EVE driver to QML.
//!
//! Architecture:
//!   QML thread  ──Qt signal──▶  PartnerWindowBridge (QObject)
//!                                      │  mpsc::channel
//!                                      ▼
//!                               Background thread
//!                               (open_ftdi → init → displayText loop)
//!
//! The background thread owns the FTDI device and EVE driver exclusively.
//! The QObject methods (called from the QML main thread) simply push commands
//! through a channel — they never block on SPI.

use crate::partner_window;

use qmetaobject::prelude::*;

use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

// ─── Commands sent from QML thread to background thread ─────────────────────

enum PwCommand {
    /// Update the displayed text.
    UpdateText(String),
    /// Enable or disable mirroring.
    SetEnabled(bool),
    /// Clear the display.
    Clear,
    /// Shut down the driver and exit the thread.
    Shutdown,
}

// ─── Shared state (background thread writes, QObject reads) ─────────────────

#[derive(Default, Clone)]
struct SharedState {
    device_connected: bool,
    active: bool,
}

// ─── QML-exposed QObject ────────────────────────────────────────────────────

#[derive(QObject, Default)]
#[allow(non_snake_case)]
pub struct PartnerWindowBridge {
    base: qt_base_class!(trait QObject),

    // ── Properties ──
    /// Whether mirroring is enabled by the user (persisted in settings).
    enabled: qt_property!(bool; NOTIFY enabledChanged),
    /// Whether the FTDI FT232H USB device is physically connected.
    deviceConnected: qt_property!(bool; NOTIFY deviceConnectedChanged),
    /// Whether the display is actively mirroring (enabled AND connected AND initialized).
    active: qt_property!(bool; NOTIFY activeChanged),

    // ── Signals ──
    enabledChanged: qt_signal!(),
    deviceConnectedChanged: qt_signal!(),
    activeChanged: qt_signal!(),

    // ── Invokable methods (called from QML) ──
    updateText: qt_method!(fn(&mut self, text: QString)),
    setEnabled: qt_method!(fn(&mut self, enabled: bool)),
    clear: qt_method!(fn(&self)),
    shutdown: qt_method!(fn(&self)),

    // ── Internal ──
    #[allow(dead_code)]
    tx: Option<mpsc::Sender<PwCommand>>,
    shared: Option<Arc<Mutex<SharedState>>>,
    poll_timer: Option<Box<dyn std::any::Any>>,
}

impl PartnerWindowBridge {
    /// Start the background driver thread. Call once after constructing the QObject.
    pub fn start(&mut self) {
        let (tx, rx) = mpsc::channel::<PwCommand>();
        let shared = Arc::new(Mutex::new(SharedState::default()));

        self.tx = Some(tx);
        self.shared = Some(shared.clone());

        // Spawn the background driver loop
        thread::spawn(move || {
            driver_thread(rx, shared);
        });

        println!("[PartnerWindow] Bridge started");
    }

    // ── QML-callable methods ────────────────────────────────────────────

    fn updateText(&mut self, text: QString) {
        let text = text.to_string();
        if let Some(tx) = &self.tx {
            let _ = tx.send(PwCommand::UpdateText(text));
        }
    }

    fn setEnabled(&mut self, enabled: bool) {
        self.enabled = enabled;
        self.enabledChanged();
        if let Some(tx) = &self.tx {
            let _ = tx.send(PwCommand::SetEnabled(enabled));
        }
    }

    fn clear(&self) {
        if let Some(tx) = &self.tx {
            let _ = tx.send(PwCommand::Clear);
        }
    }

    fn shutdown(&self) {
        if let Some(tx) = &self.tx {
            let _ = tx.send(PwCommand::Shutdown);
        }
    }

    /// Poll shared state and update QObject properties.
    /// Called periodically from the QML timer or from Rust.
    pub fn poll_state(&mut self) {
        if let Some(shared) = &self.shared {
            if let Ok(state) = shared.lock() {
                if self.deviceConnected != state.device_connected {
                    self.deviceConnected = state.device_connected;
                    self.deviceConnectedChanged();
                }
                if self.active != state.active {
                    self.active = state.active;
                    self.activeChanged();
                }
            }
        }
    }
}

// ─── Background driver thread ───────────────────────────────────────────────

fn driver_thread(rx: mpsc::Receiver<PwCommand>, shared: Arc<Mutex<SharedState>>) {
    println!("[PartnerWindow] Background thread started");

    let mut enabled = false;
    let mut driver: Option<partner_window::PartnerWindow<
        ftdi_embedded_hal::SpiDevice<ftdi::Device>,
        ftdi_embedded_hal::OutputPin<ftdi::Device>,
    >> = None;
    let mut last_text = String::new();
    let mut last_device_check = std::time::Instant::now();

    loop {
        // Process all pending commands (non-blocking drain)
        loop {
            match rx.try_recv() {
                Ok(PwCommand::UpdateText(text)) => {
                    last_text = text;
                }
                Ok(PwCommand::SetEnabled(en)) => {
                    enabled = en;
                    if !enabled {
                        // Shut down driver if disabling
                        if let Some(ref mut d) = driver {
                            let _ = d.shutdown();
                        }
                        driver = None;
                        if let Ok(mut s) = shared.lock() {
                            s.active = false;
                        }
                    }
                }
                Ok(PwCommand::Clear) => {
                    if let Some(ref mut d) = driver {
                        let _ = d.clear_screen(0, 0, 0);
                    }
                }
                Ok(PwCommand::Shutdown) => {
                    println!("[PartnerWindow] Shutdown requested");
                    if let Some(ref mut d) = driver {
                        let _ = d.shutdown();
                    }
                    if let Ok(mut s) = shared.lock() {
                        s.active = false;
                        s.device_connected = false;
                    }
                    println!("[PartnerWindow] Background thread exiting");
                    return;
                }
                Err(mpsc::TryRecvError::Empty) => break,
                Err(mpsc::TryRecvError::Disconnected) => {
                    println!("[PartnerWindow] Channel closed, exiting");
                    if let Some(ref mut d) = driver {
                        let _ = d.shutdown();
                    }
                    return;
                }
            }
        }

        // Periodic device connection check (every 3 seconds)
        if last_device_check.elapsed() >= Duration::from_secs(3) {
            last_device_check = std::time::Instant::now();
            let connected = partner_window::is_device_connected();
            if let Ok(mut s) = shared.lock() {
                s.device_connected = connected;
            }

            // Auto-connect if enabled and device appeared
            if enabled && connected && driver.is_none() {
                match partner_window::open_ftdi() {
                    Ok(mut pw) => {
                        match pw.init() {
                            Ok(()) => {
                                println!("[PartnerWindow] Display initialized successfully");
                                // Send current text immediately
                                if !last_text.is_empty() {
                                    let _ = pw.display_text(
                                        &last_text, None, None, 31, (255, 255, 255),
                                    );
                                }
                                driver = Some(pw);
                                if let Ok(mut s) = shared.lock() {
                                    s.active = true;
                                }
                            }
                            Err(e) => {
                                eprintln!("[PartnerWindow] Init failed: {e}");
                                let _ = pw.shutdown();
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!("[PartnerWindow] Failed to open FTDI: {e}");
                    }
                }
            }

            // Auto-disconnect if device disappeared
            if !connected && driver.is_some() {
                println!("[PartnerWindow] Device disconnected");
                // Don't try shutdown — device is gone
                driver = None;
                if let Ok(mut s) = shared.lock() {
                    s.active = false;
                }
            }
        }

        // If we have a pending text update and the driver is active, display it
        if driver.is_some() && !last_text.is_empty() {
            // We use a simple approach: the text field in QML calls updateText on
            // every keystroke. We only push to the display here (on the bg thread)
            // so the debounce is naturally ~50ms (our sleep interval).
            if let Some(ref mut d) = driver {
                let display_text = last_text.clone();
                // Show text (white on black, centered, largest ROM font)
                match d.display_text(&display_text, None, None, 31, (255, 255, 255)) {
                    Ok(()) => {}
                    Err(e) => {
                        eprintln!("[PartnerWindow] Display error: {e}");
                        // Assume device disconnected on SPI error
                        driver = None;
                        if let Ok(mut s) = shared.lock() {
                            s.active = false;
                        }
                    }
                }
                // Clear last_text so we don't re-send the same text every loop
                // We'll get a new PwCommand::UpdateText when text changes
                last_text.clear();
            }
        }

        // Sleep briefly before next iteration
        thread::sleep(Duration::from_millis(50));
    }
}
