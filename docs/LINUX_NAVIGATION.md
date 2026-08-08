# Linux navigation model

The native Linux client follows the same information architecture as Android and iOS
while using COSMIC application chrome and system symbolic icons.

## Primary destinations

- **Keyboard** is the phrase and typed-communication workspace.
- **Screens** is the visual AAC board library and workspace.
- **Settings** is a secondary destination opened from the COSMIC header. Closing it
  returns to the last Keyboard or Screens workspace.
- **Pronunciation** is a Settings category rather than a separate primary destination.

Welcome and fullscreen communication intentionally hide the workspace controls to
keep their focus order short.

## Interaction rules

- Frequently used icon controls have a minimum 48×48 logical-pixel target.
- Icon-only controls use icon-theme symbolic names, tooltips, and accessible names.
- Navigation order is Keyboard, Screens, then Settings; content order follows visual
  top-to-bottom and left-to-right order.
- Playback order matches the mobile clients: hold/restore, speak, pause, resume, stop,
  clear, and fullscreen.
- Dense secondary actions use icon-only buttons with accessible labels so the 720×480
  minimum window remains usable.
- Rows containing creation, playback, import/export, or destructive actions wrap at
  narrow widths instead of shrinking targets.
