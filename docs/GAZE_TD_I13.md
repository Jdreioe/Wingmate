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
not know about. It can be made to work, so this is a packaging prerequisite
rather than an open question — but it is one Wingmate has to carry, because a
daemon installed straight from upstream will not see the device:

- `libusb_transport.zig` opens the device with a hardcoded
  `libusb_open_device_with_vid_pid(ctx, 0x2104, 0x0313)`, so a stock
  `tobiifreed` will not find `031e` at all. Getting `031e` into that lookup is
  an upstream contribution, and the friendliest form of it is a device list
  rather than a second constant.
- `assets/99-tobii.rules` grants uaccess to `0313` and `0102` only, so `031e`
  needs a matching rule or the daemon cannot claim it without root.

M4 therefore ships or documents a `tobiifreed` that knows `031e`, and the
setup instructions carry the matching udev rule. Getting the device ID upstream
removes that maintenance.

The remaining unknown is whether the TTP framing and the `GazeSample` layout are
identical on this tracker. Wingmate's decoder fails closed on a payload of
another size, so a different layout surfaces as `IncompatibleProtocol` rather
than as wrong coordinates — but it would mean the offsets in this document are
per-device, not universal. One streaming session answers it.

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

`AccessInputController` takes caller-supplied timestamps. The desktop runner
uses one host monotonic millisecond clock for gaze, pointer, and key input.
Device timestamps validate sample ordering; mixing their clock with pointer
and timer timestamps would corrupt dwell elapsed time.

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

`wingmate-desktop --gaze-probe` connects for ten seconds and prints payload
sizes, per-eye validity, and positions, so the pinned layout can be confirmed
on a tracker before hit-testing depends on it. A payload of another size is
reported as the per-device layout problem it is. Output goes to the terminal
and nowhere else.

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

M2 implementation now connects this bridge to desktop pointer hover for Screen
Buttons and Back/Clear/Hold/Speak controls. Settings > Access exposes dwell,
re-arm delay, and Select/Rest shortcuts. The runner uses one local monotonic
clock for transitions and ticks, shows target emphasis and progress, and clears
transient input on focus loss or leaving communication. Library and editing
controls remain outside this increment. Rest/Resume is click/touch reachable;
key users can toggle Rest or hold Select for two seconds and release to resume.
Rust integration tests exercise the actual C ABI, including settings persistence,
cancellation, single activation, and pause effects. Hardware verification remains
outstanding.

### M3 — Hit-testing and activation

Map `gaze_point_2d_norm` to the board grid and feed enter/exit into the bridge;
run `tick` on a frame timer; execute `Activate` through the existing activation
path so speak, insert, and navigate behave exactly as pointer hover does.
Fullscreen communication mode only — Wayland does not reliably report a normal
window's position on the display, so a windowed mapping would be guesswork.
Emphasis marks the resolved target; no cursor follows gaze.

M3 is implemented in the Linux desktop runner. Each communication control has
a semantic widget ID; an iced widget operation hit-tests its actual layout
bounds in logical window coordinates. Grid layout handles row and column spans.
Gaps, empty cells, and overlapping target bounds do not select a target.

The async Unix-socket reader runs only during an explicitly enabled session.
A latest-value mailbox avoids queuing raw frames on the UI thread and retains
a loss counter even if tracking recovers between UI updates. Device frame
counters and timestamps reject duplicate or reordered samples; host monotonic
time drives dwell and a 100 ms freshness limit. Disconnects reconnect with the
M1 backoff, and dropping the subscription closes the socket.

Invalid samples immediately clear the target and cancel dwell. Silence expires
the target after 100 ms. Reacquisition starts fresh; visual grace and forgiving
boundaries remain #158. Pending widget results are rejected after navigation,
resize, pause changes, or tracking loss. Only fresh resolved targets advance
the shared controller. Pointer hover cannot replace a live gaze target;
pointer dwell remains usable while the daemon is unavailable or incompatible.

The runner's session-only Start/Stop gaze control makes M3 testable before M4.
It requires a nonzero dwell duration and fullscreen mode. Leaving communication,
losing focus, or leaving fullscreen stops the session. Rest and resume use the
existing click/touch controls or keyboard shortcuts. Library and editor controls
are excluded. The daemon must already be calibrated to the display showing
Wingmate; multiple displays and mismatched display areas remain unsupported.

