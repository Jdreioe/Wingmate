# Wingmate

Wingmate is a local-first AAC application for people who cannot rely on natural speech. It provides peer Typing and Screens workspaces.

## Language

**Communicator**:
The person who uses Wingmate to communicate. The Communicator's needs take priority in product decisions, with Jonas's lived experience as the main guide.
_Avoid_: End user, patient

**Supporter**:
A person trusted by the Communicator to help prepare vocabulary or configure Wingmate. A Supporter may be a family member, therapist, teacher, or school staff member, but is not a separate account or authority in Wingmate.
_Avoid_: Administrator, caregiver

**Communication workspace**:
One of Wingmate's peer ways to compose and deliver communication. Typing and Screens remain distinct while sharing Messages, speech, access preferences, and history.
_Avoid_: Mode

**Typing workspace**:
The Communication workspace for free text, predictions, and saved phrases.
_Avoid_: Keyboard, Phrase screen

**Typing Screen**:
The persistently customizable, built-in input surface of the Typing workspace that replaces the system keyboard when opened. It uses the shared Screen runtime with an All Phrases Page, one live Page per Category, and an optional History Page; it cannot be deleted or selected as a startup Screen and is not part of normal vocabulary-package export.
_Avoid_: Phrase screen, OBZ Screen

**All Phrases Page**:
The starting system Page of the Typing Screen, presenting every Phrase in its authoritative repository order without representing a Category.
_Avoid_: All Category

**Screens workspace**:
The Communication workspace for visual vocabulary arranged across linked Pages.
_Avoid_: Boards workspace, Board library

**Message**:
The single communication currently being composed from structured Message parts. The active Message and Held message survive workspace changes, app closure, and device restart until explicitly cleared.
_Avoid_: Sentence, Input, Thought

**Message part**:
One meaningful portion of a Message, containing display text, spoken text, and optional visual metadata. Typed text, a Phrase, and a Screen Button may each contribute a Message part.
_Avoid_: Token, Segment

**Held message**:
A single Message set aside temporarily so the Communicator can compose another Message. Holding again swaps the active and held Messages without overwriting either.
_Avoid_: Held thought, Pinned thought

**History**:
The private, local record of Messages the Communicator has asked Wingmate to speak. History recording is distinct from prediction learning, operational logging, and anonymous feature reporting.
_Avoid_: Usage log, Analytics

**History Page**:
The optional, read-only Page in the Typing Screen that presents History without treating its Messages as Phrases or a Category.
_Avoid_: History Category

**Editing access**:
Optional device-level protection against accidental or unwanted vocabulary changes in Typing and Screens. It never blocks communication, does not identify a person or assign roles, and may be used by the Communicator or a trusted Supporter.
_Avoid_: Authorization, Editor role

### Typing vocabulary

**Phrase**:
A reusable piece of communication in Typing. A Phrase may belong to one Category.
_Avoid_: Item, Grid item

**Category**:
A flat grouping of saved Phrases in Typing. Each Category is projected as a live Page in the Typing Screen; Categories do not nest and are not represented as Phrases or folders.
_Avoid_: Folder, Phrase category

### Screens vocabulary

**Screen**:
A named visual vocabulary containing linked Pages, including one starting Page.
_Avoid_: Board set, Board library

**Page**:
One visual communication layout within a Screen.
_Avoid_: Board, Screen

**Page element**:
A typed, movable, and resizable component placed on a Page. A Button is one Page-element type; richer types can present live vocabulary or communication controls.
_Avoid_: Custom element, Widget

**Phrase collection**:
A Page element that projects live Phrases into standard runtime Buttons within its layout region while leaving the Phrase repository authoritative. It has a compact, horizontally scrolling row and an expanded grid; both presentations share vocabulary state.
_Avoid_: Phrase grid, Phrase buttons

**Page navigation**:
A Page element that presents the Typing Screen's All Phrases, Category, and optional History Pages as a horizontally scrolling row of chips. Category order comes from the Category repository rather than Page layout.
_Avoid_: Category filter, Category chips

**Action strip**:
A Page element that arranges an ordered, horizontally scrolling set of ordinary Buttons for related communication controls. Its Buttons retain normal editing, access, styling, and action behavior.
_Avoid_: Playback bar, Custom controls

**Button**:
A selectable piece of vocabulary or an action on a Page. A Button may occupy one or more Cells.
_Avoid_: Field, Grid item

**Grid**:
The rows and columns used to arrange Buttons on a Page.

**Cell**:
One position in a Grid. Several Cells may belong to the same Button.

**Screen lock**:
A per-Screen freeze against changes to finished vocabulary. It uses Editing access when configured and has no separate credential or permission meaning.
_Avoid_: Authorization, Biometric lock

### Data movement

**Local-first**:
A data-ownership model in which Wingmate keeps communication content on the Communicator's device. Moving data between devices is an explicit backup, restore, or vocabulary transfer rather than synchronization.
_Avoid_: Synced, cloud-backed

**Backup**:
A private snapshot used to restore one Wingmate installation. It may contain personal communication data and is not a vocabulary-sharing format.
_Avoid_: Sync, Vocabulary package

**Vocabulary package**:
Reusable communication content transferred without History, credentials, or personal settings. An OBZ file is the Vocabulary package for Screens.
_Avoid_: Backup, Sync
