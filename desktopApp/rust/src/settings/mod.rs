use crate::{
    Message,
    models::{Pronunciation, Settings, ThemeChoice},
};
use iced::widget::{
    button, column, container, pick_list, row, scrollable, slider, text, text_input,
};

use iced::{Element, Fill, Theme};

/// Sidebar navigation is sized for touch and gaze rather than a mouse: every
/// target is a full-width row at least `NAV_ITEM_HEIGHT` tall, so a dwell or a
/// finger does not have to land inside a text-height strip.
const NAV_WIDTH: f32 = 300.0;
const NAV_ITEM_HEIGHT: f32 = 84.0;
const CONTROL_WIDTH: f32 = 380.0;
const ACTION_HEIGHT: f32 = 60.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Section {
    #[default]
    Appearance,
    Speech,
    Access,
    Pronunciation,
    Backup,
    Screens,
}

impl Section {
    pub const ALL: [Self; 6] = [
        Self::Appearance,
        Self::Speech,
        Self::Access,
        Self::Pronunciation,
        Self::Backup,
        Self::Screens,
    ];

    fn title(self) -> &'static str {
        match self {
            Self::Appearance => "Appearance",
            Self::Speech => "Speech",
            Self::Access => "Access",
            Self::Pronunciation => "Pronunciation",
            Self::Backup => "Backup",
            Self::Screens => "Screens",
        }
    }

    fn summary(self) -> &'static str {
        match self {
            Self::Appearance => "Theme and colours",
            Self::Speech => "Voice and speaking speed",
            Self::Access => "Pointer dwell and Rest mode",
            Self::Pronunciation => "Teach Wingmate a word",
            Self::Backup => "Save or restore your data",
            Self::Screens => "Make one, or open a file",
        }
    }
}

pub fn view<'a>(
    section: Section,
    settings: &'a Settings,
    pronunciations: &'a [Pronunciation],
    word: &'a str,
    replacement: &'a str,
    recents: &'a [String],
    gaze: (&'a crate::gaze::setup::Setup, bool),
) -> Element<'a, Message> {
    row![
        sidebar(section),
        scrollable(
            column![
                text(section.title()).size(32),
                match section {
                    Section::Appearance => appearance(settings),
                    Section::Speech => speech(settings),
                    Section::Access => column![access(settings), gaze_settings(gaze.0, gaze.1)]
                        .spacing(24)
                        .into(),
                    Section::Pronunciation => pronunciation(pronunciations, word, replacement),
                    Section::Backup => backup(),
                    Section::Screens => screens(recents),
                },
            ]
            .spacing(20)
            .padding(32)
            .width(Fill),
        )
        .width(Fill)
        .height(Fill),
    ]
    .height(Fill)
    .into()
}

fn sidebar(section: Section) -> Element<'static, Message> {
    let mut nav = column![
        button(text("←  Back").size(21))
            .width(Fill)
            .height(ACTION_HEIGHT)
            .padding([12, 18])
            .style(button::text)
            .on_press(Message::CloseSettings),
    ]
    .spacing(10)
    .padding(16)
    .width(NAV_WIDTH);
    for item in Section::ALL {
        // Each `fn` item has its own type, so the branches need a shared
        // function-pointer type before they can meet in one expression.
        let style: fn(&Theme, button::Status) -> button::Style = if item == section {
            button::primary
        } else {
            button::secondary
        };
        nav = nav.push(
            button(column![text(item.title()).size(21), text(item.summary()).size(14),].spacing(4))
                .width(Fill)
                .height(NAV_ITEM_HEIGHT)
                .padding([12, 18])
                .style(style)
                .on_press(Message::SelectSettingsSection(item)),
        );
    }
    container(nav)
        .height(Fill)
        .style(container::rounded_box)
        .into()
}

fn appearance(settings: &Settings) -> Element<'_, Message> {
    column![field(
        "Theme",
        pick_list(
            ThemeChoice::all(),
            Some(settings.theme.clone()),
            Message::ThemeChanged,
        )
        .text_size(19)
        .padding(14)
        .width(Fill),
    )]
    .into()
}

