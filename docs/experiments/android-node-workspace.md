# Android node workspace, stage two

Open **Node workspace** from the Typing workspace overflow menu. Alternatively,
place the cursor inside a typed word, or select exactly one word, and choose
**Open “[word]” as node** below the message field. This adds the word to the node
workspace dictionary and keeps the message in Typing intact.

## Compose, speak, change, speak again

1. Enter `I want coffee` in **Find or add words** and choose **Add words**.
   The words become connected occurrences on the canvas.
2. Choose **Speak**. The message stays on the canvas. **Stop** stops Wingmate's
   speech queue; the message remains available for another attempt.
3. With the coffee occurrence selected, choose **Replace selected**. Search for
   `tea`, then choose **Use new word**, or tap tea if it already exists.
4. Choose **Speak** again. The two requests speak `I want coffee` and then
   `I want tea` through Wingmate's speech queue and current voice settings.

Tap a dictionary word to append another occurrence to the selected message. For
example, `I think I want coffee` contains two occurrences of I and one dictionary
entry for I. Replacing or deleting an occurrence does not change the reusable
word or other occurrences. **Clear message** clears the canvas while retaining
vocabulary. **Undo** restores the canvas edit.

The message bar previews the chain containing the selected card. Drag cards to
move them. Connect an output to an input by dragging, or by tapping the two ports.
**Disconnect selected** deliberately separates its incoming and outgoing links;
ordinary deletion joins its predecessor to its successor.

## Pauses and SSML

**Add pause** appends a pause in milliseconds. **SSML** opens the code editor.
For example, apply `<speak>I want <break time="500ms"/> coffee</speak>`.

Applying code replaces the selected chain and keeps other chains. Unsupported or
invalid code remains an editable draft and does not replace the graph. Apply or
revert the draft before modifying or speaking that graph. The code view supports
words and `break time` values from 1 to 10000 milliseconds, including seconds such
as `0.5s`. Leading, adjacent, and trailing pauses are sent as explicit speech
segments, so literal words do not become speech markup.

## Vocabulary and saved workspaces

The dictionary deduplicates words without regard to capitalization. Typing
frequency belongs to a reusable word, not to its occurrences. Completed typed
words increase that count; converting an unfinished final word completes it
once. Frequency emphasis in the picker is capped, and canvas occurrences keep
fixed dimensions. Existing messages are not counted just because the app opens.

**Workspaces** creates, opens, and renames named workspaces. Changes save
automatically to an app-private file using atomic replacement. The save status
shows whether the latest changes have reached storage. A failed save preserves
the in-memory work and offers Retry. A failed load blocks workspace editing
rather than overwriting unreadable saved content.

Documents include positions, connections, selected occurrences, and unfinished
SSML drafts. The dictionary and counts are shared across the saved workspaces.
They survive fresh app launches. They are not yet part of Wingmate's explicit
backup/restore format or vocabulary-package export.

Speaking uses the shared communication session's voice selection, queue,
playback feedback, error reporting, and history. It submits an immutable graph
draft without replacing the active or Held Message from Typing and Screens.
Playback edits affect the next request, not a request already queued.

## Scope and follow-ups

This stage is Android only. It supports up to 100 occurrences per workspace and
100 saved workspaces. Branching, canvas zoom, and additional SSML controls remain
outside this stage.

The dictionary UX for 100+ words and its landscape sidebar are tracked in
[#290](https://github.com/Jdreioe/Wingmate/issues/290). Pixel 9 portrait/landscape
optimization across the Android app is tracked in
[#291](https://github.com/Jdreioe/Wingmate/issues/291). Those additions are deferred.

## Verification

Focused tests cover graph and SSML behavior, repeated vocabulary use, replacement,
word counts, save/reload and retry, immutable speech requests, preserved active
and Held Messages, and history. Emulator UI tests cover both connection gestures
and the compose, speak, replace, speak-again flow with a recording speech session.
The physical phone and actual voice output have not been exercised for this stage.