Verification: synthetic span/edge/gap hit-testing; real Unix-socket framing,
reconnection and cancellation; shared-controller activation, gaze loss, Rest,
and pointer-fallback integration tests. An isolated desktop session with a fake
daemon selected a two-row spanning Button once and cleared it on invalid-eye
samples. The real TD-I13 checklist remains outstanding.

### M4 — Settings, status, and setup (implemented; hardware verification pending)

A gaze section in desktop Settings, shown when there is something to say.
Detection uses two separate signals, because they call for different help:

- **Tracker present**: a Tobii product ID from the table above appears under
  `/sys/bus/usb/devices/*/{idVendor,idProduct}`. Reading sysfs needs no
  permissions and no USB dependency.
- **Daemon reachable**: `gaze.sock` connects and streams.

Controls: enable/disable (off by default), dwell duration, an opt-in
diagnostics view, and a checkbox to start `tobiifreed` with Wingmate.

Tracker present but no daemon is the state worth designing for: name what is
missing and show the exact command, rather than hiding the section or
presenting a dead control.

#### Packaging on Linux

The Linux client ships as an AppImage, which decides most of this.

**The daemon is bundled, not downloaded.** A `tobiifreed` that knows `031e`
travels inside the AppImage. Downloading and installing one at runtime stays a
non-goal: upstream publishes no binaries, and an unreviewed background update
has no business in the path of the user's only voice. Bundling also pins the
daemon and the decoder to each other, so the sample layout this build expects
cannot change behind our back — a version skew becomes a build decision instead
of a field failure.

Licensing works out: `tobiifree` is GPL-3.0 and `wingmate-desktop` is
GPL-3.0-or-later, so the two can ship in one image. The AppImage carries the
licence text and the complete corresponding patched source itself, with build
instructions; releases also publish that source archive.

**Auto-start spawns the bundled daemon as a child process**, not a systemd user
service: an AppImage has no install step, and a unit file pointing into its
ephemeral mount path would break on the next run. The rule is
socket-before-spawn — if `gaze.sock` already answers, a daemon is running and
Wingmate connects to it instead. `tobiifreed` claims the tracker over USB, so
two of them must never race for the device.

**The udev rule is installed by the optional Linux installer.**
`scripts/install-wingmate.sh --setup-gaze` installs the AppImage for the current
user and uses `sudo` only to install and reload the host USB rule. The AppImage
itself runs without root. The rule grants the active local desktop user access
to runtime devices `2104:031e` and `2104:0313`; users reconnect the tracker after
setup. Firmware/bootloader access is excluded. Distro packages that can install
the rule themselves should. Daemon bundling and opt-in child startup are implemented. Selection and
diagnostics remain session-only and off by default; only the startup preference
persists locally. The daemon is pinned to
`d303e47fa1a6cac452eedd157d3efb0dd08e3732` with the runtime USB-ID patch in
`scripts/tobiifree/td-i13.patch`.

Verification: 38 Rust tests pass, including USB discovery, socket reuse,
concurrent-start exclusion, owned-child cleanup and diagnostics cancellation.
The pinned daemon builds with Zig 0.15.2. A local debug AppImage was assembled
and its daemon, x86-64 libusb and corresponding-source files were extracted and
checked. This host required invoking appimagetool with a separately supplied
runtime file; the normal CI packaging path and real TD-I13 remain unverified.

Firmware is out of scope entirely: some units need it extracted from Tobii's
Windows driver and DFU-flashed, which Wingmate must never automate.

Docs to update: `HEAD_EYE_TRACKING.md` (setup and troubleshooting), the
accessibility matrix, `docs/PLATFORM_SUPPORT.md`, and `PRIVACY_POLICY.md`.

