# Pointer input and Rest mode

Wingmate works with ordinary OS pointer and keyboard events. A mouse, trackpad,
head pointer, eye pointer, or adaptive switch can therefore use the same interface.
The Linux desktop client also offers native Tobii gaze and experimental local
webcam tracking with calibration, as described below.

## Wingmate interaction settings

Open **Settings → Interaction** (inside **Accessibility** on clients that use
settings tabs) to configure:

- **Hover to select**: keep the pointer over a communication target to use it.
- **Select key**: press Space, Enter, or a chosen function key to use the target
  under the pointer or keyboard focus. Holding the key activates only once.
- **Rest mode key**: pause or resume Hover to select and the Select key.
- **Pointer emphasis**: keep the system pointer or emphasize the current target
  with a larger high-contrast ring or outline.

The Rest mode control stays at the bottom edge of the workspace. While resting,
ordinary taps and clicks continue to work, and resuming starts a fresh hover timer.
The paused status appears as a notification next to the control and goes away on
its own after a few seconds; the control itself remains available to resume.

## iPhone and iPad Eye Tracking

On a supported device with iOS or iPadOS 18 or later, open **Settings →
Accessibility → Eye Tracking**, enable Eye Tracking, and complete calibration.
The system pointer and dwell action work with Wingmate's normal buttons. Apple’s
current instructions are at
<https://support.apple.com/guide/iphone/control-iphone-with-the-movement-of-your-eyes-iph66057d0f6/ios>.

Switch Control can also combine Eye Tracking or Head Tracking with a configured
Select Item switch. Wingmate leaves these native actions intact.

## Desktop

The desktop client in `desktopApp/` is in development. In **Settings > Access**,
set a dwell duration above zero to select Screen Buttons and communication
controls by hovering. The delay before dwell starts filters brief contact with
neighboring targets. A highlight identifies the target and a progress bar shows
dwell progress. This uses the pointer provided by the operating system, including
vendor eye/head tracking tools.

Optional Select and Rest mode shortcuts accept names such as `F8`, `F9`,
`Space`, or `Enter`; leave them empty to disable them. Select acts on the hovered
communication target. Rest pauses dwell and Select. Click or touch **Resume
input**, use the Rest shortcut, or hold Select for two seconds and release to
resume. Direct clicks and touch remain available while resting.

Leaving the communication screen or losing window focus cancels pending dwell.
Library, settings, and editor controls still require ordinary input. See the
[accessibility matrix](ACCESSIBILITY_MATRIX.md) for the remaining gaps.

## Native gaze on the TD-I13, Linux development build

