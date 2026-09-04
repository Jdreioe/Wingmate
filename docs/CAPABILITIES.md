# Wingmate capabilities

This document describes capabilities present in the current Wingmate worktree.
It is not a roadmap. Planned work belongs in GitHub issues.

## Typing

Typing supports free-text Messages, saved Phrases, and flat Categories. A Phrase
may have a separate vocalization, symbol or photo, color, personal recording,
and hidden state. Communicators can reorder Phrases and Categories, hold a
Message while composing another, review speech History, and copy or share text
and generated audio where the platform provides a share action.

Android and iOS contain local word prediction support, but it is currently
restricted to debug builds and is not a released capability.

## Screens

A Screen is a named set of linked Pages. Wingmate can create, duplicate, rename,
lock, edit, import, export, and delete Screens. It reads OBF and OBZ and exports
Screens as OBZ.

Pages support grid resizing, Buttons spanning several Cells, linked navigation,
hidden Buttons, symbols and personal images, recordings, separate
vocalizations, colors, shapes, language overrides, and optional Fitzgerald
word-type colors. Button behavior can speak, add to the Message, or do both.
Navigation can stay on the current Page, return to the previous Page, or return
to the starting Page. Special Buttons support insertion, spacing, backspace,
clear, speak, home, Typing navigation, and prediction.

App, Screen, and Page settings control labels, symbols, label placement, the
Message bar, activation behavior, return behavior, and Page background color.
Blank, calculator, and keyboard templates exist, although the choices currently
differ by platform. Quick Core import exists internally but is not offered by
the normal Screen creation flow.

## Speech and language

Wingmate supports operating-system voices and user-configured Azure Neural
Voices. Communicators can select a voice, primary language, optional secondary
language, and speech rate; use a pronunciation dictionary; and choose immediate
speech or silent composition until the complete Message is activated.

Generated speech may be cached locally. Azure synthesis requires a network for
uncached text. System text-to-speech provides the offline path supported by the
operating system. A network failure must not discard the active Message.

## Access

Communication targets support touch, mouse, trackpad, keyboard focus, adaptive
switches, and operating-system pointer devices. Wingmate provides configurable
hold and dwell activation, repeated-selection suppression, timed highlighting,
an optional selection sound, auditory exploration, a Select key, Rest mode, and
switch scanning where the native client exposes it.

Operating-system eye and head tracking work as pointer input. Wingmate does not
open a camera, collect gaze data, or provide gaze calibration. See
[Pointer input and Rest mode](HEAD_EYE_TRACKING.md).

Editing access can protect vocabulary changes while leaving communication
available. Individual Screens can also be frozen against editing. Coverage and
unlock behavior currently differ between clients and are part of the active
stability work.

## Data and privacy

Communication data stays on the device unless a selected speech or symbol
service requires a network request. Speech History visibility, speech caching,
local usage logging, and anonymous feature reporting are separate choices.
Android feature reporting is optional and disabled by default; it excludes
communication content and credentials.

Wingmate can create and restore an unencrypted `.wingmate-backup` containing
Screens, Pages, Phrases, Categories, referenced media, settings, History, voice
selection, and pronunciation entries. It excludes speech-service credentials,
Editing access verifiers, analytics identifiers, derived downloads, and caches.
See [the backup format](BACKUP_FORMAT.md) and
[credential boundaries](SECRET_BOUNDARIES.md).

OBF and OBZ provide explicit Screens vocabulary transfer. Wingmate does not
provide account-based or automatic cross-device synchronization.

## Native clients

| Client | Native UI | Notable platform-specific behavior |
| --- | --- | --- |
| Android | Jetpack Compose | Optional Aptabase reporting, bulk ARASAAC symbol download, native sharing, and external or supported rear displays. |
| iPhone and iPad | SwiftUI | Native Photos and recording flows, VoiceOver, configurable scanning areas, and OS Eye Tracking, Head Tracking, Switch Control, and Voice Control. Hardware secondary display is not exposed. |
| Desktop (in development) | Rust with `iced` | Single native binary with no JVM; system TTS only. Not a supported client — see below. |

See [supported platforms](PLATFORM_SUPPORT.md).

### Desktop, in development

The desktop client in `desktopApp/` runs the shared Kotlin core as a
Kotlin/Native static library. Today it imports OBF and OBZ files, reopens recent
files, navigates linked Pages, composes and speaks a Message through the
operating system's own voice, holds a Message, edits the pronunciation
dictionary and a small set of settings, and creates or restores a version-1
backup. It has no Typing workspace, no vocabulary editing, no cloud voices, and
none of the access features listed in the
[accessibility matrix](ACCESSIBILITY_MATRIX.md). Track it in
[#268](https://github.com/Jdreioe/Wingmate/issues/268).

## Current parity limits

The clients do not yet preserve and transfer one active Message consistently
when switching between Typing and Screens. Editing access coverage, Screen
unlock behavior, visible templates, and prediction availability also differ.
These are known gaps, not intentional parity exceptions.
