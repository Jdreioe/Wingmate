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

## Desktop

The desktop client in `desktopApp/` is in development and offers none of the
interaction settings above: it takes ordinary pointer and keyboard events only.
An OS pointer driven by head or eye tracking therefore moves and clicks in it as
in any other application, but there is no hover-to-select, Select key, or Rest
mode yet. See the [accessibility matrix](ACCESSIBILITY_MATRIX.md).

## Windows Eye Control

Wingmate does not yet ship a supported Windows client, but Windows users of a
compatible Wingmate environment can enable the OS pointer at **Settings →
Accessibility → Interaction → Eye control** after installing and calibrating a
supported tracker. Microsoft’s setup guide is at
<https://support.microsoft.com/accessibility/windows-eye-control/get-started-with-eye-control-in-windows>.

## Android

Android accepts external mouse, keyboard, switch, and OS-provided pointer events.
Pointer emphasis adds to the system pointer; it never hides or replaces it.
