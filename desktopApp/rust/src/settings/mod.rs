use crate::{
    Message,
    models::{Pronunciation, Settings, ThemeChoice},
};
use iced::widget::{
    button, column, container, pick_list, row, scrollable, slider, text, text_input,
};

// Hold-to-select and dwell-to-select are stored and round-tripped for the
// Communicator's other clients, but desktop has no runner support for them
// yet, so this screen does not offer controls that would do nothing. See
// docs/ACCESSIBILITY_MATRIX.md and issue #268.
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
    Pronunciation,
    Backup,
}

impl Section {
    pub const ALL: [Self; 4] = [
        Self::Appearance,
        Self::Speech,
        Self::Pronunciation,
        Self::Backup,
    ];

    fn title(self) -> &'static str {
        match self {
            Self::Appearance => "Appearance",
            Self::Speech => "Speech",
            Self::Pronunciation => "Pronunciation",
            Self::Backup => "Backup",
        }
    }

    fn summary(self) -> &'static str {
        match self {
            Self::Appearance => "Theme and colours",
            Self::Speech => "Voice and speaking speed",
            Self::Pronunciation => "Teach Wingmate a word",
            Self::Backup => "Save or restore your data",
        }
    }
}

pub fn view<'a>(
    section: Section,
    settings: &'a Settings,
    pronunciations: &'a [Pronunciation],
    word: &'a str,
    replacement: &'a str,
) -> Element<'a, Message> {
    row![
        sidebar(section),
        scrollable(
            column![
                text(section.title()).size(32),
                match section {
                    Section::Appearance => appearance(settings),
                    Section::Speech => speech(settings),
                    Section::Pronunciation => pronunciation(pronunciations, word, replacement),
                    Section::Backup => backup(),
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
            button(
                column![
                    text(item.title()).size(21),
                    text(item.summary()).size(14),
                ]
                .spacing(4),
            )
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
