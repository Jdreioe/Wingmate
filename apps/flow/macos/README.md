# Flow for macOS

Flow is a native macOS menu-bar reader. Select text in an application, then
press Flow's global hotkey to hear it. System voices are the default; Azure
neural voices are available with the person's own Azure Speech resource.

## Run it

Open [Flow.xcodeproj](Flow.xcodeproj) in Xcode. Under **Signing &
Capabilities**, select an Apple development team for Flow, then run the `Flow`
scheme. macOS ties Accessibility permission to the signed application identity,
so an unsigned or ad-hoc build is not a reliable way to test selected-text
capture.

After choosing a team, a command-line build can use that same identity:

```sh
xcodebuild -project apps/flow/macos/Flow.xcodeproj \
  -scheme Flow \
  -configuration Debug \
  -derivedDataPath /tmp/flow-derived-data \
  build
```

The built application is at
`/tmp/flow-derived-data/Build/Products/Debug/Flow.app`.

## First use

1. Open Flow from the build product or Xcode.
2. Open Flow's menu-bar item and choose **Settings**.
3. Choose **Allow Accessibility access**, then enable Flow in macOS Privacy &
   Security > Accessibility.
4. Select text in an application that exposes its selection to macOS
   accessibility, then press Option-Command-R.

Flow ships as an agent-style menu-bar app. It has no Dock icon while running.
The default hotkey and the two alternatives are configurable in Settings.

## Azure Speech setup

Azure is optional. In **Settings > Speech**, choose **Azure Speech**, then add
either your Azure Speech region (for example `westeurope`) or its HTTPS speech
endpoint and its subscription key. Flow stores that key in this Mac's Keychain,
separately from Wingmate. It never reads or reuses Wingmate's configuration.

When Azure is selected, the text you ask Flow to read is sent to your Azure
Speech resource for synthesis. System voices remain on-device. Use **Play test
voice** in Settings to confirm an Azure configuration without capturing text
from another application.

Azure requests use the standard [Text to Speech REST API](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-text-to-speech).

## v1 behavior

- Captures selected text through macOS Accessibility before showing its popup.
- Uses a non-activating playback popup so the source application keeps focus.
- Reads with selectable system voices through `AVSpeechSynthesizer`, or an
  opt-in Azure neural voice through Azure's Text to Speech REST API.
- Keeps captured text in memory only while the popup is visible.
- Limits selections to roughly ten minutes of speech.
- Mixes with other applications' audio. macOS audio ducking is explicitly not
  implemented in v1.
- Does not include clipboard fallback, document parsing, history, or saved
  passages.
