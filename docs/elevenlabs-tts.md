# ElevenLabs voices

Android and iOS support an ElevenLabs account API key. Impact Program members
can use their own account allowance and voices through this source. Wingmate
does not distribute a shared key or proxy speech through its own server.

In Welcome or Settings → Speech, choose ElevenLabs and open setup. Create a key
with Voices read and Text to Speech access in your ElevenLabs account, paste it,
and choose **Save securely and verify**. Wingmate loads the account voice catalog
before switching engines. Failed verification restores the previous key and
engine. Voice discovery does not spend synthesis credits or verify the key's
Text to Speech permission, so that permission must also be enabled.

Choose a voice in the voice picker, then select your language. Account voices
include personal clones and saved library voices. Add or manage those voices in
ElevenLabs, then refresh Wingmate's picker.

Speech uses `eleven_v4_turbo` and MP3 audio. Write audio tags in square brackets
to direct delivery, such as `[whispers]`, `[laughs]`, or `[sarcastic]`; Wingmate
sends them unchanged. The Typing Screen's Action strip has buttons for common
emotion tags; use Reset Typing Screen to add them to an existing Typing Screen.
Other voice sources, including system speech fallback, skip tags. Wingmate sends speed
0.7–1.2, but v4 may ignore it; use tags such as `[slowly]` instead. ElevenLabs
does not expose pitch adjustment through this API. v4 ignores SSML breaks, so
explicit pauses such as `<1s>` become `[pause]`, and pauses of 1.5 seconds or
more become `[long pause]`. A request uses the selected voice language;
per-segment language overrides and Wingmate's Azure pronunciation dictionary are
not applied. v4 supports Danish and many other languages. Its normalization of
numbers, dates, and currency differs from other providers.

Keys are stored using Android Keystore or this-device-only iOS Keychain items,
separately from Azure and Google credentials. The setup screen can replace or
remove the key. Keys are excluded from Wingmate backups and diagnostics. Requests
use `xi-api-key` headers at `https://api.elevenlabs.io` with redirects disabled.
Provider error bodies are never included in user-visible errors or diagnostics.

Text is sent to ElevenLabs when speech or vocabulary precaching uses this source.
The existing local audio cache avoids repeat requests. Existing system speech
fallback remains available when cloud speech fails. Account quotas and provider
retention policies apply; Wingmate does not request enterprise-only zero retention.
Removing a key does not delete cached audio, History, or ElevenLabs account data.

Desktop has no cloud speech adapter; this source is available on Android and iOS.

References: [account voices](https://elevenlabs.io/docs/api-reference/voices/search),
[synthesis API](https://elevenlabs.io/docs/api-reference/text-to-speech/convert),
[models and languages](https://elevenlabs.io/docs/overview/models),
[audio tags](https://elevenlabs.io/blog/elevenlabs-audio-tags-list).
