# Wingmate vs. OpenAAC Feature Recommendations

Comparison of Wingmate's features against the
[OpenAAC "Considerations for Developers" feature list](https://www.openaac.org/considerations.html).
OpenAAC ranks features by expectation of availability: 🟢 Standard, ✅ Most Apps,
⭐ Top Apps, 💡 Often Requested, 💜 Specialization.

**Wingmate status legend:** ✅ Implemented | 🟡 Partial | ❌ Not present | 🔜 Planned (README)

---

## Display (AAC Features)

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Words and symbols (text + image buttons) | 🟢 | ✅ text + symbols; show/hide label and symbol toggles |
| Grid layout | 🟢 | ✅ grid + startup screens |
| Links to other boards (visual indication, auto-return) | 🟢 | ✅ OBZ board linking, board stack, return behavior |
| Say something different than button text (label vs. spoken) | 🟢 | ✅ `Phrase.text` vs. `Phrase.name` (vocalization) |
| Symbols-only option | ✅ | ✅ label-hiding setting |
| Words-only option | ✅ | ✅ symbol-hiding setting |
| Text on top/bottom | ✅ | ✅ `label_at_top` setting |
| Skin tone selection for symbols | ⭐ | ❌ |
| High contrast option | ⭐ | 🟡 setting label exists; no dedicated theme implemented |
| Non-grid layout (spanning cells, alt. layouts) | 💡 | 🟡 OBF `isAbsoluteLayout` parsed; no layout engine beyond grid |
| Visual scenes (VSD) | 💜 | ❌ |

## Customization

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Add new buttons | 🟢 | ✅ phrase/category CRUD |
| Upload symbols (own images) | 🟢 | ✅ file picker, photos, SVG/PNG/JPG |
| Rearrange layout | 🟢 | ✅ move phrase/category use cases |
| Hide/show buttons | ✅ | ✅ `isHidden` flag |
| Upload sounds (recorded audio) | ✅ | ✅ phrase recordings (Android MediaRecorder / iOS AVAudioRecorder) |
| Custom grid size | ✅ | ✅ grid columns setting |
| Button border and fill color | ✅ | 🟡 fill color (HSV color wheel); no border styling |
| Search through symbol library | ⭐ | ✅ OpenSymbols search + ARASAAC download |
| Add photos from device/camera | ⭐ | ✅ image picker + iOS PhotosPicker |
| Easily switch symbol sets | ⭐ | 🟡 multiple sources (ARASAAC, OpenSymbols, photos), no batch switching |
| Color coding of buttons by word type (Fitzgerald Key) | ⭐ | ❌ (manual per-button colors only) |
| Quick access hide/show (babble) | 💡 | ✅ |
| Lock editing behind access code | 💡 | ✅ |
| Offline backup | 💡 | ✅ |
| Share vocabulary sets across users | 💡 | ✅ OBZ import/export enables sharing; no dedicated share UI |
| Different-sized buttons (spanning) | 💡 | ✅ |
| Choose grid background color / dark mode | 💜 |✅ |
| Background image behind buttons | 💜 | ❌ |
| Customizable keyboards | 💜 | ❌ (Compose TextField, OS keyboard) |
| Different-shaped buttons | 💜 | ✅ |

## Access

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Touch (basic) | 🟢 | ✅ |
| Select from touch-start | 🟢 | ❌ |
| Select from touch-release | 🟢 | ❌ |
| Hold to select | ⭐ | ✅ `holdToSelectMillis` (configurable long-press) |
| User-defined hold duration | ⭐ | ✅ settings slider |
| Fixed selection (explicit keyboard map) | 💜 | ❌ |
| Scanning (row/column) | ⭐ | ✅ iOS scanning (row-major/column-major/linear, dwell, auto-advance, area toggles) |
| Accept on select / on release / advance on select / no-click / cancel | ⭐ | 🟡 advance-on-select + dwell present; per-action variants not all exposed |
| Auditory scanning | ⭐ | 🟡 auditory fishing exists; scanning read-out not verified |
| Region scanning / region drilldown | 💡/💜 | ❌ |
| Axis/crosshair scanning | 💜 | ❌ |
| Double/triple tap or hold for extra action | 💜 | ❌ |
| Mouse control / click to select | 💡 | ✅ desktop mouse input |
| Dwell to select | 💡 | ✅ dwell-to-select with progress ring + hover dwell |
| Double/right click special action, custom cursor | 💡/💜 | ❌ |
| Eye gaze / head control / joystick | 💡 | 🟡 via OS support (iOS Eye Tracking / Head Tracking 18+, Switch Control, Voice Control; Windows Eye Control) — accessibility-element-based iOS UI + standard pointer input; no in-app gaze code/calibration |
| Debounce (prevent multiple hits) | ✅ | ❌ |
| Speak each word on select | ✅ | 🟡 per-word feedback options (iOS feedback setting); not fully verified |
| Option to only speak when sentence complete | ✅ | ❌ |
| Click sound on select | ⭐ | ✅ selection sound setting |
| Button spacing / border size | ⭐ | 🟡 scaling + button-size settings; no gutter/border config |
| Highlight on select | 💡 | 🟡 word highlighting in history/prediction not verified |
| Swipe to scroll between pages | 💡 | ❌ |
| Auditory fishing | 💡 | ✅ `auditoryFishingEnabled` |
| Digital zoom | 💜 | ❌ |

## Sentence Box

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Build whole sentences | 🟢 | ✅ message/sentence bar |
| Tap to speak sentence | 🟢 | ✅ |
| Clear button | 🟢 | ✅ |
| Backspace button | 🟢 | ✅ |
| Clear sentence on select | ✅ | 🟡 playback/auto-clear not verified |
| Quick access phrases | ✅ | ❌ |
| Option to include images in sentence box | ⭐ | ❌ (soon-ish) |
| Saved phrases | ⭐ | ✅ save sentences & categories |
| Hold that thought | ⭐ | ✅ "On that thought" / pinned + scratch thought drafts (soon in Screens) |
| Repeat louder | ⭐ | ❌ |
| Share sentence externally | ⭐ | ✅ share service + clipboard fallback |
| Flip text to show someone else | 💡 | ❌ |
| Show on secondary display | 💜 | ✅ external display (androidx.window) + display text bus; rear display stub |

## Vocabulary

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Pre-populated vocabularies | 🟢 | ✅ board templates (calculator + keyboard). Easy to add |
| Places for personalized words | 🟢 | ✅ |
| Core words in pre-populated vocabularies | ✅ | ✅ community boards importable |
| Category-based layout option | ✅ | ✅ categories/folders |
| Multiple grid sizes pre-built | ✅ | ✅ configurable grid columns |
| Motor planning-based layout option | ⭐ | 🟡 board return behavior (stay/previous/start) supports consistent navigation |
| Option to auto-return to home board | ⭐ | ✅ `BoardReturnBehavior.StartPage` |
| Semantic compaction (patented) | 💡 | ❌ |
| Adult topics option | 💡 | ❌ |

## Keyboard

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Spelling by keys | 🟢 | ✅ text input |
| Word prediction | ✅ | ✅ n-gram prediction bar |
| Punctuation keys | ⭐ | 🟡 OS keyboard provides punctuation; Template supports it|
| Capitalization | ⭐ | 🟡 OS keyboard; app does not auto caåitilize|
| Personalized word prediction results | ⭐ | ✅ n-gram trained on user history (opt in soon) |
| Read last sentence on punctuation end | ⭐ |  |
| Option to use native on-screen keyboard | 💡 | ✅ |
| Audio output options (phonics vs. letter name) | 💡 | ❌ |
| Swipe spelling | 💜 | ❌ |

## Voice

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Standard TTS | 🟢 | ✅ system TTS + Azure Neural, waterfall fallback |
| Playback recorded audio | ✅ | ✅ phrase recordings, uploaded sounds |
| Premium voices | ✅ | ✅ Azure Neural voice catalog |
| Alternate scanning voice | ⭐ | ❌ |
| Alternate audio fishing voice | ⭐ | ❌ |
| Adjust rate, pitch, volume | ⭐ | 🟡 pitch/rate via SSML shorthand; full TTS controls not verified |
| Child voices | ⭐ | ✅ available via Azure catalog (voice picker) |
| Message banking | 💡 | 🟡 personal recordings supported; no banking workflow |
| Voice banking | 💡 | ❌ |
| Gender neutral voices | 💜 | 🟡 depends on Azure catalog selection |
| Quick switch between voices | 💜 | ✅ voice selection dialogs / voice engine selector |
| Different output target for prompts vs. speech | 💜 | ❌ |

## Language & Inflections

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Support for multiple languages | ⭐ | ✅ primary + secondary language, per-phrase `<en>` SSML (10-language map) |
| Multiple languages on same board | 💡 | ✅ per-category selected language, secondary playback toggle |
| Switch between languages | 💡 | ✅ secondary-language toggle (iOS sheet, bridge) |
| Bring up inflections/variants | 💡 | ❌ |
| Automatic grammatical tenses | 💡 | ❌ |
| Buttons that apply inflections to next-selected button | 💜 | ❌ |
| Native speaker review of translated boards | 💜 | ❌ |

## Extras

| OpenAAC Feature | Key | Wingmate |
|---|---|---|
| Works offline (images, links, audio) | 🟢 | ✅ audio caching, offline voice download, connectivity-aware fallback |
| Copy plaintext to clipboard | ✅ | ✅ |
| Data logging | ✅ | ✅ OBL-style usage logging + opt-in Aptabase analytics (OBL not yet fully implemented) |
| Easily-reachable alert button | ⭐ | ❌ |
| Shortcuts for current day/month in spoken content | ⭐ | ❌ |
| Navigation sidebar | ⭐ | ❌ |
| Find a button | ⭐ | ❌ |
| Print vocabulary to PDF | ⭐ | ❌ |
| Import/export OBF/OBZ | 💡 | ✅ |
| Shared reading resources | 💡 | ❌ |
| "Show me how to get there" | 💡 | ❌ |
| Easily-reachable "oops" button | 💡 | ❌ |
| Remote editing | 💡 | ❌ |
| Remote tracking/control | 💡 | ❌ |
| Environmental control | 💡 | ❌ |
| Spinner/dice in spoken content | 💡 | ❌ |
| Abbreviation auto-expansion | 💡 | ❌ |
| Auto-contractions | 💡 | ❌ |
| Sentence repairs (reorder/edit in sentence box) | 💡 | ❌ |
| Launch extra tools (calculator, whiteboard, video) | 💜 | ✅ calculator board; no whiteboard/video |
| "Show me how" for user-inputted phrase | 💜 | ❌ |
| Cross-platform support | 💜 | ✅ iOS, Android, Desktop (Compose), Linux (Rust/Iced) |
| Sync content across devices | 💜 | ✅ (Backup / Restore) |
| Battery level indicator | 💜 | ❌ |
| Launch third-party tools (aac_shim) | 💜 | ❌ |
| Act as keyboard for other apps | 💜 | ❌ |

---
*Based on OpenAAC considerations page (https://www.openaac.org/considerations.html)
and Wingmate codebase survey, August 2026.*
