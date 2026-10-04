use std::process::{Command, Stdio};

pub async fn speak(text: String, voice: String, rate: f32) -> Result<(), String> {
    if text.trim().is_empty() {
        return Ok(());
    }
    let status = platform_command(&text, &voice, rate)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map_err(|_| "System speech is unavailable".to_string())?;
    status
        .success()
        .then_some(())
        .ok_or_else(|| "System speech could not speak the message".to_string())
}

#[cfg(target_os = "linux")]
fn platform_command(text: &str, voice: &str, rate: f32) -> Command {
    let mut command = Command::new("spd-say");
    command
        .arg("--wait")
        .arg("--rate")
        .arg(((rate - 1.0) * 100.0).round().to_string());
    if voice != "default" && !voice.trim().is_empty() {
        // --synthesis-voice takes a voice name, as `spd-say -L` lists them.
        // --voice-type would only accept male1/female2 and friends, which is
        // not what the settings field asks the Communicator for.
        command.arg("--synthesis-voice").arg(voice);
    }
    command.arg(text);
    command
}

#[cfg(target_os = "macos")]
fn platform_command(text: &str, voice: &str, rate: f32) -> Command {
    let mut command = Command::new("say");
    command
        .arg("--rate")
        .arg((rate * 180.0).round().to_string());
    if voice != "default" && !voice.trim().is_empty() {
        command.arg("--voice").arg(voice);
    }
    command.arg(text);
    command
}

#[cfg(target_os = "windows")]
fn platform_command(text: &str, voice: &str, rate: f32) -> Command {
    // PowerShell does not populate $args under -Command, and interpolating the
    // Message into the command line would run whatever it contains. Button
    // text comes from imported OBZ files, so the script reads its inputs from
    // the environment instead of the command line.
    const SCRIPT: &str = concat!(
        "Add-Type -AssemblyName System.Speech; ",
        "$synthesizer = New-Object System.Speech.Synthesis.SpeechSynthesizer; ",
        "if ($env:WINGMATE_VOICE) { try { $synthesizer.SelectVoice($env:WINGMATE_VOICE) } catch { } }; ",
        "$synthesizer.Rate = [Math]::Round(([double]$env:WINGMATE_RATE - 1) * 5); ",
        "$synthesizer.Speak($env:WINGMATE_TEXT)"
    );
    let named = if voice == "default" || voice.trim().is_empty() {
        ""
    } else {
        voice
    };
    let mut command = Command::new("powershell.exe");
    command
        .args(["-NoProfile", "-NonInteractive", "-Command", SCRIPT])
        .env("WINGMATE_TEXT", text)
        .env("WINGMATE_VOICE", named)
        .env("WINGMATE_RATE", rate.to_string());
    command
}
