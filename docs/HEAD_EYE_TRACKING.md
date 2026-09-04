# Pointer input and Rest mode

Wingmate works with ordinary OS pointer and keyboard events. A mouse, trackpad,
head pointer, eye pointer, or adaptive switch can therefore use the same interface;
Wingmate does not open a camera or receive gaze data.

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

## Windows Eye Control

Wingmate does not currently ship a native Windows client, but Windows users of a
compatible Wingmate environment can enable the OS pointer at **Settings →
Accessibility → Interaction → Eye control** after installing and calibrating a
supported tracker. Microsoft’s setup guide is at
<https://support.microsoft.com/accessibility/windows-eye-control/get-started-with-eye-control-in-windows>.

## Android and Linux

Android accepts external mouse, keyboard, switch, and OS-provided pointer events.
Linux accepts the same inputs through the desktop environment on both Wayland and
X11. Pointer emphasis adds to the system pointer; it never hides or replaces it.

## Linux native gaze (TD-I13 via tobiifreed, P1)

On Linux, Wingmate can consume gaze directly from a running
[`tobiifreed`](https://github.com/Aetherall/tobiifree) daemon instead of going
through an OS eye pointer:

1. Start `tobiifreed` (it owns USB access; Wingmate only reads its Unix socket
   and never takes exclusive hardware ownership).
2. Open **Settings → Access → Eye tracking (TD-I13)** and enable it. The status
   line reports disabled, connecting, connected, gaze lost, incompatible
   protocol, or daemon unavailable.
3. Set **Dwell to select** above 0 — gaze reuses the same dwell timing, Rest
   mode, debounce, and selection feedback as pointer input.
4. Open **eye-tracking communication** (or the fullscreen button on either
   workspace). Gaze resolves to large phrase, category, board, and control
   targets in this fullscreen surface; looking away or losing both eyes cancels
   pending dwell and reacquisition starts fresh.

Vocabulary editing stays on the ordinary workspaces by design, and no gaze
cursor is shown. Gaze coordinates and eye measurements are memory-only
operational data: Wingmate never logs, persists, or transmits them — only
semantic target transitions reach the local access controller.

If app-level pointer emphasis is unavailable, Wingmate keeps the OS pointer path
active and explains the limitation in settings.