fn speech(settings: &Settings) -> Element<'_, Message> {
    column![
        field(
            "System voice",
            text_input("default", &settings.voice)
                .on_input(Message::VoiceChanged)
                .size(19)
                .padding(14),
        ),
        field(
            format!("Speaking speed — {:.1}×", settings.speech_rate),
            slider(0.5..=2.0, settings.speech_rate, Message::RateChanged)
                .step(0.1_f32)
                .width(Fill),
        ),
    ]
    .into()
}

fn pronunciation<'a>(
    entries: &'a [Pronunciation],
    word: &'a str,
    replacement: &'a str,
) -> Element<'a, Message> {
    let mut content = column![
        text("Wingmate speaks the replacement instead of the word.").size(16),
        row![
            text_input("Word", word)
                .on_input(Message::PronunciationWordChanged)
                .size(19)
                .padding(14)
                .width(Fill),
            text_input("Speak as", replacement)
                .on_input(Message::PronunciationReplacementChanged)
                .size(19)
                .padding(14)
                .width(Fill),
            button(text("Add").size(19))
                .height(ACTION_HEIGHT)
                .padding([12, 24])
                .on_press(Message::AddPronunciation),
        ]
        .spacing(12)
        .align_y(iced::Center),
    ]
    .spacing(20);
    if entries.is_empty() {
        content = content.push(text("No words added yet.").size(16));
    }
    for entry in entries {
        content = content.push(
            row![
                text(format!("{} → {}", entry.word, entry.phoneme))
                    .size(19)
                    .width(Fill),
                button(text("Delete").size(19))
                    .height(ACTION_HEIGHT)
                    .padding([12, 24])
                    .style(button::danger)
                    .on_press(Message::DeletePronunciation(entry.word.clone())),
            ]
            .spacing(16)
            .align_y(iced::Center),
        );
    }
    content.into()
}

fn backup() -> Element<'static, Message> {
    column![
        text("A backup holds your Screens, settings, and pronunciations.").size(16),
        row![
            button(text("Create backup").size(19))
                .height(ACTION_HEIGHT)
                .padding([12, 24])
                .on_press(Message::ExportBackup),
            button(text("Restore backup").size(19))
                .height(ACTION_HEIGHT)
                .padding([12, 24])
                .style(button::secondary)
                .on_press(Message::RestoreBackup),
        ]
        .spacing(12),
    ]
    .spacing(20)
    .into()
}

fn screens(recents: &[String]) -> Element<'_, Message> {
    let mut content = column![
        text("Make a new Screen, or open one from an Open Board Format file.").size(16),
        row![
            action("New Screen", button::primary)
                .on_press(Message::Editor(crate::editor::Event::New)),
            action("Open OBF or OBZ file", button::secondary).on_press(Message::ChooseBoardFile),
        ]
        .spacing(12),
        text("Recent files").size(21),
    ]
    .spacing(20);
    if recents.is_empty() {
        content = content.push(text("No files opened yet.").size(16));
    }
    for path in recents {
        content = content.push(
            action(path.as_str(), button::secondary)
                .width(Fill)
                .on_press(Message::ImportFile(path.clone())),
        );
    }
    content.into()
}

/// A settings action button, sized like every other target on this screen.
fn action(
    label: &str,
    style: fn(&Theme, button::Status) -> button::Style,
) -> button::Button<'_, Message> {
    button(text(label).size(19))
        .height(ACTION_HEIGHT)
        .padding([12, 24])
        .style(style)
}

/// One settings row: label on the left, a width-capped control on the right,
/// the way desktop settings panes on every OS are laid out.
fn field<'a>(
    label: impl Into<String>,
    control: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    row![
        text(label.into()).size(19).width(Fill),
        container(control).width(CONTROL_WIDTH),
    ]
    .spacing(24)
    .align_y(iced::Center)
    .into()
}

