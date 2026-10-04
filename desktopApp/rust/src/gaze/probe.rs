//! `wingmate-desktop --gaze-probe`: a local check that the daemon streams what
//! this build expects.
//!
//! The decoder pins the `GazeSample` layout to the tracker upstream supports
//! (see `docs/GAZE_TD_I13.md`). The TD-I13's tracker is a different product ID,
//! so before hit-testing depends on those offsets, this prints what actually
//! arrives: payload sizes, the present mask, per-eye validity, and where the
//! sample says the user is looking.
//!
//! Everything goes to the terminal and nowhere else. Nothing is written to
//! disk, and the probe has to be asked for by name.

use super::client::{Client, socket_path};
use super::protocol::{GAZE_SAMPLE_SIZE, ProtocolError, Sample};
use super::{ConnectError, ReadError};
use std::time::{Duration, Instant};

const RUN_FOR: Duration = Duration::from_secs(10);
const PRINT_EVERY: Duration = Duration::from_millis(200);

pub fn run() {
    println!("Wingmate gaze probe");
    println!("socket: {}", socket_path().display());
    println!("expecting a {GAZE_SAMPLE_SIZE}-byte GazeSample\n");

    let mut client = match Client::connect() {
        Ok(client) => client,
        Err(ConnectError::Unavailable) => {
            println!("No daemon is listening. Start tobiifreed, then try again.");
            return;
        }
        Err(ConnectError::Io(error)) => {
            println!("Could not subscribe to the daemon: {error}");
            return;
        }
    };
    println!(
        "Subscribed. Look around the screen for {} seconds.\n",
        RUN_FOR.as_secs()
    );

    let started = Instant::now();
    let mut last_print = Instant::now() - PRINT_EVERY;
    let mut samples = 0u32;
    let mut tracked = 0u32;

    while started.elapsed() < RUN_FOR {
        match client.next_sample() {
            Ok(sample) => {
                samples += 1;
                if sample.point.is_some() {
                    tracked += 1;
                }
                if last_print.elapsed() >= PRINT_EVERY {
                    last_print = Instant::now();
                    println!("{}", describe(&sample));
                }
            }
            Err(ReadError::Disconnected) => {
                println!("\nThe daemon closed the connection.");
                break;
            }
            Err(ReadError::Protocol(error)) => {
                println!("\n{}", explain(error));
                break;
            }
        }
    }

    println!("\n{samples} samples, {tracked} with a position.");
    if samples > 0 {
        println!(
            "The pinned {GAZE_SAMPLE_SIZE}-byte layout decodes on this tracker. \
             If the positions above tracked where you looked, the offsets are right too."
        );
    }
}

fn describe(sample: &Sample) -> String {
    match sample.point {
        Some(point) => format!(
            "frame {:>8}  {:>10} µs  gaze {:.3}, {:.3}",
            sample.frame_counter, sample.timestamp_us, point.x, point.y
        ),
        None => format!(
            "frame {:>8}  {:>10} µs  no position (eyes lost, field absent, or off display)",
            sample.frame_counter, sample.timestamp_us
        ),
    }
}

fn explain(error: ProtocolError) -> String {
    match error {
        ProtocolError::UnexpectedGazeSize(size) => format!(
            "This tracker sends a {size}-byte GazeSample, not {GAZE_SAMPLE_SIZE}.\n\
             The field offsets in docs/GAZE_TD_I13.md are per-device: the decoder\n\
             needs this layout before gaze can select anything."
        ),
        ProtocolError::MissingFields(missing) => format!(
            "The daemon omits fields selection needs (present-mask bits {missing:#06x}).\n\
             Gaze cannot be timed or trusted without them."
        ),
        ProtocolError::OversizedPayload(size) => {
            format!("The daemon announced a {size}-byte payload, which is not a message we know.")
        }
    }
}
