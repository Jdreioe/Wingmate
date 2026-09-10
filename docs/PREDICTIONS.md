# Local predictions

Android's Typing and Screens workspaces and the iOS prediction bridge use one
`LocalTextPredictionService` per application process. Desktop currently has no
text-prediction caller. Typing's existing debug-build restriction remains.

Callers observe `TextPredictionService.predictions(text, maxWords, maxLetters)`.
The first observation starts loading. All observers share that work, and closing
one screen does not cancel loading for another. Changing the input cancels the
old observation; changing the primary language cancels the old model load.
Observers receive empty suggestions while a replacement loads and new results
when it is ready, without requiring another keystroke.

The service combines the language dictionary with visible, persisted History.
Dictionary downloads are cached by the existing loader and have a 15-second
loading budget. A failed dictionary load falls back to local History; a failed
History read still allows dictionary predictions. `refresh()` retries loading
and replaces the model from current data, so removed History is not retained.
Successful speech, History import, and backup restoration trigger this refresh.
Prediction loading and rebuilding run outside the speech queue. Unspoken phrase
insertion and saving a Phrase do not train the model.

The n-gram engine ranks candidate words using the previous two words, the
previous word, and vocabulary frequency. Both suggestion rows share these
scores. Letters are ranked by adding the scores of candidates sharing the next
letter, before limiting the visible word suggestions. After a space this gives
initial letters for likely next words. When no known word completes the input,
letter n-grams provide a fallback for unfamiliar words.

This is still a dictionary-and-History model. It does not include a pretrained
sentence corpus, neural inference, or a new prediction-learning setting. The
existing distinction between History and opt-in prediction learning described
in ADR 0008 is not fully implemented by the current settings model.

Focused checks:

```sh
./gradlew :core:data:jvmTest --tests '*PredictionServiceTest' --console=plain
./gradlew :feature:communication:presentation:jvmTest --tests '*QueuedCommunicationSessionTest' --console=plain
```