fn access(settings: &Settings) -> Element<'_, Message> {
    column![
        text("Dwell selects communication buttons while the pointer rests on them. Zero turns dwell off. Use your operating system's eye or head pointer setup to move the pointer."),
        field(format!("Dwell duration: {} ms", settings.dwell_to_select_millis),
            slider(0..=5000_u32, settings.dwell_to_select_millis as u32, Message::DwellChanged).step(100_u32)),
        field(format!("Delay before dwell starts: {} ms", settings.dwell_rearm_delay_millis),
            slider(0..=2000_u32, settings.dwell_rearm_delay_millis as u32, Message::RearmChanged).step(20_u32)),
        field("Select key", text_input("e.g. F8, Space, Enter", &settings.select_key_binding).on_input(Message::SelectKeyChanged).padding(14)),
        field("Rest mode key", text_input("e.g. F9", &settings.rest_mode_key_binding).on_input(Message::RestKeyChanged).padding(14)),
        text("Leave shortcuts empty to disable them. Rest pauses dwell and select-key activation. Click or touch Resume input to continue, or hold the select key for two seconds and release."),
    ].spacing(24).into()
}

fn gaze_settings(setup: &crate::gaze::setup::Setup, can_start: bool) -> Element<'_, Message> {
    use iced::widget::checkbox;
    if !setup.visible() {
        return column![].into();
    }
    let mut content = column![
        text("Gaze input (Linux)").size(24),
        checkbox(setup.use_webcam)
            .label("Use webcam eye tracking (experimental)")
            .on_toggle(Message::WebcamEnabled),
    ]
    .spacing(16);
    if setup.use_webcam {
        content = content.extend([
            iced::widget::pick_list(setup.cameras.clone(), setup.camera.clone(), Message::WebcamCamera)
                .placeholder("Choose a webcam").into(),
            text("Camera frames stay on this computer. Setup takes about a minute. Look at each timed target, then press Enter to begin selection if validation passes. Esc cancels at any time.").into(),
            text("Use one display, fullscreen, and large targets. Recalibrate after moving yourself, the camera, or the display. Camera capture stops when gaze stops or Wingmate loses focus.").into(),
            text("First-time setup: bash scripts/install-webcam-gaze.sh").into(),
        ]);
        if setup.cameras.is_empty() {
            content = content.push(text(
                "No webcam found. Connect a camera; this list refreshes automatically.",
            ));
        }
    } else {
        content = content.extend([
            text(if setup.tracker { "Tobii tracker detected" } else { "No supported USB tracker detected" }).into(),
            text(if setup.reachable { "Daemon socket available; use diagnostics to check streaming." } else { "Gaze daemon unavailable. Enable startup below, or run: tobiifreed" }).into(),
            text("For USB access, run: bash scripts/install-wingmate.sh --setup-gaze. Reconnect the tracker afterward. Firmware and calibration must already be prepared.").into(),
            checkbox(setup.autostart).label("Start tobiifreed with Wingmate").on_toggle(Message::GazeAutostart).into(),
            button("Retry bundled daemon").height(ACTION_HEIGHT).on_press(Message::GazeRetry).into(),
            checkbox(setup.diagnostics.is_some()).label("Show live diagnostics (memory only)").on_toggle(Message::GazeDiagnostics).into(),
        ]);
    }
    content = content.push(text("Gaze uses the dwell duration above. Start from an open Screen; leaving communication or losing focus stops selection."))
        .push(button("Enable gaze and return to Screen (fullscreen)").height(ACTION_HEIGHT)
            .on_press_maybe((can_start && (!setup.use_webcam || setup.camera.is_some())).then_some(Message::ToggleGaze)));
    if let Some(error) = setup.error {
        content = content.push(text(error));
    }
    if let Some(source) = &setup.diagnostics {
        let snapshot = source.snapshot();
        let status = match snapshot.point(std::time::Instant::now()) {
            Some(point) => format!("Streaming: x {:.3}, y {:.3}", point.x, point.y),
            None => match snapshot.status {
                crate::gaze::Status::DaemonUnavailable => {
                    "Daemon unavailable. Check USB access and retry the daemon."
                }
                crate::gaze::Status::IncompatibleProtocol => {
                    "Gaze protocol incompatible. Use the bundled daemon version."
                }
                crate::gaze::Status::Connecting => "Connecting to the gaze stream…",
                _ => "Gaze lost or stale. Look at the display to check tracking.",
            }
            .into(),
        };
        content = content.push(text(status));
    }
    content.into()
}
