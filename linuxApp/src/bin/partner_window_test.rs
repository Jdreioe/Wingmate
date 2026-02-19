//! CLI test tool for the Partner Window display.
//!
//! Run with:
//!   cargo run --bin partner-window-test -- --help

use wingmate_kde::partner_window;

use clap::{Parser, Subcommand};
use std::process;

#[derive(Parser)]
#[command(name = "partner-window-test")]
#[command(about = "Tobii Partner Window display test tool (FTDI FT232H → EVE GPU)")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Check if FT232H device is connected
    Discover,
    /// Initialize the display only (no test output)
    Init,
    /// Display text on the partner window
    Text {
        /// Message to display
        message: String,
        /// Font size (28, 30, or 31). Default: 31
        #[arg(short, long, default_value_t = 31)]
        font: i16,
    },
    /// Fill the screen with a solid color
    Color {
        /// Red (0-255)
        r: u8,
        /// Green (0-255)
        g: u8,
        /// Blue (0-255)
        b: u8,
    },
    /// Display color bar test pattern
    Pattern,
    /// Draw geometric shapes
    Geometry,
    /// Scrolling text animation
    Animate,
    /// Run the full test suite (colors + text + geometry + pattern + animation)
    All,
    /// Shut down the display cleanly
    Off,
}

fn main() {
    let cli = Cli::parse();

    match cli.command {
        Commands::Discover => {
            if partner_window::is_device_connected() {
                println!("✓ FTDI FT232H (0403:6014) detected");
            } else {
                println!("✗ No FTDI FT232H found — check USB connection");
                process::exit(1);
            }
            return;
        }
        _ => {}
    }

    let mut pw = match partner_window::open_ftdi() {
        Ok(pw) => pw,
        Err(e) => {
            eprintln!("Failed to open FTDI device: {e}");
            process::exit(1);
        }
    };

    if let Err(e) = pw.init() {
        eprintln!("Initialization failed: {e}");
        process::exit(1);
    }

    let result = match cli.command {
        Commands::Discover => unreachable!(),
        Commands::Init => {
            println!("Display initialized — press Ctrl+C to exit");
            loop {
                std::thread::sleep(std::time::Duration::from_secs(60));
            }
        }
        Commands::Text { ref message, font } => {
            pw.display_text(&message, None, None, font, (255, 255, 255))
        }
        Commands::Color { r, g, b } => pw.clear_screen(r, g, b),
        Commands::Pattern => partner_window::test_pattern(&mut pw),
        Commands::Geometry => partner_window::test_geometry(&mut pw),
        Commands::Animate => partner_window::test_animation(&mut pw),
        Commands::All => partner_window::run_all_tests(&mut pw),
        Commands::Off => pw.shutdown(),
    };

    if let Err(e) = result {
        eprintln!("Error: {e}");
        let _ = pw.shutdown();
        process::exit(1);
    }

    // Keep display on for a moment, then shut down
    match cli.command {
        Commands::Off | Commands::Init => {}
        _ => {
            println!("\nDisplay will shut down in 3 seconds...");
            std::thread::sleep(std::time::Duration::from_secs(3));
            let _ = pw.shutdown();
        }
    }
}
