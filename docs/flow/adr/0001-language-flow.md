# ADR 0001: Language Flow

## Status

Accepted.

## Context

People often read selections containing more than one language. A single voice
can make the other language difficult to understand. Flow should automatically
route each sentence to an appropriate configured system voice, without sending
selected text off-device merely to identify its language.

## Decision

- Flow splits a selection into sentences and identifies each sentence's
  language locally.
- On macOS, use Apple's `NLLanguageRecognizer` to detect broadly and obtain
  language-confidence hypotheses. Do not constrain identification to the
  enabled languages.
- Flow switches only among languages the person has enabled and assigned a
  voice. A required default language and voice handle language without a
  configured route.
- Automatic language switching is enabled by default once the person has more
  than one language route.
- Before playback starts, Flow presents a Language check popup for every
  uncertain sentence. Playback never stops unexpectedly to ask a language
  question.
- When Flow confidently detects a language that is not enabled, it asks whether
  the person wants to enable it rather than reading it in the default voice.
- The playback popup shows each sentence's detected language and provides a
  manual override. A correction can affect just that sentence or every matching
  detected sentence in the current ephemeral reading.
- When the detector is uncertain, Flow asks the person to choose a language for
  that sentence rather than silently guessing.
- A multilingual Azure voice remains one selected voice throughout a reading.
  Flow sends its detected or manually overridden language tag with each
  sentence, but Azure does not select another voice automatically.
- A non-multilingual Azure configuration has a separate Azure voice for each
  enabled language route. Flow switches only between the person's configured
  route voices.
- Every route stores an exact BCP-47 tag, such as `da-DK`, rather than relying
  on a detector's base-language result such as `da`.
- Speech rate is stored per language route for system voices. It is also stored
  per language route for a non-multilingual Azure voice.
- A sentence is uncertain when the top language hypothesis is below 75%, or its
  lead over the next hypothesis is below 15 percentage points.
- If enabling a detected language has no matching system voice installed, Flow
  opens the route editor, explains the missing voice, and links to macOS voice
  downloads.

## Consequences

- System-voice playback becomes a queue of sentence speech units, each with a
  chosen language and voice.
- A manual correction applies to the current ephemeral reading only. It is not
  retained as personal language data or as selected-text history.
- Language detection, confidence values, and sentence text remain in memory
  only for the active playback popup.
- Azure receives the selected text and the language tags only when Azure is
  selected. System-voice language detection remains local.

## References

- [Apple: Identifying the language in text](https://developer.apple.com/documentation/naturallanguage/identifying-the-language-in-text)
- [Apple: NLLanguageRecognizer](https://developer.apple.com/documentation/naturallanguage/nllanguagerecognizer)
