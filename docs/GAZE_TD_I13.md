# Plan: native gaze on the TD-I13 through tobiifreed

Engineering plan for [#129](https://github.com/Jdreioe/Wingmate/issues/129)
(P1 of the access roadmap, [#123](https://github.com/Jdreioe/Wingmate/issues/123)).
User-facing setup lives in [HEAD_EYE_TRACKING.md](HEAD_EYE_TRACKING.md); the
per-platform status table is [ACCESSIBILITY_MATRIX.md](ACCESSIBILITY_MATRIX.md).

## Goal

A TD-I13 running Linux selects Wingmate communication targets with gaze, using
[`Aetherall/tobiifree`](https://github.com/Aetherall/tobiifree) for hardware
access and Wingmate's existing shared dwell/activation policy for selection.
No system pointer is moved and no gaze-following cursor is drawn.

Target client: `desktopApp/` (Rust + `iced`) on Linux. Android and iOS keep the
OS pointer path; native gaze there is out of scope (#127 records that decision).
Windows reaches the same feature through its vendor stack rather than through
`tobiifreed` — see [Windows](#windows) below.

## What tobiifree gives us

`tobiifreed` owns the USB device and broadcasts samples over a Unix socket, so
Wingmate never touches libusb and never links GPL-3.0 driver code — the process
boundary is also the licence boundary. Vendoring or reimplementing the driver is
a non-goal.

Pinned facts (read from `tobiifree` `main`, 2026-09):

| Item | Value |
| --- | --- |
| Socket | `$XDG_RUNTIME_DIR/tobiifreed/gaze.sock` (falls back to `/tmp`) |
| Framing | `[u8 msg_type][u32 LE payload_len][payload]`, 5-byte header |
| Subscribe | client msg `0x01`, payload `u32 LE 0x500` (`STREAM_GAZE`) |
| Disconnect | client msg `0xFF`, empty payload |
| Gaze event | server msg `0x01`, payload is one `GazeSample` |
| Other server msgs | `0x02` response, `0x03` display area, `0xFF` error (u32 code) |
| Clients | daemon serves up to 16 subscribers, so the overlay/demo can run alongside Wingmate |

`GazeSample` is a native Zig `extern struct` copied onto the wire, currently
**392 bytes**. `ARCHITECTURE.md` upstream still documents 232 bytes, so the
layout has changed at least once and the protocol is explicitly experimental.
Wingmate therefore pins these offsets and rejects any gaze frame whose payload
length is not exactly the pinned size:

| Offset | Type | Field | Use |
| --- | --- | --- | --- |
| 0 | u32 | `present_mask` | required-field check |
| 4 | u32 | `frame_counter` | drop duplicates/reorder |
| 8 | u32 | `validity_L` | `0` valid, `4` not detected |
| 12 | u32 | `validity_R` | same |
| 16 | i64 | `timestamp_us` | device µs clock, monotonic input to dwell |
| 40 | 2×f64 | `gaze_point_2d_norm` | filtered binocular gaze, `[0,1]²` |
| 376 | 2×f64 | `gaze_point_2d_unfiltered` | diagnostics only |

Present-mask bits used: `timestamp` `1<<0`, `frame_counter` `1<<1`,
`validity_L` `1<<2`, `validity_R` `1<<3`, `gaze_point_2d_norm` `1<<6`. Pupil
diameters, eye origins, 3D rays, and track-box positions are ignored: reading
them would tie us to more of a moving layout for no selection benefit.

Everything else — calibration, display area, per-eye geometry — stays with
`tobiifreed` and its own tools. Wingmate consumes `gaze_point_2d_norm` as the
device's already-calibrated answer to "where on the display area".

## Devices

| Device | USB ID | Note |
| --- | --- | --- |
| Tobii Eye Tracker 5 | `2104:0313` | What upstream supports and tests |
| Tobii Eye Tracker 5, bootloader | `2104:0102` | DFU flashing only |
| TD-I13 integrated tracker | `2104:031e` | Our hardware; not yet supported upstream |

The I-13's tracker is a Tobii device (`2104`) with a product ID upstream does
not know about, and that has two consequences we have to handle before M3 can
be verified:

- `libusb_transport.zig` opens the device with a hardcoded
  `libusb_open_device_with_vid_pid(ctx, 0x2104, 0x0313)`, so a stock
  `tobiifreed` will not find `031e` at all. Getting `031e` into that lookup is
  an upstream contribution, and the friendliest form of it is a device list
  rather than a second constant.
- `assets/99-tobii.rules` grants uaccess to `0313` and `0102` only, so `031e`
  needs a matching rule or the daemon cannot claim it without root.

Whether the TTP framing and the `GazeSample` field layout are identical on this
tracker is unknown until it streams. Wingmate's decoder already fails closed on
a payload of another size, so a different layout surfaces as
`IncompatibleProtocol` rather than as wrong coordinates — but it would mean the
offsets in this document are per-device, not universal.

## Architecture

```text
tobiifreed ──unix socket──> gaze module (Rust)
                              │ validate frame, drop invalid samples
                              │ normalized [0,1]² -> window coordinates
                              │ hit-test the board grid
                              ▼
                            target enter / exit / lost
                              │ C API (wm_access_*)
                              ▼
                            AccessInputController (shared Kotlin)
                              │ dwell, re-arm delay, rest mode, debounce
                              ▼
                            Activate(target_id) -> existing Core::activate
```

Two rules keep this small:

- **Transport and geometry are platform code.** Sample decoding, coordinate
  mapping, and hit-testing live in Rust next to the `iced` widgets that own the
  layout. High-frequency coordinates never cross the C bridge.
- **Selection semantics stay shared.** Only semantic target transitions and
  clock ticks cross into `AccessInputController`, which already owns dwell,
  tremor re-arm, rest mode, and single activation and is unit-tested in
  `core/domain`. Desktop must not grow a second dwell implementation.

`AccessInputController` takes caller-supplied timestamps, so the bridge can pass
the sample clock and stay deterministic in tests.

## Milestones

Each milestone is independently reviewable and leaves the client working.

### M1 — Client transport

`desktopApp/rust/src/gaze/`: a `protocol` decoder that reassembles the daemon's
stream into samples, and a `client` that connects, subscribes, and reads them,
with bounded reconnect backoff for a daemon that is absent or restarts. A
status enum (`Disabled`, `Connecting`, `Connected`, `GazeLost`,
`IncompatibleProtocol`, `DaemonUnavailable`) names what the user is told.
Unknown message types are skipped by length; a gaze frame of unexpected length
or missing a required field stops decoding rather than guessing.

The `iced` subscription that drives this from the UI thread lands in M3
together with its consumer, so nothing runs in the background before something
uses it.

Tests: partial and coalesced reads, skipped message types, a gaze payload of
another size, `present_mask` missing a required field, an undetected eye,
absent and out-of-range coordinates, oversized payloads, and backoff growth
and reset.

### M2 — Shared controller on the C bridge

Add `wm_access_*` entry points (`target_entered`, `target_exited`,
`clear_transient_input`, `tick`, `set_paused`) that wrap the existing
`AccessInputController` and return the state plus any effect as JSON, matching
the JSON convention the rest of `DesktopCore` already uses. Sync `dwellMillis`
and the re-arm delay from shared `Settings`.

This also unblocks desktop dwell for mouse and OS-pointer users, which the
matrix currently records as a gap.

### M3 — Hit-testing and activation

Map `gaze_point_2d_norm` to the board grid and feed enter/exit into the bridge;
run `tick` on a frame timer; execute `Activate` through the existing activation
path so speak, insert, and navigate behave exactly as pointer hover does.
Fullscreen communication mode only — Wayland does not reliably report a normal
window's position on the display, so a windowed mapping would be guesswork.
Emphasis marks the resolved target; no cursor follows gaze.

Invalid samples (either eye `validity != 0`, or `gaze_point_2d_norm` absent or
outside `[0,1]²`) clear the current target after a short grace period, cancel
pending dwell, and require a fresh dwell on reacquisition.

Tests: synthetic samples over a fixture grid, boundary and span cells,
gaze-loss cancellation, rest mode suppressing activation.

### M4 — Settings, status, and setup

A gaze section in desktop Settings, shown when there is something to say.
Detection uses two separate signals, because they call for different help:

- **Tracker present**: a Tobii product ID from the table above appears under
  `/sys/bus/usb/devices/*/{idVendor,idProduct}`. Reading sysfs needs no
  permissions and no USB dependency.
- **Daemon reachable**: `gaze.sock` connects and streams.

Controls: enable/disable (off by default), dwell duration, an opt-in
diagnostics view, and a checkbox to start `tobiifreed` with Wingmate. Auto-start
uses a systemd **user** service, so it needs no root and leaves the daemon
shared with the overlay and calibration tools.

Tracker present but no daemon is the state worth designing for: name what is
missing and show the exact commands, rather than hiding the section or
presenting a dead control.

**Wingmate does not install the daemon.** It is a packaging problem, not a
runtime one: ship `tobiifreed` alongside the desktop client, or document the
Nix flake upstream provides. Downloading and installing it at runtime is a
non-goal, because upstream publishes no binaries (it builds from source with
Zig 0.14+/Nix), USB access needs a root udev step, some units need firmware
extracted from Tobii's Windows driver and DFU-flashed, and an unreviewed
background update would sit in the path of the user's only voice.

Docs to update: `HEAD_EYE_TRACKING.md` (setup and troubleshooting), the
accessibility matrix, `docs/PLATFORM_SUPPORT.md`, and `PRIVACY_POLICY.md`.

Interaction quality — hysteresis, magnetism, calibration validation — is
deliberately deferred to [#158](https://github.com/Jdreioe/Wingmate/issues/158);
M3 only has to be safe, not forgiving.

### M5 — Calibration from inside Wingmate

The daemon forwards calibration commands to the tracker (`0x20` start, `0x21`
add point, `0x22` finish, `0x23` apply) and returns them as `0x02` responses,
so Wingmate can run calibration itself: fullscreen targets, gaze sampled at
each, then apply. The alternative is sending an AAC user to a browser demo to
fix their own input, which is reason enough to own the flow.

Two costs: response payloads are undocumented and have to be read out of the
upstream TypeScript SDK, and the command IDs in `daemon_protocol.zig` already
disagree with upstream's `ARCHITECTURE.md` (`0x23` is `cal_apply` in the code
and `cal_retrieve` in the document). Responses get the same fail-closed
treatment as gaze frames.

Sized like M3, and independent of it: calibration is useful the moment gaze
streams at all.

## Windows

Windows gets the same feature through a different route, and a cheaper one.

The tracker has a vendor driver there, and on a TD-I13 that is TD Control's
gaze interaction. Using `tobiifree` on Windows would mean replacing that driver
with WinUSB to reach libusb, breaking TD Control and its calibration on the
device someone speaks with. It is also a Linux-shaped daemon: Unix socket,
udev, Zig. So Windows consumes the **OS pointer** that Windows Eye Control or
TD Control already drives, and the vendor stack keeps calibration.

The gap is that the desktop client has no dwell runner, so a gaze-driven OS
pointer can move over Wingmate without being able to select anything. **M2
closes that**, and M2 is platform-independent: it is not merely plumbing for
Linux, it is the whole of Windows gaze support. Worth scheduling accordingly.

| | Linux (TD-I13) | Windows |
| --- | --- | --- |
| Transport | M1/M3 through `tobiifreed` | none — OS pointer |
| Dwell, rest mode, activation | M2 | M2 |
| Calibration | M5, Wingmate drives the daemon | TD Control or Eye Control owns it |
| Settings section | daemon status, auto-start, calibrate | dwell controls, and a pointer to the OS setup |

M1, M3, and M5 stay behind `cfg(unix)`; Windows needs no equivalent.

A native Windows gaze provider — Tobii's Stream Engine or Interaction Library,
for semantic targets and gaze-loss safety instead of a bare pointer — is
deferred. It is proprietary, `wingmate-desktop` is GPL-3.0-or-later, so linking
it is a licensing question for the copyright holder before it is a technical
one. #127 already asks for that decision to be recorded with evidence that the
OS pointer is insufficient.

The capability difference is real and belongs in the accessibility matrix
rather than being papered over: on an OS pointer, Wingmate hit-tests pixels
under a moving cursor and cannot distinguish "looked away" from "stopped
moving", so the gaze-loss and hysteresis work in #158 can only be partial
there.

## Privacy

Gaze samples are sensitive operational data and stay in memory. Nothing logs
coordinates, pupil or eye measurements, validity histories, frame streams, or
the label of a gazed target. Diagnostics may show live values on screen; they
must not write them to disk or into error messages. Status logging is limited to
connection state and rejection reasons.

## Risks and open questions

- **Device support.** The I-13's tracker is `2104:031e`, which upstream neither
  opens nor grants uaccess to (see [Devices](#devices)). Until a patched
  `tobiifreed` claims it, M3 and M5 cannot be verified on real hardware; M1, M2,
  and the Windows path do not depend on it. This is why #129 is a vertical
  slice and #126 does not generalise a provider interface yet.
- **Protocol drift.** The layout already changed once. Failing closed on any
  unexpected length turns drift into a clear status message instead of garbage
  coordinates driving selections.
- **Coordinate space.** `gaze_point_2d_norm` is normalized to the daemon's
  configured display area. Multi-monitor and mismatched display-area
  configuration are not handled in this slice; the daemon owns that setup.
- **Timestamps.** `timestamp_us` is a device clock, not wall time. Use it only
  for elapsed-time arithmetic.

## Verification

Deterministic tests come first, then hardware:

1. Rust unit tests for framing, decoding, and hit-testing with synthetic samples.
2. Existing `core/domain` `AccessInputController` tests cover dwell and rest
   mode; add bridge-level tests only for the JSON boundary.
3. On the real TD-I13, with a `tobiifreed` that opens `2104:031e`: select
   phrase and board targets, confirm gaze loss cannot complete an activation,
   confirm rest mode suppresses gaze, kill and restart the daemon while
   Wingmate runs, and confirm touch, mouse, keyboard, and switch input still
   work with gaze disabled.
4. On Windows with TD Control or Eye Control driving the pointer: dwell, rest
   mode, and the select key behave as they do with a mouse.

Record the hardware result in #129 before closing it.
