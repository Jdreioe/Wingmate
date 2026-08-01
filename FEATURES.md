# Wingmate Features

The following list is taken from the OpenAAC [AAC App Development](https://www.openaac.org/considerations) page, checked against the current state of Wingmate. `[x]` = implemented in Wingmate, `[ ]` = not yet implemented.

## Feature Key
- 🟢 Standard Across Apps – Features that are included in virtually every AAC app.
- ✅ Most Apps Have – Features that are commonly found in most AAC apps, though not universal.
- ⭐ Top Apps Have – Features that are present in leading AAC apps and are considered advanced or premium.
- 💡 Often Requested – Features that users frequently ask for, even if not widely implemented yet.
- 💜 Specialization – Features aimed at specific user needs, niche use cases, or customization for individual accessibility.

<details>
<summary>Display</summary>

- [x] 🟢 **words and symbols**
  Some apps show text-only buttons, but many show (or have the option to show) images that are paired with the button text. Some apps use actual photographs, while others use line-drawn symbols. For some types of disabilities, photographs may be too visually overwhelming or stimulating, although some adults who need AAC later in life prefer less "cartoony" images.
- [x] 🟢 **grid layout**
  A grid-based layout is the most common layout for AAC apps, so if you are using an alternative layout, please make sure it works well for your users.
- [x] 🟢 **links to other boards**
  Most apps have some kind of visual indication when a button is actually a link to another layer/board/view. This is usually a different border style or thickness, folder-shaped border, or a visual flag in the top corner of the button. Some apps have a setting to auto-return to the starting board when an unlinked button is navigated to and pressed. This can be helpful in teaching users a consistent/reliable sequence they can use to get to a specific button.
- [x] 🟢 **say something different than button text**
  This is used for two common purposes: to override a word that is being mispronounced, or to show a single word, abbreviation or shortened version of a longer phrase that will be spoken when the button is activated.
- [x] ✅ **symbols-only option**
  Some proficient users choose to turn off labels on their buttons once they have memorized their locations because it makes the interface less cluttered for them.
- [x] ✅ **words-only option**
  While some users or their support staff prefer images for more easily finding buttons, some literate users prefer not to have images because they can scan through the options more easily, or the pictures are distracting or distasteful.
- [x] ✅ **text on top/bottom**
  To encourage literacy, some people suggest putting the word above the symbol on each button, but other users prefer to have the word below the symbol.
- [ ] ⭐ **skin tone selection for symbols with people**
  In the past many symbol libraries only offered light-colored skin tones for people-related symbols, and it was generally tolerated because there weren't a lot of options, but many proprietary symbol libraries have now added skin tone options, so the expectation of supporting skin tones is more common.
- [x] ⭐ **high contrast option for all symbols**
  Some users may have visual impairments which can make it difficult for them to see photographs or even symbols with a broad range of colors. Many symbol libraries include high-contrast alternatives, which tend to have a black background, thicker and less-details lintes, and limit the symbol to only a few, high-saturation colors at a time.
- [ ] 💡 **non-grid layout**
  Many AAC systems rely on a grid-based layout for simplicity and consistency, but not all. Being able to have alternative layouts or even just having some buttons that span multiple cells in a grid can be valuable, especially on keyboard-style layouts.
- [ ] 💜 **visual scenes**
  Visual Scene Displays are a specific type of non-grid layout where the screen is filled with a drawing or photograph, and buttons are drawn or placed directly over items in the picture (think labelling everything in the picture of a bathroom). This can help beginning AAC users, and also others who may struggle mapping concepts to spoken words or phrases.

</details>

<details>
<summary>Customization</summary>

- [x] 🟢 **add new buttons**
  Most users will want to personalize the vocabulary that is provided, at the very least with names of people they regularly encounter, with personal examples, or words or topics that are unique but important to them.
- [x] 🟢 **upload symbols**
  When users personalize a vocabulary, they may find existing symbols that match the words they are adding, but photos of people or places are unique and personal, and no library contains symbols for every possible word or concept. Additionally, for some users the provided mapping of concept-to-symbol may not make sense in their culture, environment or preference.
- [x] 🟢 **rearrange layout**
  Most AAC systems allow users to move symbols around to the locations that work best for them. They may have physical impairments which make certain areas of the screen easier to access for them, and can choose to organize their layout around their personal efficiencies.
- [x] ✅ **hide/show buttons**
  When a user is new to AAC, or focused on a specific context, they may want to hide buttons to improve focus or prevent being overwhelmed. This if often a better option than starting with a board with only a few buttons, because it makes progression more approachable going forward. Some apps will also have pre-programmed levels that hide sets of buttons, or an option to temporarily show all hidden buttons.
- [x] ✅ **upload sounds**
  Users may want to record personalized audio sound bytes, sound effects or other audio to play back when a button is selected
- [x] ✅ **custom grid size**
  While some apps offer a preset size for their buttons, there will be users who would like to fit more buttons on a single screen, and others who will have trouble selecting buttons and can benefit from larger targets, so it makes sense to have the button size (i.e. grid size) a user-configurable option.
- [x] ✅ **button border and fill color**
  To help with visual organization, especially on larger boards, it can make sense to great visual clusters of buttons that have the same fill or border color. Additionally, some apps will auto-color buttons based on their part of speech (see: Fitzgerald Key for an example of this).
- [x] ⭐ **search through symbol library for images**
  Having a default symbol available for each word can be useful, but there are times when the user may want to select an alternative option, such as a green instead of a red apple, to more closely match their personal experience, or to find a closest match for a word that doesn't already have a default symbol.
- [x] ⭐ **add photos from device/camera**
  Users will want to use personalized images, such as those found on their camera roll, for personal locations or people, or to better match words based on their personal experience.
- [ ] ⭐ **easily switch symbol sets**
  Some apps support multiple symbol libraries, and it can be useful to quickly switch between them in batch. Some school districts or educational programs center their material around a particular symbol library, so being able to match AAC symbols to those used in other contexts is an oft-requested feature.
- [ ] ⭐ **color coding of buttons by word type**
  This is often done with a variation of the Fitzgerald Key, and words of different parts of speech are colored differently. The benefits are twofold: it provides some basic instruction on grammar for the AAC user, and because words are often clustered by word type, it creates regions of color which help organize an potentially-large grid of options.
- [ ] 💡 **quick access to hide/show buttons**
  Sometimes called a "Babble" function, this allows the user, for a single session, to turn on all hidden buttons. This provides value for those who are exploring a new system but who, for varied regions, may not be best served by seeing all buttons all the time.
- [ ] 💡 **option to lock editing behind an access code**
  It is important to respect the AAC user, and ideally they would have control over their own vocabulary, but there may be cases where the AAC user is not proficient enough with their technology to reliably work the editing interface, or where they may be entering the editing interface on accident. Keep in mind that some users may be editing or deleting buttons as a method of communication and may feel they are being silenced or forcibly-controlled if they are locked out of this type of interface.
- [ ] 💡 **offline backup**
  Devices break and get reset. Sometimes administrators run a wipe of all campus devices without realizing the full repercussions, for example. It is important to provide users with a way to either manually or automatically store what they have on their device to a remote location. Some apps have their own cloud storage, others rely on storage provides like Apple, DropBox, etc.
- [x] 💡 **sharing vocabulary sets across users**
  The amount of work it takes to create or modify an AAC vocabulary is significant. Once someone has worked to create a vocabulary, there is often value in sharing it, either with other known users, or publicly for others to use.
- [ ] 💡 **different-sized buttons**
  Some layouts work better with mostly-small buttons and some larger buttons, such as a keyboard with word suggestions. Additionally, some AAC vocabulary creators prefer to draw extra attention to certain buttons, and having them span columns or rows is a useful way to improve access or attention.
- [ ] 💜 **choose grid background color**
  Users with visual impairments may find it is easier to look at and understand buttons when there is a darker background to help bring things into focus (think, dark mode). Additionally, some vocabulary designers may use different-colored backgrounds in different views as a visual indicator of their purpose.
- [ ] 💜 **set background image behind buttons**
  Users with visual impairments may find it easier to see the words or symbols on buttons with a black background, either filling the entire button, or just behind the actual image.
- [ ] 💜 **customizable keyboards**
  Some AAC apps user the built-in keyboard for spelling purposes, but depending on the user these keyboards may not be accessible. Additionally, some users may need larger buttons and a full keyboard doesn't fit on a single screen, or QWERTY may not be the most intuitive layout, so custom keyboards can provide value for different users.
- [ ] 💜 **options for different-shaped buttons**
  Some apps provide optional border shapes other than just a square or rounded square. They could be jagged edges, thought bubble or speech bubbles, etc.

</details>

<details>
<summary>Access</summary>

- [x] 🟢 **touch**
  Touch access is by far the most common interface for interacting with AAC, but for users with motor issues, they may need specialized touch options in order for the interface to work for them. For example, some users with place a plastic overlay on top of their device with holes cut out for each button, making it less likely for them to accidentally press the wrong buttons. Keep in mind that "touch" interfaces may be hit with a person's finger, or with a stylus or other selection tool.
    - [ ] 🟢 **select from touch-start**
      Some people may be able to hit the correct target initially, but then due to movements, lift their finger on a different target than where they started.
    - [ ] 🟢 **select from touch-release**
      Some people may have to work just to get their finger down on the device, but then once it's stable they can more easily move it to the correct location and lift on the correct target.
    - [x] ⭐ **hold to select**
      Some people may accidentally tap, and prefer to select based on them holding their finger or stylus on the correct target for a user-defined period of time (milliseconds, for example).
    - [x] ⭐ **user-defined hold duration**
      Make sure that the "hold to select" duration isn't just a pre-defined timeout, but can be configured by the user (milliseconds is the typical unit).
    - [ ] 💜 **fixed selection (explicit keyboard map)**
      An additional option that some people prefer is to have a keyboard-type interface in front of them, where each key maps to a target on-screen. So, for example, they might hit "q" to select the top-left button, "w" for the next, etc. The mapping of keys to button locations should be configurable. Holding a hand upright may be fatiguing for some people, or covering the screen with their hand may be difficult.
- [x] ⭐ **scanning**
  "Scanning" is a term used in AAC for a visual scan that people can use to select buttons. Typically a highlight border jumps from target to target (or region to region) and the user hits a button when the highlight gets to the right target. Scanning interfaces also optionally read out the contents of the current target as it is scanned. Sometimes the user may have multiple buttons, one for select, one for "next" or "previous" or "canncel". Actions are usually mapped to keyboard events, and most accessible switches are programed to send keyboard events. With enough buttons, scanning through every single target every single time would take too long, so targets are often clustered into regions and the person picks a region first, then from all the targets in that region.
    - [x] ⭐ **row scanning**
      This is the most common option. Often the sentence box is its own row, then each row of buttons. The sentence box may instead come at the end of the list instead of the beginning. Once the correct row is selected, scanning moves through all the items in that row.
    - [x] ⭐ **column scanning**
      Similar to row scanning, but with columns.
    - [ ] ⭐ **accept on select**
      The default expectation is that when a person hits the "choose" button then the current target is selected. This can be done when the person presses down on the button, or when the push down and up in fast succession.
    - [ ] ⭐ **accept on release**
      People may have trouble getting the button pushed down at the correct time, and have an easier time timing the "release" of the button as their selection action.
    - [x] ⭐ **advance on select**
      If the person has more than one button, they might use one for select, and another for "next" so they can control the speed in which options are scanned through.
    - [ ] ⭐ **accept on no-click**
      Some people prefer to use a "next" button to advance through the targets, and rather than needing a second "select" button, they can just \*not\* hit the "next" button before a timeout and consider that a selection. Keep in mind that in this case, you'll want to wait to trigger "select" events automatically until after the person has hit "next" at least once, otherwise non-action will result in the first button being hit over and over.
    - [ ] ⭐ **cancel on select**
      Some people want an additional "cancel" or "go back" button that allows them to start over or drill back out more quickly.
    - [ ] ⭐ **auditory scanning**
      As the scanning interface runs through potential targets, it can also optionally read off those targets, so that those who need help focusing or who cannot see as well, can listen instead for the desired option. Additionally, some auditory scanning interfaces will read these options in a different voice (or through a headphone output) than the person's preferred speech output voice.
    - [ ] 💡 **region scanning**
      In addition to row/column scanning, some apps offer configurable region scanning. Regions could be defined as # of rows & columns, or some apps also allow vocabulary authors to define their own scanning regions as part of the vocabulary interfact to better accommodate scanning users.
    - [ ] 💜 **axis/crosshair scanning**
      An additional scanning-style interface involves showing panning a target either veritically or horizontally back and forth across the screen until the person hits a button. Once they have chosen an x or y location, the panning can switch directions, allowing the user to select a specific x,y location on-screen. This can be faster than item-based scanning, but requires more precision.
    - [ ] 💜 **region drilldown**
      A specific style of region scanning, where the vocabulary author or the AAC user can define their own custom scanning regions.
    - [ ] 💜 **double/triple tap/hold to change action**
      Some apps support extra actions by double- or triple-tapping the button. This can give a person more flexibility without requiring too many separate buttons.
- [x] 💡 **mouse control**
  Mouse control may be easier for some people, but it also encompasses a number of alternative tracking modes that emulate mouse events or show an on-screen cursor like a mouse cursor. These could include head trackers, joysticks, touch screens, etc.
    - [x] 🟢 **click to select**
      The "click" action could be on an actual mouse, or some from a secondary device like a button that would be used for scanning (and may be programmed instead to trigger a keyboard event).
    - [x] 💡 **dwell to select**
      As an alternative to "click", the person may find it easier to steer the cursor to the location of a button and then keep it there inside the button. This would be considered a "dwell" selection.
    - [ ] 💡 **double/right click for special action**
      Some mouse interfaces use double-click or right-click to trigger specialty actions, such as popping up a sub-menu or extra options.
    - [ ] 💜 **custom cursor**
      Some people may not be able to see the standard mouse cursor very well, and can benefit from alternative cursor icons like a larger pointer, colored dot, etc.
- [x] 💡 **eye gaze/head control/joystick**
  These control methods may operate similar to a mouse control, showing an on-screen cursor and using a button to select, but they may also rely on dwelling on a target as a selection technique, or they may not show an on-screen cursor. For example, many eye gaze-tracking interfaces do not show a custor where the user is looking because it can feel confusing as the cursor follows the person's gaze around the screen. This only works because eye gaze isn't adjustable like other cursor control interfaces, so if it's not calibrated correctly you can't easily compensate by just looking slightly to the left/right, which comes more naturally for something like a head-tracking interface. Note that iOS and Android both have head tracking libraries to make this more automated. You can see examples of using these libraries in the cordova_face library.

  *Supported via the OS:* Wingmate uses standard touch/click/pointer input, and the iOS UI is built on standard SwiftUI accessibility elements (labels, hints, custom actions, explicit accessibility sort order for scanning), so OS-provided access methods work with the app — e.g. iOS Eye Tracking / Head Tracking (iOS 18+), Switch Control, Voice Control, Windows Eye Control. There is no in-app gaze calibration UI.
    - [x] 💡 **dwell to select**
      Dwell-to-select is the more-common option for eye tracking interfaces, and for some head-tracking interfaces, since it takes enough focus to select the target that then hitting something else may be too big an ask. Also, dwelling on a target feels natural for eye tracking interfaces. The amount of time required for a dwell action to trigger should be user-configurable, and is often but not always less than one second. *(App-level dwell-to-select also applies to OS-driven cursors.)*
    - [ ] 💡 **keyboard/button press to select**
      With tracking interfaces, some people prefer hitting a button, screen or key for selection rather than waiting for a dwell timeout.
    - [ ] 💡 **head as joystick vs head as pointer**
      For head tracking (and potentially for joystick tracking) there are two common ways of controlling the cursor. The first, "head as pointer" would be like taping a laser pointer to your head and having that control the cursor. The cursor position would map directly to where the head is pointing at the screen. The second option, "head as joystick", means that the cursor is controller by the head position, but they aren't locked together. So if the user tilts their head a little to the right, the cursor will start sliding to the right. If they tilt far up, the cursor will move quickly up. If they center their head, the cursor will stop moving.
    - [ ] 💡 **pause tracking**
      Because these interfaces often rely on head or eye gaze position, they can become tiring and will misfire if the person's attention is on something else. It can be vaulable to have a "pause" button or region the person can use to temporarily disable tracking.
    - [ ] 💡 **expression to select**
      Because some eye- and head-tracking users may not be in a position to use other interfaces like a button, the most obvious selection technique is dwell, but this can take a long time, so another option is to track facial expressions as a way to select the current target.
        - [ ] 💡 **blink**
        - [ ] 💡 **wink**
        - [ ] 💡 **tongue**
        - [ ] 💡 **open-mouth**
        - [ ] 💡 **smile**
        - [ ] 💡 **eyebrows**
- [x] ✅ **adaptations**
  As users select buttons, there can be different accommodations based on the needs of the individual user that can make selection and access more meaningful. Below are some selection adaptations that people use or have requested.
    - [ ] ✅ **debounce (prevent multiple hits)**
      Some people accidentally hit buttons multiple times in a row, often in quick succession. Having a debounce option can prevent unintentional repeats. This should be an optional setting though, as some people hit buttons quickly enough that a debounce feature may get in their way.
    - [ ] ✅ **option to speak each word on select**
      Some people use button selection as their main communication approach with AAC. They speak each word as it is selected, and may use the sentence box for additional emphasis. Especially with beginning AAC users, this option provides more immediate feedback and can help the person as well as those supporting them.
    - [ ] ✅ **option to only speak when hitting the constructed sentence**
      Some people prefer to build their sentences in private and only have the device speak once they have completed their statement.
    - [x] ⭐ **click sound on select**
      Some apps optionally make a clicking sound as the person navigates their vocabulary or builds sentences. This can help others know when a person is actively working on saying something, but is seen as a nuisance by some AAC users.
    - [x] ⭐ **option for button spacing, border size**
      Getting to the intended button can be difficult for some, or at some times, and being able to configure the size or the gutters between buttons, or the size of the edges of the buttons, can help some people see their intended target or get to it with less difficulty. Note that this is often a global preference rather than for a specific board or button.
    - [ ] 💡 **highlight on select**
      There is some research showing that visual highlights, especially popping up just the word as it is spoken, can help improve literacy. Additionally, some users, such as those with auditory issues or who use AAC in louder environments, may prefer a visual indication that the button was selected.
    - [ ] 💡 **swipe to scroll between pages**
      For some touch-based users, accidentally sliding across the screen can essentially move the entire interface. Additionally, if they've learned where specific buttons are on the screen, swiping can disrupt that motor memory (think how a keyboard with sliding rows would feel). However, some users prefer swiping to scroll through more options than would fit on a single screen, or as an alternative form of navigation to jup between different views.
    - [x] 💡 **auditory fishing**
      If a user has processing or visual difficulties, then they may find themselves struggling to find or select the intended target. Auditory fishing is an approach where the device speaks the button when it is selected (optionally in a different voice or into a headphone output) but requires a second selection action (such as tapping again) in order to actually select the option.
    - [ ] 💜 **digital zoom**
      Some interfaces employ a digital zoom, which acts in some respect like a scanning interface, where the person can select the region of interest, then zoom in to that region and see the options there with larger targets. This can help make a large grid size feel more approachable for some people.

</details>

<details>
<summary>Sentence Box</summary>

- [x] 🟢 **build whole sentences**
  Many apps have a blank area that either shows plain text or text-and-images, as a place where the user can combine multiple words or phrases into a cohesive sentence or statement. Often if the user taps this area then it will speak the entire contents of the sentence. Some apps auto-clear after the entire contents are spoken, and some have a separate clear or backspace button.
- [x] 🟢 **tap to speak sentence**
  Most apps that have a sentence box allow the person to tap this box and have the entire contents of the box spoken at once.
- [x] 🟢 **clear button**
  When users can collect multiple words or phrases into a larger unit, it makes sens to give them a way to clear this on-demand -- to start over, to start a new though, or just to clear the view visually.
- [x] 🟢 **backspace button**
  Some apps combine clear and backspace into a single button, and you tap to backspace, or long-press or double-tap to clear.
- [ ] ✅ **clear sentence on select**
  The most common use case for the sentence box is to craft a longer statement, speak it, and then start a new statement. As such, it can be useful for some users if there is the option to automatically clear the sentence box once its contents have been spoken.
- [ ] ✅ **quick access phrases**
  Having global access to some user-configured quick phrases (think "give me a minute to respond", "I use this device to speak", etc.). Often these quick access phrases will speak a message without adding the message to the sentence box.
- [ ] ⭐ **option to include images**
  Some apps have a text-only sentence box, which allows for building longer or larger statements. Additionally, some apps will show the sequence of buttons, so for each button it will show the word from the button as well as the image on that button (or the sequence of images used to get to that button -- though note that there are some patents related specifically to that behavior which may be an issue).
- [x] ⭐ **saved phrases**
  In addition to quick-access phrases, it can be useful if, as people build statements in the sentence box, they have an easy way to save the current statement for later. This could be a short-term stash, or a longer-term "I will use this phrase often" sort of action.
- [x] ⭐ **hold that thought**
  Because it takes time to craft a complete sentence, people may get interrupted mid-statement. If this happens, they may want to pause their current statement, craft a different response, and then go back to what they were doing. This is sometimes called "hold that thought" because it allows a person to temporarily pin their current statement, craft something completely different, and then resume the original statement.
- [ ] ⭐ **repeat louder**
  If a person feels like they weren't heard, it can be useful to be able to temporarily increase their volume and repeat what they just said for others to hear.
- [x] ⭐ **share sentence externally**
  Mobile apps often have "Share" tools that allow sending text or material between apps. In this way a person could quickly share their message via text, social media, etc. It is worth noting that while a user may have used images to help them craft their message, some AAC users prefer not to have those images included in their message when they share it externally.
- [ ] 💡 **flip text to show someone else**
  If a person wants to share their message with someone else in a noisy environment, or in a situation where reading makes more sense, then the person can set the device down so both parties can see it. However, if the other person is across from them, they will have to read upside-down. Having the option to flip (and possibly enlarge) the text in the sentence box can help a person more easily share their message while it is being written, or in a text-preferred environment.
- [x] 💜 **show on secondary display**
  Some devices come with a secondary display that sits on the back of the device, so that if a person desires, their message can appear visually to the person in front of them, even as they work on building the message. This is particularly useful for devices that are mounted in front of a user like on a wheelchair.

</details>

<details>
<summary>Vocabulary</summary>

- [ ] 🟢 **pre-populated vocabularies**
  Most people don't have the time, interest or expertise to create a full vocabulary from scratch, so make sure you have created some resources that people can at least start from as they adopt your app. If you need help, there are some free, open-licensed vocabularies you can use or modify as part of the Open Board Format site.
- [x] 🟢 **places for personalized words**
  Most people will want to have custom vocabulary based on their personal relationships, interests and locations. As you design vocabularies, make sure you take this into account and look for places for personalized buttons.
- [ ] ✅ **core words in pre-populated vocabularies**
  Core words are words that are used frequently and can be used in multiple contexts. Some apps focus heavily on nouns or needs that can be requested, but in order to have a strong, robust vocabulary, people need the ability to express a broad variety of thoughts, and having access to core words is a useful way to enable more diverse communication.
- [x] ✅ **category-based layout option**
  The most common layout for AAC vocabularies is to have a set of high-frequency words on the main view, and then link to additional words organized by category. Please note this isn't to say that this is how all AAC should be implemented, but it is useful to know what people are used to seeing.
- [ ] ✅ **multiple grid sizes pre-built**
  AAC users have different degrees of visual acuity and motor skills, and some may be able to handle very large grids (typically not larger than 8x15, or 120 cells), while others would not be able to hit buttons that small. It is valuable to support different grid sizes to accommodate different users. Note that the smallest "robust" vocabularies are typically not smaller than 3x5 (15 cells) or 4x6 (24 cells). Some users may require even larger buttons than that, but supporting them may require different layout approaches.
- [ ] ⭐ **motor planning-based layout option**
  "Motor planning" is a term used in the AAC community, and it is similar to the idea of muscle memory. We may take for granted how easy it is to get our bodies to lift a hand, point a finger, move it to the right place, set it down, and lift it again. If the layout for a vocabulary changes regularly (i.e. buttons move to different spaces) then it can disrupt a person's motor plan for saying something they've said before. Additionally, being able to always hit the same sequence to say the same thing can improve speed and require less cognitive load, so some apps automatically return to the starting board whenever a non-linked word is selected.
- [x] ⭐ **option to auto-return to home board**
  Being able to always hit the same sequence to say the same thing can improve speed and require less cognitive load, so some apps automatically return to the starting board whenever a non-linked word is selected. Some users prefer this, while for others they would rather stay on the same sub-view until they manually return to the home view. The style of vocabulary layout may also affect whether auto-return is ideal or not.
- [ ] 💡 **semantic compaction functionality (patented)**
  An additional idea in AAC is the concept of semantic compaction. Briefly, this is a way to organize vocabularies with the intent of making it easier to learn and remember where words are located. It is similar to a category-based layout in the sense that the first hit specifies a category or theme, but with semantic compaction additional views place related words in the same place on every view. So the button at cell 3,4 may always have to do with food. If you hit the "action" cell first, then cell 3,4 it might say "eat", whereas if you hit the "feeling" cell first and then cell 3,4 it might say "hungry". It's useful to think of how this type of layout would have enabled people to expand their vocabulary with a static layout before dynamic touchscreens were available, by adding depth while keeping the same concept at each cell location. Note that there are patents registered in this area which should be considered before implementing a semantic compaction-style layout or app.
- [ ] 💡 **adult topics**
  Many AAC apps are adopted by both younth and adults. When school programs or parents adopt an AAC app for someone they support, they may feel reluctant to give the person access to adult vocabulary, human anatomy, swear words, etc. There is growing acceptance of the idea of including such vocabulary for all users, but it is not universal. If you decide not to add these types of vocabulary by default, it would be a good idea to provide an option whereby it can be easily enabled for those people who are comfortable with or interested in using such vocabulary.

</details>

<details>
<summary>Keyboard</summary>

- [x] 🟢 **spelling by keys**
  Most AAC apps provide some kind of mechanism whereby people can spell out words that aren't included in the default vocabulary.
- [x] ✅ **word prediction**
  In addition to saving time, having word prediction in any in-app keyboards can provide literacy instruction for those who are still learning to type or spell.
- [ ] ⭐ **punctuation keys**
  Some AAC apps provide a full alphabet for the person to spell wit, but forget to include punctuation. A spacebar allows the person to spell more than one word at a time (and also makes for a convenient way to "accept" a word as complete without ending a sentence). Other punctuation like commas, periods, etc. help with crafting grammatically-complete statements. In many apps when the user select a sentence-ending punctionuation, the app automatically speaks the full sentence that was just completed. So if there were two sentence in the sentence box, it wouldn't speak the whole sentence box, just the second sentence.
- [ ] ⭐ **capitalization**
  Capitalization is another feature that is sometimes neglected even in apps with keyboards. Being able to capitalize (at least on the keyboard, but ideally for all words in the vocabulary as well) can help the person craft more grammatically-correct statements.
- [x] ⭐ **personalized word prediction results**
  As a user spends more time in the app, either spelling or using other buttons, the app has the opportunity to generate a personal word prediction model. This is especially useful for specialized vocabulary that the person uses regularly without adding a specific button for it.
- [ ] ⭐ **read last sentence on punctuation end**
  In many apps when the user select a sentence-ending punctionuation, the app automatically speaks the full sentence that was just completed. So if there were two sentence in the sentence box, it wouldn't speak the whole sentence box, just the second sentence.
- [x] 💡 **option to use native on-screen keyboard**
  If an app provides a specialized keyboard, it is useful to allow users to opt instead to use the device-native keyboard, as some users prefer these keyboards.
- [ ] 💡 **audio output options (phonics sounds of each letter vs. saying letter name)**
  When spelling words, there are different options for audio feedback that can be applied: speaking the name of the letter as it is typed, speaking the partially-spelled word as it currently sounds, or speaking the phonetic sound that the letter typically makes. The third option, phonic sounds, is probably the most challenging to implement, but is preferred by some groups as it can best aid in literacy instruction.
- [x] 💜 **swipe spelling**
  Some on-screen keyboards now have a swipe-based interface to make it faster to spell words. This can be useful for AAC users as well. Because of the slower process for scanning, eye-gaze or head-tracking, keyboard interfaces with swipe-style features for these types of users would be of particular value. *Note* Again - if the OS keyboard support swipe spelling, Wingmate supports it 

</details>

<details>
<summary>Voice</summary>

- [x] 🟢 **standard TTS**
  Having speech output, either as audio clips or synthesized speech, is an expected feature for any app-based AAC system. Synthesized speech will sound less jarring that combining audio clips, and most operating systems now include some built-in TTS libraries.
- [x] ✅ **playback recorded audio**
  Even with synthesized speech, there are times when playing pre-recorded audio such as sound bytes, songs, etc. can be a meaningful communication tool.
- [x] ✅ **premium voices**
  Some companies provide premium synthesized voices which offer more language choices in more languages. Keep in mind that if you are implementing a cloud-based offering (Google, Amazon Polly, etc.), you will need some kind of fallback for when the person isn't in a location with a clear data signal.
- [ ] ⭐ **alternate scanning voice**
  When users enable scanning-based selection or auditory fishing, it can be difficult to tell what a user is selecting vs. what they're reading read to them. It can be useful if there is a distinction in voice style, volume, output, etc. to help separate prompts from words the person has actually selected.
- [ ] ⭐ **alternate audio fishing voice**
  When users enable scanning-based selection or auditory fishing, it can be difficult to tell what a user is selecting vs. what they're reading read to them. It can be useful if there is a distinction in voice style, volume, output, etc. to help separate prompts from words the person has actually selected.
- [x] ⭐ **adjust rate, pitch, volume for TTS**
  Even with a variety of synthesized voices, it can be hard to perfectly match a user's actual or preferred voice sound. Being able to adjust things like rate, pitch or volume, can help better match synthesized voices to the person's preferencs.
- [x] ⭐ **child voices**
  Some premium voice libraries also include child or youth voices. This can help younger people feel more comfortable using AAC because it sounds closer to their natural speech or to the speech of their peers. (Requires Azure)
- [x] 💡 **message banking**
  Some people come to AAC later in life, when they are beginning to lose their natural speech for various reasons. When a person has speech but it is limited or may not persist, people can record a collection of sound bytes that they will be able to use going forward even if their natural voice fails. While keeping this collection doesn't require AAC app support, having built-in tools to make this process easier, or to make it easier to batch-import collections of audio files, can make an AAC app more appealing to people in this category.
- [ ] 💡 **voice banking**
  A step up from message banking, voice banking is a system that allows the user to record their own speech and use that material to generate a synthesized voice based on their unique voice print. There are a few companies who offer voice banking features.
- [x] 💜 **gender neutral voices**
  Many synthesized voices are strongly gendered by intention, to make male/female voices more obvious, but for some users having a more gender neutral voice would be preferred. (Azure has 2 neutral voices)
- [x] 💜 **quick switch between voices**
- [ ] 💜 **different output target for prompts vs. speaking**
  When using scanning or auditory fishing, and additional, very useful, feature is the option to output audio to different targets. That way the prompts a person hears when listening for their intended selection can be read quietly to a headphone, but then when the user selects an option or hits the sentence box, then the output can be sent through the device's speaker instead.

</details>

<details>
<summary>Language & Inflections</summary>

- [x] ⭐ **support for multiple languages**
  Many apps start in a specific language, and only later add support for other languages or locales. This is a fine approach, but you may find demand for additional languages showing up sooner than you think, as many societies are now multi-lingual. It makes sense to at the very least prepare your app for multiple languages so that supporthing them in the future will not be such an enormous undertaking.
- [x] 💡 **multiple languages on same board**
  Many AAC users find themselves exposed to different languages at different times (Spanish at home, English at school, for example), so having a vocabulary that contains both language sets can make it easier for the person to speak in both settings without having to learn two different layouts.
- [x] 💡 **switch between languages**
  Once a vocabulary supports multiple languages, it makes sense to be able to switch between them without too much effort. Additionally, some apps have the option to show the text for both languages at the same time, allow for better translation when there are language barriers.
- [ ] 💡 **option to bring up inflections/variants**
  This is often implemented as a long-press option. Many AAC vocabularies do not show all inflections, tenses, etc. by default, sometimes considering grammatical rules less important than just getting the basic intent across. One way to support both beginning communicators and more advances ones is to support more advanced grammar using popups or extra menus. It can also be useful to allow the person to override or create their own inflections, as they can use this feature as an extensible way to create personalized shortcuts.
- [ ] 💡 **automatic grammatical tenses**
  In addition to supporting inflections, if an app has enough of a lanugage model included, then it can make automated suggestions for grammatical tenses. Keep in mind that this always sounds more straightforward than it actually is, there are plenty of edge cases to keep in mind.
- [ ] 💜 **buttons that apply inflections to the next-selected button**
  One use case that is sometimes requested is a pre-inflection option. Rather than a long-press or popup menu to apply an inflection, the user can select a specialized button that then applies a grammatical rule to the next-selected button. This can be easier for some users, and makes more sense in some languages than others.
- [ ] 💜 **native speakers review boards for each language**
  This isn't a feature per se, but please keep in mind that if you are going to offer pre-translated vocabularies, you should collaborate with a language expert before doing so. Google Translate is fine for some things, but not sufficient for translating an AAC vocabulary.

</details>

<details>
<summary>Extras</summary>

- [x] 🟢 **works even when offline (images, links, audio output)**
  AAC users will expect to use their device in all settings, not just those with a clear wifi signal.
- [x] ✅ **copy plaintext to clipboard**
  People often craft sentences or phrases within their AAC app, which they then want to copy so they can paste it into another tool or interface.
- [x] ✅ **data logging**
  As people are learning to use AAC, it can be very valuable to track trends in their communication to help with progress tracking, setting goals, etc. Some apps offer single-session tracking, and some offer tracking across all sessions. Note that this option should be user-configurable, and should require AAC user consent before being activated.
- [ ] ⭐ **easily-reachable "alert" button**
  People can benefit from having quick, constant access to an attention-getting tool like an alert button or sound.
- [ ] ⭐ **shortcut for current day/month/etc. in spoken content**
  If someone wants to say "Today is March 3" or "Today is Friday" then they may want to program these phrases onto specific buttons, but constantly updating them to keep the phrase accurate would be untenable, so some apps offer shortcuts that can be replaced in real-time by the AAC system.
- [ ] ⭐ **navigation sidebar**
  Some apps have a navigation sidebar where helper methods or interfaces can be configured to be readily available regardless of where the user has currently navigated within the interface.
- [ ] ⭐ **find a button**
  When people are learning a new vocabulary, or trying to teach someone a new vocabulary, they often don't know where words are located yet. Many apps offer a "find a button"-style interface to help people know what words are available. Additionally, some apps also allow you to select a search result and get visual indicators of how to navigate to the desired button.
- [ ] ⭐ **print vocabulary to pdf**
  Sometimes people find themselves in a place where a computer isn't ideal (think, swimming pool), or they want to have a paper-based backup in case of technical issues. Being able to export the vocabulary to a printable format like a pdf (preferably with page numbers for linked buttons) gives the person more freedom.
- [x] 💡 **import and export obf/obz**
  There is a brand-agnostic file format for AAC vocabularies called the Open Board Format which can be used to import public vocabularies, or export for transfer from one AAC product to another.
- [ ] 💡 **shared reading resources**
- [ ] 💡 **"show me how to get there" for find-a-button**
- [ ] 💡 **easily-reachable "oops" button**
- [ ] 💡 **remote editing**
  Some users are very protective of their AAC systems, and they may be reluctant or even hostile to the idea of someone else touching their system. Additionally, therapists may not have time to make changes during a therapy session. Without an option to edit vocabulary remotely, the only alternative would be to take the AAC user's device away from them in order to make changes.
- [ ] 💡 **remote tracking/control**
  With a web connection, it's possible for an AAC user's screen to be monitored or even directed remotely. In an AAC system, that could include visual prompts or hints that can be triggered remotely. If you decide to implement a feature like this, please be sure to consider user privacy and autonomy. Remote control of a user's communication system should at the very least be opt-in.
- [ ] 💡 **environmental control**
  Some older devices included an IR emitter that could be used to communicate with environmental control and home automation systems. System AAC apps can accomplish similar features using web-based integrations.
- [ ] 💡 **spinner/dice to use in spoken content**
  Some apps have specialized actions like choosing a random number. This can be useful for users who, for example, want to participate in games but can't roll dice or spin a wheel on their own.
- [ ] 💡 **abbreviation auto-expansion**
  Some users can benefit from custom shortcut combinations to more easily generate longer or more specialized words or phrases.
- [ ] 💡 **auto-contractions**
  English contractions are much more natural-sounding, so some apps automatically generate them ("are" "not" to "aren't").
- [ ] 💡 **sentence repairs**
  Being able to "repair" a sentence is valuable for many AAC users. Examples of repairs could be: reordering the words in the sentence box after they are entered, manually typing or inserting words within the sentence, etc.
- [ ] 💜 **launch extra tools (calculator, whiteboard, video player, etc.)**
  Some apps include extra tools that can be useful for specific contexts.
- [ ] 💜 **"show me how" for a user-inputted phrase**
  Similar to "find a button", some apps allow users to enter a whole phrase instead of just a single word, and then have visual indicators which guide them through the process of crafting the entire phrase. This can help, especially with families who are trying to learn a complicated vocabulary so they can model it for their developing AAC user.
- [x] 💜 **cross-platform support**
  Most new AAC apps start with iOS-only. Many insurance-funded devices are Windows-only. However, many families rely on Android because it is more affordable. Supporting users on the devices they already have helps cut down on costs and improves adoption of AAC.
- [ ] 💜 **sync content across devices**
  Whether automatic or manual, syncing still isn't the norm for AAC, many apps consider themselves on-device. This obviously leads to issues with data loss due to hardware failure, but it also means that if a device's battery dies, there is no option but to wait for it to charge, you can't quickly switch to another device with everything intact, unless syncing is an option.
- [ ] 💜 **battery left indicator**
  Some visual indication (and possibly audio tones) of how much battery is left on the device. The AAC user will not necessary have access to the OS taskbar and be able to see information like this unless it is shown inside the app. Some apps show this as a small fill bar in the sentence box when it is empty, for example.
- [ ] 💜 **launch third-party tools**
  OpenAAC has a library, aac_shim, which is an example of this. Basically a way for third parties to offer interfaces that can be rendered inside of your AAC system. Some AAC users will not be able to run other programs other than their AAC app for differring reasons, and giving them the option to still access other resources, would be a huge boon. However, expecting every AAC vendor to re-create every possible integration is not tenable.
- [ ] 💜 **act as keyboard for other apps**
  I've heard this requested on Windows devices, or through a remote connection (eg. Bluetooth) for the AAC app to operate essentially as a keyboard by sending system events that could then be used in arbitrary apps.

</details>

---
