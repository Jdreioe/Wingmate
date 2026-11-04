# Piper offline voices

Android can speak with downloaded [Piper](https://github.com/OHF-Voice/piper1-gpl)
voices. They run on the device through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
(ONNX Runtime and the espeak-ng phonemizer), so speech works without a network
once a voice is downloaded.

In Settings → Speech, choose **Piper (offline)**, then **Choose an offline voice**.
The list opens on voices for the primary language. Each voice shows its size.
The Play button on each voice streams Piper's recorded sample of it, so you can
hear a voice before you download it. Downloading the first voice also downloads 9 MB of shared espeak-ng data. Tap
**Use** on a downloaded voice to select it.

## Downloads

Voices are stored in app-private storage (`files/piper`), so uninstalling
Wingmate removes them. Every file is pinned by size and SHA-256 in
`PiperVoiceCatalog.kt`:

- A download continues in the background while the app is open. If the
  connection drops or the app closes, the kept bytes resume with an HTTP Range
  request when you tap **Resume**.
- **Cancel** stops a download and discards its partial files.
- A file that fails its checksum is deleted and reported as damaged.
- Files are checked again before a voice is loaded, so a damaged model never
  reaches the native engine.
- Deleting the last voice also removes the shared espeak-ng data.

## Speech

Piper reads plain text in the voice's own language. It has no pitch control and
does not apply per-segment language overrides or the Azure pronunciation
dictionary. Pauses become silence. Speech rate is clamped to 0.5–2×. Multi-speaker
voices use their first speaker. Generated audio is cached as WAV under the same
rules as cloud speech; board speech preloading skips Piper, because synthesis is local.

If the selected Piper voice is missing, damaged, or fails, the Communication
session speaks with the device voice and shows the usual fallback notice.

## Catalog

`scripts/piper/generate_catalog.py` regenerates the catalog from Piper's
`voices.json`, pinned to sherpa-onnx's converted models on Hugging Face
(`csukuangfj/vits-piper-<key>`) at a fixed revision. Voices without a converted
model or without a named dataset licence are left out.

```sh
python3 -I scripts/piper/generate_catalog.py
```

Each voice's dataset licence is kept in the catalog for attribution but is not
shown in the app. Some datasets are licensed for non-commercial use only (for
example CC BY-NC-SA); Wingmate does not filter by licence. Sample clips come from
`rhasspy/piper-voices` at a pinned revision.

The engine is the official sherpa-onnx Android AAR from JitPack, which is
byte-identical to the GitHub release. It stays on 1.13.x because sherpa-onnx 2.0
removed the bundled espeak-ng phonemizer that Piper voices need.

## Platforms

- **Android:** supported.
- **iOS:** not yet available. This is a documented platform exception. The shared
  catalog and installer are in common Kotlin, but iOS needs the sherpa-onnx
  xcframework and a Swift voice page. Selecting Piper is not offered on iOS. A
  Piper engine setting falls back to the device voice.
- **Desktop:** not yet available.

## Size

sherpa-onnx adds ONNX Runtime and its JNI library per ABI. Play delivers one ABI
per device from the app bundle: about 25 MiB installed and 9 MiB downloaded on
arm64 (17 MiB and 8 MiB on armeabi-v7a). The C and C++ API libraries in the AAR are
not packaged. A universal debug APK carries all four ABIs.