Native gaze now drives the desktop Screen runner through `tobiifreed`. It is
still awaiting verification on the real TD-I13 and is tracked in
[#129](https://github.com/Jdreioe/Wingmate/issues/129).

For AppImage installation and one-time USB permissions, run
`bash scripts/install-wingmate.sh --appimage /path/to/Wingmate.AppImage --setup-gaze`
from a checkout, then reconnect the tracker. See the
[Linux installer instructions](../desktopApp/README.md#install-on-linux).
The AppImage bundles the daemon; startup is opt-in under Settings > Access.

1. Enable bundled daemon startup in Settings > Access, or start a calibrated
   `tobiifreed` that supports the TD-I13's `2104:031e` USB ID.
   Wingmate expects `$XDG_RUNTIME_DIR/tobiifreed/gaze.sock`, or
   `/tmp/tobiifreed/gaze.sock` when that environment variable is unset.
2. Check the protocol on the device with
   `cargo run --manifest-path desktopApp/Cargo.toml -- --gaze-probe`.
   This explicit diagnostic prints live samples to the terminal. Confirm that
   the positions follow your gaze before trying selection.
3. Launch Wingmate normally. Set a dwell duration above zero in
   **Settings > Access**, then open a Screen.
4. Select **Start gaze (fullscreen)**. The daemon's calibrated display area
   must be the display showing Wingmate. This increment supports one display;
   it cannot detect a mismatched display-area configuration.
5. Look at a Screen Button, Back, Clear, Hold, or Speak. Wingmate highlights the
   resolved control and activates it once after dwell. Spanning Buttons use
   their full displayed area. No gaze cursor is drawn or OS pointer moved.

Invalid gaze immediately cancels pending dwell. A stream with no fresh usable
sample for 100 ms also loses its target. Reacquisition starts fresh. Rest mode
suppresses gaze activation; use the existing Rest shortcut or click/touch
controls to pause and resume. A parked mouse cannot replace a live gaze target.

Connection loss reports **Gaze daemon unavailable** and retries automatically.
An unsupported payload reports **Gaze protocol incompatible**. Pointer dwell
remains available while the daemon is unavailable or incompatible; move off
and back onto a control to acquire a pointer target. Touch and clicks remain
available throughout.

**Stop gaze** ends the session and returns to windowed mode. Leaving the runner,
losing window focus, or leaving fullscreen also ends gaze input. Start it again
explicitly when ready. Gaze selection is off at startup and must be started explicitly.

The daemon, USB permissions, calibration, and display setup must currently be
prepared outside Wingmate. In-app calibration is M5.
Library, editor, and settings controls are not native gaze targets. See
[the engineering plan](GAZE_TD_I13.md) for those later milestones.

## Windows Eye Control

Wingmate does not yet ship a supported Windows client, but Windows users of a
compatible Wingmate environment can enable the OS pointer at **Settings →
Accessibility → Interaction → Eye control** after installing and calibrating a
supported tracker. Microsoft’s setup guide is at
<https://support.microsoft.com/accessibility/windows-eye-control/get-started-with-eye-control-in-windows>.

## Android

Android accepts external mouse, keyboard, switch, and OS-provided pointer events.
Pointer emphasis adds to the system pointer; it never hides or replaces it.

### Gaze settings and bundled daemon (M4)

Linux AppImages built from this revision include a pinned `tobiifreed` with
TD-I13 and Eye Tracker 5 support. In **Settings > Access**, the native gaze
section appears when a supported USB tracker or daemon is detected, or startup
has been enabled. USB detection and daemon availability are reported separately.
The dwell duration above the section applies to both pointer and native gaze.
Open a Screen first to enable fullscreen gaze from settings; **Stop gaze** in
the runner disables selection. Selection always starts off.

Opt into **Start tobiifreed with Wingmate** to save that local startup preference
and start the bundled daemon now and on subsequent launches. Wingmate reuses an
existing socket; it does not replace an independently running daemon. Disabling
startup or exiting Wingmate stops only its own child. A failed child is reported
and can be retried with **Retry bundled daemon**. Child output is discarded.
Development builds need `tobiifreed` beside `wingmate-desktop`, or a separately
started daemon (`tobiifreed`). No runtime download or firmware flashing occurs.

If the tracker is detected but the daemon cannot stream, run
`bash scripts/install-wingmate.sh --setup-gaze`, reconnect the tracker, and retry.
If the socket connects but the protocol is incompatible, use the bundled pinned
daemon. Calibration and display configuration remain external prerequisites.

**Show live diagnostics** subscribes only after you opt in. It shows current
normalized coordinates or the stream failure state without activating controls,
logging, saving or exporting samples. Leaving Access settings or losing focus
closes diagnostics; enable it again explicitly when needed.

Build the bundle with `bash scripts/build-gaze-daemon.sh` before Linux packaging
(Zig 0.15.2, pkg-config and libusb development headers required). The AppImage
contains the GPL licence and exact patched source under
`usr/share/doc/wingmate/tobiifree`; releases also carry the source archive.

## Webcam gaze on Linux desktop

An experimental webcam provider is available in Settings > Access beside Tobii.
It runs locally and includes automatic timed calibration and validation. Install
its optional runtime with `bash scripts/install-webcam-gaze.sh`. See
[webcam setup and validation limits](WEBCAM_GAZE.md). Sandberg Webcam Pro hardware
results are pending; start with a small grid of large targets.
