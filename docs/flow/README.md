# Flow

## Product definition

Flow is a separate desktop reader developed in the Wingmate monorepo. A person
selects text in another application, presses a global hotkey, and Flow reads
the captured text aloud.

Flow is for people with reading needs broadly. It is not an AAC board, document
library, browser, PDF reader, or EPUB reader. It reads text exposed by the
application already displaying the document.

The v1 promise is:

> Select text in a supported desktop application and hear it with one hotkey.

"Any app" is an aspiration, not a support guarantee. An application must make
its selected text available through the platform accessibility system or another
supported capture method.

## Interaction

1. The person selects text in a desktop application.
2. They press Flow's configured global hotkey.
3. Flow captures the selection before showing its popup. The popup must not
   steal focus before capture completes.
4. Flow shows a transient, non-activating popup with the captured text and
   playback controls. It says "Preparing playback" while the voice starts.
5. The selected voice reads the text. The popup can pause, resume, or stop it.

If the hotkey is pressed while Flow is already reading:

- the same captured selection pauses or resumes playback; this behavior is
  configurable;
- a different selection replaces the active reading immediately.

Flow compares captured text after its normal text cleanup so harmless changes
in whitespace do not turn a pause/resume action into a replacement.

## Scope and limits

Flow reads text from documents already open in other applications, including
PDF readers, EPUB readers, browsers, and messaging apps when they expose
selectable text. It does not open or parse those file formats itself.

Each reading is limited to ten minutes. This protects against accidental very
large selections and gives Azure speech a firm upper bound. Flow should show a
clear choice when a selection exceeds the limit rather than silently truncating
it.

The popup is transient. Flow does not keep a reading history, saved passages,
or resumable document position after dismissal.

## Platforms and selection capture

Flow targets macOS, Windows, and Linux, including Wayland.

| Platform | Hotkey | Preferred selected-text source | Constraint |
| --- | --- | --- | --- |
| macOS | Native global hotkey | Accessibility API focused element | Requires Accessibility permission and only works when the source app exposes selected text. |
| Windows | `RegisterHotKey` | UI Automation focused text element | The source control must support a text selection pattern. A hotkey collision must be reported in settings. |
| Linux, Wayland | XDG Global Shortcuts portal | To be decided during platform work | A Wayland global-shortcut grant does not itself give Flow access to another application's selected text. Support must state the desktop environments and capture paths that work. |
| Linux, X11 | Native global hotkey | To be decided during platform work | X11 and Wayland must be tested and documented separately. |

Flow must never simulate Cmd+C or Ctrl+C, replace clipboard contents, or read
the clipboard without an explicit user-selected fallback. The fallback policy is
an open product decision.

Relevant platform documentation:

- [macOS accessibility selected text](https://developer.apple.com/documentation/appkit/nsaccessibility-c.protocol/accessibilityselectedtext)
- [Windows UI Automation selection](https://learn.microsoft.com/en-us/dotnet/api/system.windows.automation.textpattern.getselection?view=windowsdesktop-10.0)
- [Windows global hotkeys](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-registerhotkey)
- [XDG Global Shortcuts portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.GlobalShortcuts.html)

## Speech, audio, and privacy

Flow supports two voice sources:

- **System voices** are the default. They work without an account, setup, or
  network request.
- **Azure voices** are an opt-in source for people who want a particular neural
  voice. The macOS app accepts a person-owned Azure Speech endpoint or region
  and subscription key, stored in the Mac Keychain. It uses Azure's
  [Text to Speech REST API](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-text-to-speech).

Flow's intended default is to duck other audio while it reads. Flow controls
only its own playback. It must not stop or control another application.

macOS v1 uses the system speech synthesizer and leaves other applications'
audio unchanged. It mixes rather than pretending to control another process's
volume. A real ducking implementation needs a macOS-specific audio policy and
is not part of this first app target.

Selected text is ephemeral. Flow must not log it, store it, cache synthesized
audio for it, add it to history, or include it in diagnostics. When Azure is
selected, Flow sends the selected text to Azure solely to synthesize speech.
The Azure settings screen must explain this before the source is enabled.

Flow owns its Azure consent and secure credentials. It must not silently reuse
Wingmate's Azure configuration or credentials, even if both products later
share small authentication code.

## Language Flow

Flow identifies each selected sentence locally and can switch between the
person's enabled system-language voices. Azure keeps one selected voice for the
whole reading, but receives Flow's detected language tag for each sentence.
The detailed decision and open questions are in
[ADR 0001: Language Flow](adr/0001-language-flow.md). Defined terms are in the
[Flow glossary](glossary.md).

## Settings

Flow's settings should be visually familiar to Wingmate's settings, while using
its own settings model and storage. The initial settings are:

- global hotkey;
- voice source, system or Azure;
- available voice and speech rate;
- volume;
- audio behavior, including ducking;
- maximum reading length, capped at ten minutes;
- popup auto-dismiss delay;
- same-selection hotkey action, pause/resume or restart.

Settings must be accessible through keyboard, screen reader, and large reliable
targets. A missing permission, unavailable voice, Azure configuration problem,
or hotkey conflict must show a concrete recovery action.

## Architecture

Flow belongs in this monorepo but is not a Wingmate feature. It can share build
tooling, Kotlin, coroutines, test conventions, and a future small Azure
authentication library. It must not depend on Wingmate's `SpeechService`, voice
models, Azure configuration, speech cache, history, pronunciation dictionaries,
or communication features.

The shared Flow module owns the playback state machine, hotkey behavior,
ten-minute guard, and privacy rules. It talks to a small Flow-owned interface:

```kotlin
interface FlowSpeechEngine {
    suspend fun read(text: String, settings: FlowSpeechSettings)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
}
```

Platform adapters implement that interface for system voices and Azure voices.
They also own global hotkeys, selected-text capture, audio ducking, permission
prompts, and native popup behavior. The shared controller has no access to the
selected text after playback completes.

This seam has two real implementations, system and Azure speech, and three
platform integrations. It keeps platform-specific work out of the reading
workflow without making Flow inherit Wingmate's product assumptions.

## Open decisions

- Define the explicit fallback when selected text is unavailable. Do not make
  clipboard use implicit.
- Specify the Wayland desktop environments and selected-text capture paths that
  Flow will support first.
- Define the exact audio choices available beyond the default ducking behavior.
- Decide whether Flow identifies the source application in the popup without
  retaining it after playback.
