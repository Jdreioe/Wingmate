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

The Rest mode control remains at the edge of the workspace. While resting,
ordinary taps and clicks continue to work, and resuming starts a fresh hover timer.

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

If app-level pointer emphasis is unavailable, Wingmate keeps the OS pointer path
active and explains the limitation in settings.
