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

use crate::logo_data;
use crate::partner_window;

use qmetaobject::prelude::*;

use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

/// Global shutdown sender — the signal handler uses this to tell the
/// background thread to power off the EVE display and release the FTDI
/// device before the process exits.
static SHUTDOWN_TX: Mutex<Option<mpsc::Sender<PwCommand>>> = Mutex::new(None);

/// Send a shutdown command to the background driver thread from anywhere
/// (signal handler, Drop, etc.). Safe to call multiple times.
pub fn send_global_shutdown() {
    if let Ok(mut guard) = SHUTDOWN_TX.lock() {
        if let Some(tx) = guard.take() {
            let _ = tx.send(PwCommand::Shutdown);
        }
    }
}

// ─── Commands sent from QML thread to background thread ─────────────────────

enum PwCommand {
    /// Update the displayed text.
    UpdateText(String),
    /// Enable or disable mirroring.
    SetEnabled(bool),
    /// Update display font setting.
    SetFont(i16),
    /// Enable or disable idle face when no text for 10s.
    SetIdleEnabled(bool),
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
    /// EVE ROM font index (16-34, default 31).
    fontSize: qt_property!(i32; NOTIFY fontSizeChanged),
    /// Whether to show idle face after 10s of no text input.
    idleEnabled: qt_property!(bool; NOTIFY idleEnabledChanged),

    // ── Signals ──
    enabledChanged: qt_signal!(),
    deviceConnectedChanged: qt_signal!(),
    activeChanged: qt_signal!(),
    fontSizeChanged: qt_signal!(),
    idleEnabledChanged: qt_signal!(),

    // ── Invokable methods (called from QML) ──
    updateText: qt_method!(fn(&mut self, text: QString)),
    setEnabled: qt_method!(fn(&mut self, enabled: bool)),
    setFontSize: qt_method!(fn(&mut self, font: i32)),
    setIdleEnabled: qt_method!(fn(&mut self, enabled: bool)),
    clear: qt_method!(fn(&self)),
    shutdown: qt_method!(fn(&self)),
    pollState: qt_method!(fn(&mut self)),

    // ── Internal ──
    #[allow(dead_code)]
    tx: Option<mpsc::Sender<PwCommand>>,
    shared: Option<Arc<Mutex<SharedState>>>,
}

#[allow(non_snake_case)]
impl PartnerWindowBridge {
    /// Start the background driver thread. Call once after constructing the QObject.
    pub fn start(&mut self) {
        let (tx, rx) = mpsc::channel::<PwCommand>();
        let shared = Arc::new(Mutex::new(SharedState::default()));

        self.tx = Some(tx.clone());
        self.shared = Some(shared.clone());

        // Initialize display settings with defaults
        self.fontSize = 31;
        self.idleEnabled = true;

        // Store a clone of the sender globally so the signal handler can
        // trigger shutdown even if Drop doesn't run (e.g. process::exit).
        if let Ok(mut guard) = SHUTDOWN_TX.lock() {
            *guard = Some(tx);
        }

        // Spawn the background driver loop
        thread::spawn(move || {
            driver_thread(rx, shared);
        });

        println!("[PartnerWindow] Bridge started");
    }

    // ── QML-callable methods ────────────────────────────────────────────
    // camelCase names required by QML convention

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

    fn setFontSize(&mut self, font: i32) {
        let font = font.clamp(16, 34);
        self.fontSize = font;
        self.fontSizeChanged();
        if let Some(tx) = &self.tx {
            let _ = tx.send(PwCommand::SetFont(font as i16));
        }
    }

