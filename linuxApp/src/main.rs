use qmetaobject::prelude::*;
use std::env;
use std::path::PathBuf;
use std::process::{Child, Command};

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
    // Start the Kotlin bridge server
    let mut bridge_process = start_bridge_server();

    if bridge_process.is_none() {
        eprintln!("[Wingmate] WARNING: Bridge server failed to start. UI will load but backend won't work.");
    }



    // Force KDE desktop style for Qt Quick Controls
    env::set_var("QT_QUICK_CONTROLS_STYLE", "org.kde.desktop");

    // Initialize QML engine
    let mut engine = QmlEngine::new();

    // Expose the API URL to QML (same as the C++ version)
    engine.set_property("apiUrl".into(), QVariant::from(QString::from("http://localhost:8765")));

    // Load the main QML file
    let qml_dir = find_qml_dir();
    let main_qml = qml_dir.join("main.qml");

    println!("[Wingmate] Loading QML from: {}", main_qml.display());

    let qml_path = QString::from(main_qml.to_string_lossy().to_string());
    engine.load_file(qml_path);

    println!("[Wingmate] Wingmate KDE started successfully");

    // Run the Qt event loop
    engine.exec();

    // Cleanup: terminate the bridge process
    if let Some(ref mut process) = bridge_process {
        println!("[Wingmate] Stopping Kotlin bridge server...");
        let _ = process.kill();
        let _ = process.wait();
    }

    println!("[Wingmate] Shutdown complete.");
}
