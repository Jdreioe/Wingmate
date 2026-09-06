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

## Native gaze on the TD-I13 (planned)

Wingmate has a diagnostic gaze reader, but native gaze cannot select targets yet. Planned support for the TD-I13
running Linux, using the `tobiifreed` daemon from
[`Aetherall/tobiifree`](https://github.com/Aetherall/tobiifree), is specified in
[the TD-I13 gaze plan](GAZE_TD_I13.md) and tracked in
[#129](https://github.com/Jdreioe/Wingmate/issues/129). Until it ships, an OS
pointer driven by the tracker is the supported path.

## Windows Eye Control

Wingmate does not yet ship a supported Windows client, but Windows users of a
compatible Wingmate environment can enable the OS pointer at **Settings →
Accessibility → Interaction → Eye control** after installing and calibrating a
supported tracker. Microsoft’s setup guide is at
<https://support.microsoft.com/accessibility/windows-eye-control/get-started-with-eye-control-in-windows>.

## Android

Android accepts external mouse, keyboard, switch, and OS-provided pointer events.
Pointer emphasis adds to the system pointer; it never hides or replaces it.
