use iced::Theme;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BoardSet {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Cell {
    pub id: String,
    pub row: usize,
    pub column: usize,
    pub row_span: u16,
    pub column_span: u16,
    pub label: String,
    pub vocalization: String,
    pub image: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BoardView {
    pub title: String,
    pub rows: usize,
    pub columns: usize,
    pub cells: Vec<Cell>,
    pub message: String,
    pub show_message_bar: bool,
    pub show_speak_button: bool,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Activation {
    pub view: BoardView,
    pub speech: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub theme: ThemeChoice,
    #[serde(default)]
    pub prefers_dark: Option<bool>,
    pub voice: String,
    pub speech_rate: f32,
    pub hold_to_select_millis: u64,
    pub dwell_to_select_millis: u64,
}

/// Every theme iced ships, plus following the operating system. Persisted by
/// display name so the list grows with iced instead of a hand-kept table.
#[derive(Clone, Debug, Default, PartialEq)]
pub enum ThemeChoice {
    #[default]
    System,
    Named(Theme),
}

impl ThemeChoice {
    const SYSTEM: &'static str = "system";

    pub fn all() -> Vec<Self> {
        std::iter::once(Self::System)
            .chain(Theme::ALL.iter().cloned().map(Self::Named))
            .collect()
    }

    /// `None` for System, so the shared Kotlin `forceDarkTheme` keeps meaning
    /// something for the other clients whichever palette desktop picks.
    pub fn prefers_dark(&self) -> Option<bool> {
        match self {
            Self::System => None,
            Self::Named(theme) => Some(theme.extended_palette().is_dark),
        }
    }

    pub fn resolve(&self, system: &Theme) -> Theme {
        match self {
            Self::System => system.clone(),
            Self::Named(theme) => theme.clone(),
        }
    }

    fn key(&self) -> String {
        match self {
            Self::System => Self::SYSTEM.to_owned(),
            Self::Named(theme) => theme.to_string(),
        }
    }

    fn from_key(key: &str) -> Self {
        // "light"/"dark" are what the light-and-dark-only contract persisted.
        match key {
            Self::SYSTEM => return Self::System,
            "light" => return Self::Named(Theme::Light),
            "dark" => return Self::Named(Theme::Dark),
            _ => {}
        }
        Theme::ALL
            .iter()
            .find(|theme| theme.to_string() == key)
            .cloned()
            .map(Self::Named)
            .unwrap_or_default()
    }
}

impl std::fmt::Display for ThemeChoice {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::System => formatter.write_str("System"),
            Self::Named(theme) => theme.fmt(formatter),
        }
    }
}

impl Serialize for ThemeChoice {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.key())
    }
}

impl<'de> Deserialize<'de> for ThemeChoice {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Ok(Self::from_key(&String::deserialize(deserializer)?))
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Pronunciation {
    pub word: String,
    pub phoneme: String,
    #[serde(default = "text_alphabet")]
    pub alphabet: String,
}

fn text_alphabet() -> String {
    "text".into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_iced_theme_is_offered_and_round_trips_by_name() {
        let all = ThemeChoice::all();
        assert_eq!(all.len(), iced::Theme::ALL.len() + 1);
        assert_eq!(all[0], ThemeChoice::System);
        for choice in all {
            let encoded = serde_json::to_string(&choice).unwrap();
            assert_eq!(serde_json::from_str::<ThemeChoice>(&encoded).unwrap(), choice);
        }
    }

    #[test]
    fn the_light_and_dark_only_contract_still_loads() {
        assert_eq!(
            serde_json::from_str::<ThemeChoice>("\"dark\"").unwrap(),
            ThemeChoice::Named(iced::Theme::Dark),
        );
        assert_eq!(
            serde_json::from_str::<ThemeChoice>("\"light\"").unwrap(),
            ThemeChoice::Named(iced::Theme::Light),
        );
        assert_eq!(
            serde_json::from_str::<ThemeChoice>("\"system\"").unwrap(),
            ThemeChoice::System,
        );
        // An unknown palette must fall back rather than fail to load settings.
        assert_eq!(
            serde_json::from_str::<ThemeChoice>("\"Retired Theme\"").unwrap(),
            ThemeChoice::System,
        );
    }

    #[test]
    fn darkness_is_reported_for_named_palettes_only() {
        assert_eq!(ThemeChoice::System.prefers_dark(), None);
        assert_eq!(
            ThemeChoice::Named(iced::Theme::Dracula).prefers_dark(),
            Some(true),
        );
        assert_eq!(
            ThemeChoice::Named(iced::Theme::SolarizedLight).prefers_dark(),
            Some(false),
        );
    }

    #[test]
    fn kotlin_activation_contract_deserializes() {
        let value: Activation = serde_json::from_str(
            r##"{
            "view":{"boardSetId":"set","boardId":"page","title":"Home","rows":1,"columns":1,
            "cells":[{"id":"yes","row":0,"column":0,"rowSpan":1,"columnSpan":1,
            "label":"Yes","vocalization":"Yes","backgroundColor":"#ffffff","image":null}],
            "message":"Yes","showMessageBar":true,"showSpeakButton":true},"speech":"Yes"}"##,
        )
        .unwrap();
        assert_eq!(value.view.cells[0].label, "Yes");
        assert_eq!(value.speech.as_deref(), Some("Yes"));
    }
}
