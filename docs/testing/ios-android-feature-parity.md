# Recent Android features on iPhone and iPad

The iOS client now exposes the recent Android additions from #103, #110,
#121, #213, and #227. Selection feedback from #120 was already present.
The shared models, ranking, settings, archive handling, and paper layout remain
the sources of truth.

| Feature | iOS entry point | Checks still requiring Xcode/device |
| --- | --- | --- |
| Likely next | Privacy settings; Typing Page and row above keyboard controls | Enable with visible History, select a suggestion, hide History, restore backup |
| Background image | Page edit menu; Photos, Fit/Fill, Remove | Replace/remove, rotate iPad, relaunch, OBZ and backup round trips, missing media and high contrast |
| Touch-down | Accessibility settings | Single contact selects once; release and dragging do not select again; cancellation; VoiceOver, Switch Control, hold precedence |
| Swipe Pages | Accessibility settings; Screens and Typing vocabulary area | 64-point horizontal threshold, 1.5:1 horizontal dominance, first/last edges, vertical scroll, editing, hold and touch-down conflicts |
| PDF export | Screen library and workspace printer buttons | Share/save/print on iPhone and iPad; symbols, merged Cells, long labels and Page links |

Touch-down commits at first contact, matching Android. Moving afterwards cannot
undo the selection. Vocabulary grids stop scrolling in this setting; editing
and reorder gestures on Phrases are disabled. Hold-to-select takes priority.
Swipe navigation is disabled during touch-down and hold selection. Ordinary
Page chips remain available. Screens follow stored Screen Page order without
wrapping; Typing follows All Phrases, Categories in repository order, History,
then Likely next when available. Successful navigation crossfades the Page and
moves VoiceOver/Switch Control focus to its title or selected Page chip. Old
Page access targets unregister, cancelling pending dwell selection.

PDF export uses the shared paper layout and renders a landscape A4 PDF with
UIKit. The native share sheet supports saving and printing on iPad as well as
iPhone. Missing symbols leave labels readable. Author background colors remain,
with an opaque white backing behind text for contrast.

Shared JVM tests cover the native boundary's History privacy and clear behavior,
background removal without deleting Button media, locked Screen rejection, and
access to paper layout. The existing domain tests cover ranking, background
metadata and paper layout. These tests cannot verify Swift compilation or native
gesture behavior. Run `ci_scripts/verify_ios.sh` on macOS, then exercise the
checks above on an iPhone and an iPad before declaring parity verified.