    fn setIdleEnabled(&mut self, enabled: bool) {
        self.idleEnabled = enabled;
        self.idleEnabledChanged();
        if let Some(tx) = &self.tx {
            let _ = tx.send(PwCommand::SetIdleEnabled(enabled));
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
    /// Called periodically from the QML Timer component.
    fn pollState(&mut self) {
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

/// Ensure the FTDI device is released and the EVE display is powered off
/// when the bridge is dropped (app close, signal, panic unwind, etc.).
impl Drop for PartnerWindowBridge {
    fn drop(&mut self) {
        println!("[PartnerWindow] Bridge dropping — sending shutdown to driver thread");
        if let Some(tx) = self.tx.take() {
            let _ = tx.send(PwCommand::Shutdown);
            // Give the background thread a moment to cleanly power off the display
            // before the process exits and tears down the USB handle.
            std::thread::sleep(Duration::from_millis(500));
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
    let mut font: i16 = 31;
    let mut logo_uploaded = false;
    let mut idle_enabled = true;
    let mut showing_idle = false;
    let mut last_text_time = std::time::Instant::now();
    // Face transition: mouth progressively opens when typing resumes after idle.
    let mut face_transition = false;
    let mut current_face_stage: u8 = 0;
    let mut transition_updates: u32 = 0;
    let mut pending_face_stage: Option<u8> = None;
    // Whether the display settings changed and we need to re-render.
    let mut settings_dirty = false;

    loop {
        // Process all pending commands (non-blocking drain)
        loop {
            match rx.try_recv() {
                Ok(PwCommand::UpdateText(text)) => {
                    // Strip <tags> before displaying on partner window
                    let cleaned = partner_window::strip_tags(&text);
                    if !cleaned.is_empty() {
                        last_text_time = std::time::Instant::now();

                        if showing_idle || face_transition {
                            // Coming out of idle — animate mouth opening
                            transition_updates += 1;
                            let stage = partner_window::mouth_stage(transition_updates);

                            if stage >= 4 {
                                // Enough keystrokes — switch to normal text display
                                face_transition = false;
                                showing_idle = false;
                                current_face_stage = 0;
                                transition_updates = 0;
                                pending_face_stage = None;
                                last_text = cleaned;
                            } else {
                                face_transition = true;
                                showing_idle = false;
                                if stage != current_face_stage {
                                    pending_face_stage = Some(stage);
                                }
                                last_text.clear(); // Don't trigger text rendering
                            }
                        } else {
                            last_text = cleaned;
                            showing_idle = false;
                        }
                    }
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
                Ok(PwCommand::SetFont(f)) => {
                    font = f;
                    settings_dirty = true;
                    let (cpl, ml) = partner_window::auto_layout(font);
                    println!("[PartnerWindow] Font={font} → {cpl} chars/line × {ml} lines");
                }
                Ok(PwCommand::SetIdleEnabled(en)) => {
                    idle_enabled = en;
                    if !idle_enabled && (showing_idle || face_transition) {
                        // Clear idle/face immediately
                        if let Some(ref mut d) = driver {
                            let _ = d.clear_screen(0, 0, 0);
                        }
                        showing_idle = false;
                        face_transition = false;
                        current_face_stage = 0;
                        transition_updates = 0;
                        pending_face_stage = None;
                    }
                    println!("[PartnerWindow] Idle face: {}", if en { "enabled" } else { "disabled" });
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

        // Render face transition frame (mouth opening animation)
        if let Some(stage) = pending_face_stage.take() {
            current_face_stage = stage;
            if let Some(ref mut d) = driver {
                let art = partner_window::face_art(stage);
                let result = if logo_uploaded {
                    let lx = (partner_window::DISPLAY_WIDTH - logo_data::LOGO_WIDTH - 4) as i16;
                    let ly = (partner_window::DISPLAY_HEIGHT - logo_data::LOGO_HEIGHT - 4) as i16;
                    d.display_idle_with_logo(
                        art, 26, (100, 100, 100),
                        0, lx, ly,
                    )
                } else {
                    d.display_text_wrapped(art, 26, (100, 100, 100))
                };
                match result {
                    Ok(()) => {}
                    Err(e) => {
                        eprintln!("[PartnerWindow] Face display error: {e}");
                        driver = None;
                        if let Ok(mut s) = shared.lock() {
                            s.active = false;
                        }
                    }
                }
            }
        }

        // Periodic device connection check (every 3 seconds)
        // ONLY check when the driver is NOT open — is_device_connected()
        // tries to open the device which conflicts with an existing handle.
        if last_device_check.elapsed() >= Duration::from_secs(3) {
            last_device_check = std::time::Instant::now();

            if driver.is_some() {
                // Driver already open — assume connected.
                // If the device physically disappears, the next SPI write
                // will fail and we'll drop the driver then.
                if let Ok(mut s) = shared.lock() {
                    s.device_connected = true;
                }
            } else {
                // No driver — safe to probe for the device
                let connected = partner_window::is_device_connected();
                if let Ok(mut s) = shared.lock() {
                    s.device_connected = connected;
                }

                // Auto-connect if enabled and device appeared
                if enabled && connected {
                    match partner_window::open_ftdi() {
                        Ok(mut pw) => {
                            match pw.init() {
                                Ok(()) => {
                                    println!("[PartnerWindow] Display initialized successfully");
                                    // Upload app logo to RAM_G for idle display
                                    match pw.upload_bitmap(
                                        0, // handle 0
                                        partner_window::RAM_G, // start of graphics RAM
                                        &logo_data::LOGO_32X32_L8,
                                        partner_window::L8,
                                        logo_data::LOGO_WIDTH,
                                        logo_data::LOGO_HEIGHT,
                                    ) {
                                        Ok(()) => { logo_uploaded = true; }
                                        Err(e) => { eprintln!("[PartnerWindow] Logo upload failed: {e}"); }
                                    }
                                    if !last_text.is_empty() {
                                        let _ = pw.display_text_wrapped(
                                            &last_text, font, (255, 255, 255),
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
            }
        }

        // If we have a pending text update (or settings changed) and the driver is active, display it
        if driver.is_some() && (!last_text.is_empty() || settings_dirty) {
            if let Some(ref mut d) = driver {
                // Re-render when settings change, even if last_text was cleared
                let render_text = if !last_text.is_empty() {
                    last_text.clone()
                } else if settings_dirty {
                    // Settings changed but no new text — we can't re-render without text.
                    // The next text update will use the new settings.
                    settings_dirty = false;
                    String::new()
                } else {
                    String::new()
                };

                if !render_text.is_empty() {
                    match d.display_text_wrapped(&render_text, font, (255, 255, 255)) {
                        Ok(()) => {}
                        Err(e) => {
                            eprintln!("[PartnerWindow] Display error: {e}");
                            driver = None;
                            if let Ok(mut s) = shared.lock() {
                                s.active = false;
                            }
                        }
                    }
                    last_text.clear();
                    settings_dirty = false;
                }
            }
        }

        // Sleep briefly before next iteration
        thread::sleep(Duration::from_millis(50));

        // Idle face: if no text update for 10 seconds, show idle graphic
        if idle_enabled && driver.is_some() && !showing_idle
            && last_text_time.elapsed() >= Duration::from_secs(10)
        {
            // Reset any in-progress face transition
            face_transition = false;
            current_face_stage = 0;
            transition_updates = 0;
            pending_face_stage = None;

            if let Some(ref mut d) = driver {
                let idle_art = partner_window::face_art(0); // Closed mouth
                let result = if logo_uploaded {
                    // Show face + logo in bottom-right corner
                    let lx = (partner_window::DISPLAY_WIDTH - logo_data::LOGO_WIDTH - 4) as i16;
                    let ly = (partner_window::DISPLAY_HEIGHT - logo_data::LOGO_HEIGHT - 4) as i16;
                    d.display_idle_with_logo(
                        idle_art, 26, (100, 100, 100),
                        0, lx, ly,
                    )
                } else {
                    d.display_text_wrapped(idle_art, 26, (100, 100, 100))
                };
                match result {
                    Ok(()) => {
                        showing_idle = true;
                        println!("[PartnerWindow] Showing idle face");
                    }
                    Err(e) => {
                        eprintln!("[PartnerWindow] Idle display error: {e}");
                        driver = None;
                        if let Ok(mut s) = shared.lock() {
                            s.active = false;
                        }
                    }
                }
            }
        }
    }
}
