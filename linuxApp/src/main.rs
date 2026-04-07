use qmetaobject::prelude::*;
use qmetaobject::QObjectBox;
use std::env;
use std::path::PathBuf;
use std::process::{Child, Command};

use wingmate_kde::partner_window_bridge::{self, PartnerWindowBridge};

/// Locate the fat JAR for the Kotlin bridge server.
/// Priority: WINGMATE_LINUXAPP_JAR env var > default build path.
fn find_fat_jar() -> PathBuf {
    if let Ok(env_jar) = env::var("WINGMATE_LINUXAPP_JAR") {
        return PathBuf::from(env_jar);
    }

    // Derive from current executable location or fall back to the build dir
    let exe_dir = env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."));

    // Try relative to the linuxApp directory first
    let candidates = [
        exe_dir.join("../linuxApp/build/libs/linuxApp-all.jar"),
        exe_dir.join("../../linuxApp/build/libs/linuxApp-all.jar"),
        PathBuf::from("build/libs/linuxApp-all.jar"),
        PathBuf::from("../linuxApp/build/libs/linuxApp-all.jar"),
    ];

    for candidate in &candidates {
        if candidate.exists() {
            return candidate.clone();
        }
    }

    // Default fallback
    PathBuf::from("build/libs/linuxApp-all.jar")
}

/// Start the Kotlin bridge server as a child process.
fn start_bridge_server() -> Option<Child> {
    let jar_path = find_fat_jar();
    println!(
        "[Wingmate] Starting Kotlin bridge server from: {}",
        jar_path.display()
    );

    match Command::new("java")
        .arg("-jar")
        .arg(&jar_path)
        .arg("--no-partner-window") // Rust side owns the FTDI/EVE driver
        .spawn()
    {
        Ok(child) => {
            println!("[Wingmate] Kotlin bridge server started (PID: {})", child.id());
            // Give the server a moment to start up
            std::thread::sleep(std::time::Duration::from_secs(1));
            Some(child)
        }
        Err(e) => {
            eprintln!("[Wingmate] Failed to start Kotlin bridge server: {}", e);
            eprintln!("[Wingmate] JAR path: {}", jar_path.display());
            None
        }
    }
}

/// Find the QML resources directory.
fn find_qml_dir() -> PathBuf {
    let candidates = [
        PathBuf::from("src"),           // Run from linuxApp/
        PathBuf::from("linuxApp/src"),   // Run from project root
        PathBuf::from("../linuxApp/src"),
    ];

    for candidate in &candidates {
        if candidate.join("main.qml").exists() {
            return candidate.clone();
        }
    }

    // Fallback
    PathBuf::from("src")
}

fn main() {
    // ── Signal handling for graceful FTDI release ──
    // On Ctrl+C / SIGTERM / SIGHUP: send Shutdown to the bg driver thread
    // via a global channel clone, then force-exit after a grace period.
    ctrlc::set_handler(|| {
        eprintln!("\n[Wingmate] Signal received, shutting down partner window...");
        // Tell the bg thread to power off EVE and release FTDI
        partner_window_bridge::send_global_shutdown();
        // Give it a moment to complete the SPI shutdown sequence
        std::thread::sleep(std::time::Duration::from_millis(800));
        eprintln!("[Wingmate] Exiting.");
        std::process::exit(0);
    })
    .expect("Failed to set signal handler");

    // Start the Kotlin bridge server
    let mut bridge_process = start_bridge_server();

    if bridge_process.is_none() {
        eprintln!("[Wingmate] WARNING: Bridge server failed to start. UI will load but backend won't work.");
    }

    // Force KDE desktop style for Qt Quick Controls
    env::set_var("QT_QUICK_CONTROLS_STYLE", "org.kde.desktop");

    // Initialize QML engine
    let mut engine = QmlEngine::new();

    // Expose the API URL to QML
    engine.set_property("apiUrl".into(), QVariant::from(QString::from("http://localhost:8765")));

    // ── Partner Window bridge (Rust → FTDI → EVE display) ──
    let pw_bridge = QObjectBox::new(create_partner_window_bridge());
    engine.set_object_property("partnerWindow".into(), pw_bridge.pinned());
    println!("[Wingmate] Partner window bridge registered");

    // Load the main QML file
    let qml_dir = find_qml_dir();
    let main_qml = qml_dir.join("main.qml");

    println!("[Wingmate] Loading QML from: {}", main_qml.display());

    let qml_path = QString::from(main_qml.to_string_lossy().to_string());
    engine.load_file(qml_path);

    println!("[Wingmate] Wingmate KDE started successfully");

    // Run the Qt event loop (blocks until window closes or signal received)
    engine.exec();

    // ── Cleanup ──
    // Drop the bridge first — this sends Shutdown to the bg thread,
    // which powers off the EVE display and releases the FTDI device.
    println!("[Wingmate] Shutting down partner window...");
    drop(pw_bridge);

    // Terminate the Kotlin bridge process
    if let Some(ref mut process) = bridge_process {
        println!("[Wingmate] Stopping Kotlin bridge server...");
        let _ = process.kill();
        let _ = process.wait();
    }

    println!("[Wingmate] Shutdown complete.");
}

/// Create and start the partner window bridge.
fn create_partner_window_bridge() -> PartnerWindowBridge {
    let mut bridge = PartnerWindowBridge::default();
    bridge.start();
    bridge
}
