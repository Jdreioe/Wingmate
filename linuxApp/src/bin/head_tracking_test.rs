//! CLI harness for the MediaPipe head-tracking pipeline (requires the
//! `tracking` feature). Opens the webcam, runs landmark inference, applies
//! smoothing, and prints normalized pointer positions.
//!
//! ```text
//! cargo run --manifest-path linuxApp/Cargo.toml --features tracking --bin head-tracking-test -- --model /path/to/face_landmarker.task
//! ```

use std::time::Duration;
use wingmate_kde::tracking::camera::WebcamSource;
use wingmate_kde::tracking::{AffineCalibration, Tracker, TrackEvent};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mut model = "models/face_landmarker.task".to_string();
    let mut camera_index = 0u32;

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--model" => {
                i += 1;
                model = args.get(i).cloned().unwrap_or(model);
            }
            "--camera" => {
                i += 1;
                camera_index = args
                    .get(i)
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(camera_index);
            }
            other => {
                eprintln!("unknown argument: {other}");
                std::process::exit(2);
            }
        }
        i += 1;
    }

    println!("[HeadTrackTest] opening camera #{camera_index}, model {model}");

    let (tracker, events) = match Tracker::start(
        move || WebcamSource::open(camera_index),
        model.clone(),
        AffineCalibration::IDENTITY,
        0.35,
    ) {
        Ok(pair) => pair,
        Err(e) => {
            eprintln!("[HeadTrackTest] tracker error: {e}");
            std::process::exit(1);
        }
    };

    println!(
        "[HeadTrackTest] running. Press Ctrl-C to stop. Model: {}",
        model
    );

    let mut frames_since_last = 0usize;
    loop {
        match events.recv_timeout(Duration::from_millis(500)) {
            Ok(TrackEvent::Position(p)) => {
                frames_since_last = 0;
                println!(
                    "[HeadTrackTest] pointer ({:.3}, {:.3}) conf={:.2}",
                    p.x, p.y, p.confidence
                );
            }
            Ok(TrackEvent::Lost) => {
                frames_since_last += 1;
                if frames_since_last % 10 == 1 {
                    println!("[HeadTrackTest] no face…");
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                println!("[HeadTrackTest] no events for 500ms");
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                eprintln!("[HeadTrackTest] tracker thread exited");
                break;
            }
        }
    }

    tracker.set_enabled(false);
}