Interaction quality — hysteresis, magnetism, calibration validation — is
deliberately deferred to [#158](https://github.com/Jdreioe/Wingmate/issues/158);
M3 only has to be safe, not forgiving.

### M5 — Calibration from inside Wingmate

Tracked in [#273](https://github.com/Jdreioe/Wingmate/issues/273).

The daemon exposes `0x20` start, `0x21` add point, `0x22` finish, and `0x23`
apply. **Finish already computes and applies the calibration.** It is not a
preview operation followed by a separate commit. M5 needs daemon changes
before Wingmate can promise that failed or abandoned calibration preserves
the previous calibration, as required by #273.

#### Protocol inspection, 2026-09-07

Checked the source archive packaged with Wingmate, pinned to
`d303e47fa1a6cac452eedd157d3efb0dd08e3732`, including the TD-I13 patch.
The relevant upstream sources at that revision are:

- [Daemon command dispatch](https://github.com/Aetherall/tobiifree/blob/d303e47fa1a6cac452eedd157d3efb0dd08e3732/applications/tobiifreed/src/main.zig):
  start and apply return a response containing only the command byte on
  success. Finish returns the command byte followed by the retrieved payload.
  State-machine failure sends a separate error message with code `0x01`.
  Add-point responses forward the raw tracker response without decoding quality.
- [Tracker state machines](https://github.com/Aetherall/tobiifree/blob/d303e47fa1a6cac452eedd157d3efb0dd08e3732/driver/src/tobiifree_core.zig):
  `calFinishPollInner` executes compute-and-apply (`0x42F`), retrieve (`0x44C`),
  then close-realm. A failure after compute can therefore occur after the active
  calibration has changed. The finish response hook advances on receipt without
  validating the tracker response's success status.
- [Socket command enum](https://github.com/Aetherall/tobiifree/blob/d303e47fa1a6cac452eedd157d3efb0dd08e3732/driver/src/daemon_protocol.zig):
  no independent retrieve, abort, or rollback command is exposed. Lower-level
  retrieve and close-realm requests exist in the core, but are not reachable
  through this socket protocol.
- [Socket cleanup](https://github.com/Aetherall/tobiifree/blob/d303e47fa1a6cac452eedd157d3efb0dd08e3732/applications/tobiifreed/src/server.zig):
  disconnecting a client closes its socket without calibration cleanup.
- [TypeScript client](https://github.com/Aetherall/tobiifree/blob/d303e47fa1a6cac452eedd157d3efb0dd08e3732/sdk/src/ws_source.ts):
  `addCalibrationPoint` awaits the response but discards its contents. It does
  not supply a per-point quality decoder. The SDK alone cannot establish the
  quality or rejection semantics required by #273.

The command enum remains authoritative over upstream `ARCHITECTURE.md`, which
assigns different meanings to `0x22` and `0x23`. Do not decode an opaque response
as success or label ordinary gaze validity as calibration quality.

#### Implementation prerequisites

1. Establish tracker response status, point-quality, and calibration-blob
   decoding from protocol evidence. Verify retrieve/apply round-tripping on the
   TD-I13 without logging or persisting calibration blobs or eye measurements.
2. Add a daemon-owned calibration session with exclusive ownership, an in-memory
   copy of the previous calibration, explicit cancellation, and cleanup on client
   loss. Validate responses before advancing. If restoration cannot complete
   after tracker loss, report it explicitly and keep gaze selection disabled;
   never claim the previous calibration is intact without confirmation.
3. Expose the session's validated outcomes through a versioned or explicitly
   negotiated socket capability. Old daemons must leave calibration unavailable.
   Exercise failure before and after compute, cancellation, malformed responses,
   and client/tracker loss with synthetic transport tests.
4. Build the fullscreen Wingmate flow on that boundary: paced targets,
   per-point feedback and retry, cancellation, and no communication activation
   during calibration. Finish with the real-device checks in #273.

No supported Tobii USB device (`2104:0313` or `2104:031e`) was attached to the
development host during this inspection. Real-device response and restoration
verification remain outstanding. The in-app TD-I13 calibration flow is not
implemented; webcam calibration is a separate provider and does not complete M5.

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
  opens nor grants uaccess to (see [Devices](#devices)). It can be made to work,
  so the cost is packaging and a udev rule rather than feasibility. The open
  question is the `GazeSample` layout on this tracker, which one streaming
  session settles. This is why #129 is a vertical slice and #126 does not
  generalise a provider interface yet.
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
