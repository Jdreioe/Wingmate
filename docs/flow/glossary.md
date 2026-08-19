# Flow glossary

## Language Flow

Flow's local sentence-by-sentence language detection and optional system-voice
switching. It is intentionally named after the product.

## Language route

One enabled language in Settings. It contains an exact BCP-47 language tag, a
voice, and its speech rate. Examples are `en-US` with an English voice and
`da-DK` with a Danish voice.

## Default route

The required language route used when Flow cannot identify a sentence, unless
the person resolves it in Language check.

## Language check

The pre-playback popup that asks the person to resolve uncertain language
detections and optionally enable a confidently detected but unconfigured
language. It never appears mid-playback.

## Manual override

A correction that changes the route for one sentence, or every sentence with
the same detected language in the active reading. It is discarded when the
ephemeral reading ends.

## Azure language tag

The BCP-47 language tag Flow sends with an Azure sentence. The tag is detected
or manually overridden by Flow; it does not mean Azure may silently choose a
different voice.

## Multilingual Azure voice

One Azure voice that can pronounce more than one language. Flow keeps that
voice for the entire reading and sends a language tag for every sentence.

## Non-multilingual Azure route

A configured Azure voice for one language. Flow may switch between separately
configured non-multilingual Azure routes, but only among the person's enabled
languages.
