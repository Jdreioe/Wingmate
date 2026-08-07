use iced::widget::{
    button, checkbox, column, container, pick_list, row, scrollable, slider, text, text_input,
    Space,
};
use iced::{theme, Element, Fill, Subscription, Task, Theme};
use reqwest::{Client, Method};
use serde::Deserialize;
use std::env;
use std::path::PathBuf;
use std::process::{Child, Command};
use std::time::Duration;
use wingmate_kde::partner_window_bridge::{self, PartnerWindowController};

const DEFAULT_API_URL: &str = "http://127.0.0.1:8765";

fn main() -> iced::Result {
    ctrlc::set_handler(|| {
        partner_window_bridge::send_global_shutdown();
        std::process::exit(0);
    })
    .expect("failed to install signal handler");

    iced::application(Wingmate::boot, Wingmate::update, Wingmate::view)
        .title("Wingmate")
        .subscription(Wingmate::subscription)
        .theme(Wingmate::theme)
        .window_size((1280.0, 800.0))
        .antialiasing(true)
        .run()
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Phrase {
    id: String,
    text: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    background_color: Option<String>,
    #[serde(default)]
    image_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct Category {
    id: String,
    #[serde(default)]
    name: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Voice {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    supported_languages: Option<Vec<String>>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct Settings {
    language: String,
    voice: String,
    speech_rate: f32,
    tts_engine: String,
    force_dark_theme: Option<bool>,
    partner_window_enabled: bool,
    partner_window_font_size: i32,
    partner_window_idle_enabled: bool,
    welcome_flow_completed: bool,
    startup_mode: String,
    feature_usage_reporting_enabled: bool,
    primary_language: String,
    secondary_language: String,
    font_size_scale: f32,
    button_scale: f32,
    input_field_scale: f32,
    show_labels: bool,
    show_symbols: bool,
    label_at_top: bool,
    grid_columns: i32,
    high_contrast_mode: bool,
    hold_to_select_millis: i64,
    dwell_to_select_millis: i64,
    selection_sound_enabled: bool,
    auditory_fishing_enabled: bool,
    selection_highlight_millis: i64,
    selection_debounce_millis: i64,
    startup_board_set_id: Option<String>,
    scanning_enabled: bool,
    scan_playback_area_enabled: bool,
    scan_input_field_enabled: bool,
    scan_phrase_grid_enabled: bool,
    scan_category_items_enabled: bool,
    scan_top_bar_enabled: bool,
    scan_phrase_grid_order: String,
    scan_dwell_time_seconds: f32,
    scan_auto_advance_seconds: f32,
    usage_logging_enabled: bool,
    history_visible: bool,
    board_show_message_bar: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            language: "en-US".into(),
            voice: "default".into(),
            speech_rate: 1.0,
            tts_engine: "SYSTEM".into(),
            force_dark_theme: None,
            partner_window_enabled: false,
            partner_window_font_size: 31,
            partner_window_idle_enabled: true,
            welcome_flow_completed: false,
            startup_mode: "Keyboard".into(),
            feature_usage_reporting_enabled: false,
            primary_language: "en-US".into(),
            secondary_language: String::new(),
            font_size_scale: 1.0,
            button_scale: 1.0,
            input_field_scale: 1.0,
            show_labels: true,
            show_symbols: true,
            label_at_top: false,
            grid_columns: 3,
            high_contrast_mode: false,
            hold_to_select_millis: 0,
            dwell_to_select_millis: 0,
            selection_sound_enabled: false,
            auditory_fishing_enabled: false,
            selection_highlight_millis: 0,
            selection_debounce_millis: 0,
            startup_board_set_id: None,
            scanning_enabled: false,
            scan_playback_area_enabled: true,
            scan_input_field_enabled: true,
            scan_phrase_grid_enabled: true,
            scan_category_items_enabled: true,
            scan_top_bar_enabled: true,
            scan_phrase_grid_order: "row-major".into(),
            scan_dwell_time_seconds: 1.0,
            scan_auto_advance_seconds: 1.2,
            usage_logging_enabled: false,
            history_visible: true,
            board_show_message_bar: true,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
struct Pronunciation {
    word: String,
    phoneme: String,
}

#[derive(Debug, Clone, Deserialize)]
struct Predictions {
    #[serde(default)]
    words: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct InsertionResult {
    #[serde(default)]
    insertion: String,
}

#[derive(Debug, Clone, Deserialize)]
struct AzureConfig {
    endpoint: String,
    key: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HistoryEntry {
    #[serde(default)]
    said_text: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BoardSet {
    id: String,
    name: String,
    root_board_id: String,
    #[serde(default)]
    board_ids: Vec<String>,
    #[serde(default)]
    is_locked: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BoardGraph {
    board_set: BoardSet,
    boards: Vec<Board>,
}

#[derive(Debug, Clone, Deserialize)]
struct Board {
    id: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    buttons: Vec<BoardButton>,
    #[serde(default)]
    grid: Option<BoardGrid>,
}

#[derive(Debug, Clone, Deserialize)]
struct BoardGrid {
    rows: usize,
    columns: usize,
    order: Vec<Vec<Option<String>>>,
}

#[derive(Debug, Clone, Deserialize)]
struct BoardButton {
    id: String,
    #[serde(default)]
    label: Option<String>,
    #[serde(default)]
    vocalization: Option<String>,
    #[serde(default)]
    background_color: Option<String>,
    #[serde(default)]
    load_board: Option<BoardLink>,
}

#[derive(Debug, Clone, Deserialize)]
struct BoardLink {
    #[serde(default)]
    id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Page {
    Welcome,
    Communicate,
    Screens,
    Dictionary,
    Settings,
    Fullscreen,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SettingsCategory {
    Speech,
    Display,
    Access,
    Startup,
    Privacy,
    Partner,
}

struct BackendProcess(Option<Child>);

impl Drop for BackendProcess {
    fn drop(&mut self) {
        if let Some(child) = &mut self.0 {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

struct Wingmate {
    api: Api,
    _backend: BackendProcess,
    partner: PartnerWindowController,
    page: Page,
    draft: String,
    phrases: Vec<Phrase>,
    categories: Vec<Category>,
    voices: Vec<Voice>,
    pronunciations: Vec<Pronunciation>,
    predictions: Vec<String>,
    history: Vec<HistoryEntry>,
    board_sets: Vec<BoardSet>,
    board_graph: Option<BoardGraph>,
    active_board_id: Option<String>,
    board_edit_mode: bool,
    onboarding_step: u8,
    onboarding_analytics: bool,
    onboarding_screens: bool,
    selected_category: Option<String>,
    settings: Settings,
    system_theme: theme::Mode,
    settings_category: SettingsCategory,
    new_phrase: String,
    new_category: String,
    new_word: String,
    new_phoneme: String,
    thought_draft: Option<String>,
    editing_phrase_id: Option<String>,
    phrase_editor_text: String,
    phrase_editor_voice: String,
    new_board_set: String,
    new_page: String,
    board_rows: i32,
    board_columns: i32,
    calculator_template: bool,
    editing_cell: Option<(usize, usize)>,
    cell_label: String,
    cell_vocalization: String,
    pending_prediction_word: Option<String>,
    azure_endpoint: String,
    azure_key: String,
    status: String,
}

#[derive(Debug, Clone)]
enum Message {
    Navigate(Page),
    DraftChanged(String),
    PredictionSelected(String),
    PredictionInsertionLoaded(Result<InsertionResult, String>),
    LoadedPhrases(Result<Vec<Phrase>, String>),
    LoadedCategories(Result<Vec<Category>, String>),
    LoadedVoices(Result<Vec<Voice>, String>),
    LoadedSettings(Result<Settings, String>),
    LoadedDictionary(Result<Vec<Pronunciation>, String>),
    LoadedPredictions(Result<Predictions, String>),
    LoadedAzureConfig(Result<AzureConfig, String>),
    LoadedHistory(Result<Vec<HistoryEntry>, String>),
    LoadedBoardSets(Result<Vec<BoardSet>, String>),
    LoadedBoardGraph(Result<BoardGraph, String>),
    SelectCategory(Option<String>),
    CategorySelected(Result<(), String>),
    Speak(String),
    SpeechAction(&'static str),
    ActionFinished(Result<(), String>),
    NewPhraseChanged(String),
    AddPhrase,
    DeletePhrase(String),
    EditPhrase(String),
    PhraseEditorChanged(String),
    PhraseEditorVoiceChanged(String),
    SavePhraseEdit,
    CancelPhraseEdit,
    NewCategoryChanged(String),
    AddCategory,
    DeleteCategory(String),
    VoiceSelected(String),
    RateChanged(f32),
    EngineChanged(String),
    AzureEndpointChanged(String),
    AzureKeyChanged(String),
    SaveAzureConfig,
    PrimaryLanguageChanged(String),
    SecondaryLanguageChanged(String),
    ThemeChanged(String),
    SystemThemeChanged(theme::Mode),
    SelectSettingsCategory(SettingsCategory),
    PartnerEnabled(bool),
    PartnerFontChanged(i32),
    PartnerIdleChanged(bool),
    SettingBool(&'static str, bool),
    FontScaleChanged(f32),
    ButtonScaleChanged(f32),
    InputScaleChanged(f32),
    GridColumnsChanged(i32),
    HoldChanged(i64),
    DwellChanged(i64),
    SelectionHighlightChanged(i64),
    SelectionDebounceChanged(i64),
    ScanDwellChanged(f32),
    ScanAutoAdvanceChanged(f32),
    ScanOrderChanged(String),
    StartupBoardSetChanged(String),
    StartupModeChanged(String),
    NewWordChanged(String),
    NewPhonemeChanged(String),
    AddPronunciation,
    DeletePronunciation(String),
    ClearHistory,
    ImportHistory,
    ExportHistory,
    AppendMarkup(&'static str),
    ToggleThought,
    OnboardingNext,
    OnboardingBack,
    OnboardingAnalytics(bool),
    OnboardingMode(bool),
    CompleteOnboarding,
    OpenBoardSet(String, bool),
    ExitBoardSet,
    BoardSetNameChanged(String),
    PageNameChanged(String),
    BoardRowsChanged(i32),
    BoardColumnsChanged(i32),
    CalculatorTemplate(bool),
    CreateBoardSet,
    ImportBoardSet,
    ExportBoardSet(String, String),
    DuplicateBoardSet(String),
    ToggleBoardSetLock(String),
    DeleteBoardSet(String),
    CreatePage,
    SelectBoard(String),
    ToggleBoardEdit,
    SelectBoardCell(usize, usize),
    CellLabelChanged(String),
    CellVoiceChanged(String),
    SaveBoardCell,
    ClearBoardCell,
    CancelBoardCell,
    Refresh,
}

impl Wingmate {
    fn boot() -> (Self, Task<Message>) {
        let api = Api::new();
        let backend = BackendProcess(start_bridge_server());
        let mut partner = PartnerWindowController::default();
        partner.start();

        let state = Self {
            api: api.clone(),
            _backend: backend,
            partner,
            page: Page::Welcome,
            draft: String::new(),
            phrases: vec![],
            categories: vec![],
            voices: vec![],
            pronunciations: vec![],
            predictions: vec![],
            history: vec![],
            board_sets: vec![],
            board_graph: None,
            active_board_id: None,
            board_edit_mode: false,
            onboarding_step: 0,
            onboarding_analytics: false,
            onboarding_screens: false,
            selected_category: None,
            settings: Settings::default(),
            system_theme: theme::Mode::None,
            settings_category: SettingsCategory::Speech,
            new_phrase: String::new(),
            new_category: String::new(),
            new_word: String::new(),
            new_phoneme: String::new(),
            thought_draft: None,
            editing_phrase_id: None,
            phrase_editor_text: String::new(),
            phrase_editor_voice: String::new(),
            new_board_set: String::new(),
            new_page: String::new(),
            board_rows: 4,
            board_columns: 4,
            calculator_template: false,
            editing_cell: None,
            cell_label: String::new(),
            cell_vocalization: String::new(),
            pending_prediction_word: None,
            azure_endpoint: String::new(),
            azure_key: String::new(),
            status: "Starting Wingmate services…".into(),
        };

        (
            state,
            Task::batch([
                api.bootstrap(),
                iced::system::theme().map(Message::SystemThemeChanged),
            ]),
        )
    }

    fn theme(&self) -> Theme {
        match self.settings.force_dark_theme {
            Some(true) => Theme::Dark,
            Some(false) => Theme::Light,
            None => match self.system_theme {
                theme::Mode::Dark => Theme::Dark,
                theme::Mode::None | theme::Mode::Light => Theme::Light,
            },
        }
    }

    fn subscription(&self) -> Subscription<Message> {
        iced::system::theme_changes().map(Message::SystemThemeChanged)
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::Navigate(page) => {
                self.page = page;
                if page == Page::Dictionary {
                    return self.api.load_dictionary();
                }
                if page == Page::Screens {
                    self.board_graph = None;
                    return self.api.load_board_sets();
                }
                if page == Page::Communicate && self.settings.history_visible {
                    return self.api.load_history();
                }
            }
            Message::DraftChanged(value) => {
                self.draft = value.clone();
                self.partner.update_text(value.clone());
                return self.api.predict(value);
            }
            Message::PredictionSelected(word) => {
                self.pending_prediction_word = Some(word.clone());
                let api = self.api.clone();
                let draft = self.draft.clone();
                return Task::perform(
                    async move {
                        api.request_json(
                            Method::POST,
                            "/api/predict/insert",
                            Some(serde_json::json!({"sentence": draft, "suggestion": word})),
                        )
                        .await
                    },
                    Message::PredictionInsertionLoaded,
                );
            }
            Message::PredictionInsertionLoaded(result) => {
                if let Some(_word) = self.pending_prediction_word.take() {
                    let insertion = result.map(|r| r.insertion).unwrap_or_default();
                    self.draft = format!("{}{} ", self.draft, insertion);
                    self.partner.update_text(self.draft.clone());
                    return self.api.learn(self.draft.clone());
                }
            }
            Message::LoadedPhrases(result) => match result {
                Ok(v) => self.phrases = v,
                Err(e) => self.status = e,
            },
            Message::LoadedCategories(result) => match result {
                Ok(v) => self.categories = v,
                Err(e) => self.status = e,
            },
            Message::LoadedVoices(result) => match result {
                Ok(v) => self.voices = v,
                Err(e) => self.status = e,
            },
            Message::LoadedSettings(result) => match result {
                Ok(v) => {
                    self.partner.set_enabled(v.partner_window_enabled);
                    self.partner.set_font_size(v.partner_window_font_size);
                    self.partner.set_idle_enabled(v.partner_window_idle_enabled);
                    if self.page == Page::Welcome && v.welcome_flow_completed {
                        self.page = if v.startup_mode == "Screens" {
                            Page::Screens
                        } else {
                            Page::Communicate
                        };
                    }
                    self.settings = v;
                    self.status = "Ready".into();
                }
                Err(e) => self.status = e,
            },
            Message::LoadedDictionary(result) => match result {
                Ok(v) => self.pronunciations = v,
                Err(e) => self.status = e,
            },
            Message::LoadedPredictions(result) => {
                if let Ok(v) = result {
                    self.predictions = v.words
                }
            }
            Message::LoadedAzureConfig(result) => match result {
                Ok(config) => {
                    self.azure_endpoint = config.endpoint;
                    self.azure_key = config.key;
                }
                Err(e) => self.status = e,
            },
            Message::LoadedHistory(result) => match result {
                Ok(v) => self.history = v,
                Err(e) => self.status = e,
            },
            Message::LoadedBoardSets(result) => match result {
                Ok(v) => self.board_sets = v,
                Err(e) => self.status = e,
            },
            Message::LoadedBoardGraph(result) => match result {
                Ok(graph) => {
                    self.active_board_id = Some(graph.board_set.root_board_id.clone());
                    self.board_graph = Some(graph);
                }
                Err(e) => self.status = e,
            },
            Message::SelectCategory(id) => {
                self.selected_category = id.clone();
                return self.api.select_category(id);
            }
            Message::CategorySelected(result) => {
                if let Err(e) = result {
                    self.status = e;
                }
                return self.api.load_phrases();
            }
            Message::Speak(text) => {
                if text.trim().is_empty() {
                    return Task::none();
                }
                self.partner.update_text(text.clone());
                self.status = "Speaking…".into();
                return self.api.speak(text);
            }
            Message::SpeechAction(action) => return self.api.empty_post(action),
            Message::ActionFinished(result) => {
                self.status = result.map(|_| "Ready".to_string()).unwrap_or_else(|e| e);
            }
            Message::NewPhraseChanged(v) => self.new_phrase = v,
            Message::AddPhrase => {
                let value = std::mem::take(&mut self.new_phrase);
                if !value.trim().is_empty() {
                    return self.api.add_phrase(value);
                }
            }
            Message::DeletePhrase(id) => return self.api.delete_phrase(id),
            Message::EditPhrase(id) => {
                if let Some(phrase) = self.phrases.iter().find(|p| p.id == id) {
                    self.editing_phrase_id = Some(id);
                    self.phrase_editor_text = phrase.text.clone();
                    self.phrase_editor_voice = phrase.name.clone().unwrap_or_default();
                }
            }
            Message::PhraseEditorChanged(v) => self.phrase_editor_text = v,
            Message::PhraseEditorVoiceChanged(v) => self.phrase_editor_voice = v,
            Message::SavePhraseEdit => {
                if let Some(id) = self.editing_phrase_id.take() {
                    return self.api.update_phrase(
                        id,
                        self.phrase_editor_text.clone(),
                        self.phrase_editor_voice.clone(),
                    );
                }
            }
            Message::CancelPhraseEdit => self.editing_phrase_id = None,
            Message::NewCategoryChanged(v) => self.new_category = v,
            Message::AddCategory => {
                let value = std::mem::take(&mut self.new_category);
                if !value.trim().is_empty() {
                    return self.api.add_category(value);
                }
            }
            Message::DeleteCategory(id) => return self.api.delete_category(id),
            Message::VoiceSelected(voice) => {
                self.settings.voice = voice.clone();
                return self
                    .api
                    .put_json("/api/settings/voice", serde_json::json!({"voice": voice}));
            }
            Message::RateChanged(rate) => {
                self.settings.speech_rate = rate;
                return self
                    .api
                    .put_json("/api/settings/rate", serde_json::json!({"rate": rate}));
            }
            Message::EngineChanged(engine) => {
                self.settings.tts_engine = engine.clone();
                return self.api.put_json(
                    "/api/settings/systemtts",
                    serde_json::json!({"ttsEngine": engine}),
                );
            }
            Message::AzureEndpointChanged(value) => self.azure_endpoint = value,
            Message::AzureKeyChanged(value) => self.azure_key = value,
            Message::SaveAzureConfig => {
                return self
                    .api
                    .save_azure_config(self.azure_endpoint.clone(), self.azure_key.clone())
            }
            Message::PrimaryLanguageChanged(language) => {
                self.settings.primary_language = language.clone();
                self.settings.language = language.clone();
                return self.api.put_json(
                    "/api/settings",
                    serde_json::json!({"primaryLanguage": language}),
                );
            }
            Message::SecondaryLanguageChanged(language) => {
                self.settings.secondary_language = if language == "Disabled" {
                    String::new()
                } else {
                    language.clone()
                };
                return self.api.put_json(
                    "/api/settings",
                    serde_json::json!({"secondaryLanguage": self.settings.secondary_language}),
                );
            }
            Message::ThemeChanged(theme) => {
                self.settings.force_dark_theme = match theme.as_str() {
                    "Dark" => Some(true),
                    "Light" => Some(false),
                    _ => None,
                };
                return self.api.put_json(
                    "/api/settings",
                    serde_json::json!({"forceDarkTheme": self.settings.force_dark_theme}),
                );
            }
            Message::SystemThemeChanged(mode) => self.system_theme = mode,
            Message::SelectSettingsCategory(category) => self.settings_category = category,
            Message::PartnerEnabled(enabled) => {
                self.settings.partner_window_enabled = enabled;
                self.partner.set_enabled(enabled);
                return self.api.put_json(
                    "/api/settings/partnerwindow",
                    serde_json::json!({"enabled": enabled}),
                );
            }
            Message::PartnerFontChanged(font) => {
                self.settings.partner_window_font_size = font;
                self.partner.set_font_size(font);
                return self.api.partner_display(&self.settings);
            }
            Message::PartnerIdleChanged(enabled) => {
                self.settings.partner_window_idle_enabled = enabled;
                self.partner.set_idle_enabled(enabled);
                return self.api.partner_display(&self.settings);
            }
            Message::SettingBool(key, enabled) => {
                match key {
                    "featureUsageReportingEnabled" => {
                        self.settings.feature_usage_reporting_enabled = enabled
                    }
                    "showLabels" => self.settings.show_labels = enabled,
                    "showSymbols" => self.settings.show_symbols = enabled,
                    "labelAtTop" => self.settings.label_at_top = enabled,
                    "highContrastMode" => self.settings.high_contrast_mode = enabled,
                    "selectionSoundEnabled" => self.settings.selection_sound_enabled = enabled,
                    "auditoryFishingEnabled" => self.settings.auditory_fishing_enabled = enabled,
                    "usageLoggingEnabled" => self.settings.usage_logging_enabled = enabled,
                    "historyVisible" => self.settings.history_visible = enabled,
                    "boardShowMessageBar" => self.settings.board_show_message_bar = enabled,
                    "scanningEnabled" => self.settings.scanning_enabled = enabled,
                    "scanPlaybackAreaEnabled" => self.settings.scan_playback_area_enabled = enabled,
                    "scanInputFieldEnabled" => self.settings.scan_input_field_enabled = enabled,
                    "scanPhraseGridEnabled" => self.settings.scan_phrase_grid_enabled = enabled,
                    "scanCategoryItemsEnabled" => self.settings.scan_category_items_enabled = enabled,
                    "scanTopBarEnabled" => self.settings.scan_top_bar_enabled = enabled,
                    _ => {}
                }
                return self
                    .api
                    .patch_setting(key, serde_json::Value::Bool(enabled));
            }
            Message::FontScaleChanged(v) => {
                self.settings.font_size_scale = v;
                return self
                    .api
                    .patch_setting("fontSizeScale", serde_json::json!(v));
            }
            Message::ButtonScaleChanged(v) => {
                self.settings.button_scale = v;
                return self.api.patch_setting("buttonScale", serde_json::json!(v));
            }
            Message::InputScaleChanged(v) => {
                self.settings.input_field_scale = v;
                return self
                    .api
                    .patch_setting("inputFieldScale", serde_json::json!(v));
            }
            Message::GridColumnsChanged(v) => {
                self.settings.grid_columns = v;
                return self.api.patch_setting("gridColumns", serde_json::json!(v));
            }
            Message::HoldChanged(v) => {
                self.settings.hold_to_select_millis = v;
                return self
                    .api
                    .patch_setting("holdToSelectMillis", serde_json::json!(v));
            }
            Message::DwellChanged(v) => {
                self.settings.dwell_to_select_millis = v;
                return self
                    .api
                    .patch_setting("dwellToSelectMillis", serde_json::json!(v));
            }
            Message::SelectionHighlightChanged(v) => {
                self.settings.selection_highlight_millis = v;
                return self
                    .api
                    .patch_setting("selectionHighlightMillis", serde_json::json!(v));
            }
            Message::SelectionDebounceChanged(v) => {
                self.settings.selection_debounce_millis = v;
                return self
                    .api
                    .patch_setting("selectionDebounceMillis", serde_json::json!(v));
            }
            Message::ScanDwellChanged(v) => {
                self.settings.scan_dwell_time_seconds = v;
                return self
                    .api
                    .patch_setting("scanDwellTimeSeconds", serde_json::json!(v));
            }
            Message::ScanAutoAdvanceChanged(v) => {
                self.settings.scan_auto_advance_seconds = v;
                return self
                    .api
                    .patch_setting("scanAutoAdvanceSeconds", serde_json::json!(v));
            }
            Message::ScanOrderChanged(v) => {
                self.settings.scan_phrase_grid_order = v.clone();
                return self
                    .api
                    .patch_setting("scanPhraseGridOrder", serde_json::json!(v));
            }
            Message::StartupBoardSetChanged(v) => {
                self.settings.startup_board_set_id = Some(v.clone());
                return self
                    .api
                    .patch_setting("startupBoardSetId", serde_json::json!(v));
            }
            Message::StartupModeChanged(v) => {
                self.settings.startup_mode = v.clone();
                return self
                    .api
                    .patch_setting("startupMode", serde_json::json!(v));
            }
            Message::NewWordChanged(v) => self.new_word = v,
            Message::NewPhonemeChanged(v) => self.new_phoneme = v,
            Message::AddPronunciation => {
                let word = std::mem::take(&mut self.new_word);
                let phoneme = std::mem::take(&mut self.new_phoneme);
                if !word.trim().is_empty() && !phoneme.trim().is_empty() {
                    return self.api.add_pronunciation(word, phoneme);
                }
            }
            Message::DeletePronunciation(word) => return self.api.delete_pronunciation(word),
            Message::ClearHistory => return self.api.clear_history(),
            Message::ImportHistory => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("JSON", &["json"])
                    .pick_file()
                {
                    return self.api.import_history(path);
                }
            }
            Message::ExportHistory => {
                if let Some(path) = rfd::FileDialog::new()
                    .set_file_name("wingmate-history.json")
                    .add_filter("JSON", &["json"])
                    .save_file()
                {
                    return self.api.export_history(path);
                }
            }
            Message::AppendMarkup(markup) => {
                self.draft.push_str(markup);
                self.partner.update_text(self.draft.clone());
            }
            Message::ToggleThought => {
                if let Some(pinned) = self.thought_draft.take() {
                    let current = std::mem::replace(&mut self.draft, pinned);
                    self.thought_draft = Some(current);
                } else {
                    self.thought_draft = Some(std::mem::take(&mut self.draft));
                }
                self.partner.update_text(self.draft.clone());
            }
            Message::OnboardingNext => self.onboarding_step = (self.onboarding_step + 1).min(2),
            Message::OnboardingBack => {
                self.onboarding_step = self.onboarding_step.saturating_sub(1)
            }
            Message::OnboardingAnalytics(v) => self.onboarding_analytics = v,
            Message::OnboardingMode(screens) => self.onboarding_screens = screens,
            Message::CompleteOnboarding => {
                self.settings.welcome_flow_completed = true;
                self.settings.feature_usage_reporting_enabled = self.onboarding_analytics;
                self.settings.startup_mode = if self.onboarding_screens {
                    "Screens"
                } else {
                    "Keyboard"
                }
                .into();
                self.page = if self.onboarding_screens {
                    Page::Screens
                } else {
                    Page::Communicate
                };
                return self
                    .api
                    .complete_onboarding(self.onboarding_analytics, self.onboarding_screens);
            }
            Message::OpenBoardSet(id, edit) => {
                self.board_edit_mode = edit;
                return self.api.load_board_graph(id);
            }
            Message::ExitBoardSet => {
                self.board_graph = None;
                self.active_board_id = None;
                return self.api.load_board_sets();
            }
            Message::BoardSetNameChanged(v) => self.new_board_set = v,
            Message::PageNameChanged(v) => self.new_page = v,
            Message::BoardRowsChanged(v) => self.board_rows = v,
            Message::BoardColumnsChanged(v) => self.board_columns = v,
            Message::CalculatorTemplate(v) => self.calculator_template = v,
            Message::CreateBoardSet => {
                let name = std::mem::take(&mut self.new_board_set);
                if !name.trim().is_empty() {
                    return self.api.create_board_set(
                        name,
                        self.board_rows,
                        self.board_columns,
                        self.calculator_template,
                    );
                }
            }
            Message::ImportBoardSet => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("Open Board Format", &["obf", "obz", "json"])
                    .pick_file()
                {
                    return self
                        .api
                        .import_board_set(path.to_string_lossy().into_owned());
                }
            }
            Message::ExportBoardSet(id, name) => {
                if let Some(path) = rfd::FileDialog::new()
                    .set_file_name(format!("{}.obz", safe_filename(&name)))
                    .add_filter("Open Board Archive", &["obz"])
                    .save_file()
                {
                    return self.api.export_board_set(id, path);
                }
            }
            Message::DuplicateBoardSet(id) => {
                return self.api.board_set_action(id, "duplicate", Method::POST)
            }
            Message::ToggleBoardSetLock(id) => {
                return self.api.board_set_action(id, "lock", Method::PUT)
            }
            Message::DeleteBoardSet(id) => return self.api.delete_board_set(id),
            Message::CreatePage => {
                if let Some(graph) = &self.board_graph {
                    let name = std::mem::take(&mut self.new_page);
                    if !name.trim().is_empty() {
                        return self.api.create_board(
                            graph.board_set.id.clone(),
                            name,
                            self.board_rows,
                            self.board_columns,
                        );
                    }
                }
            }
            Message::SelectBoard(id) => self.active_board_id = Some(id),
            Message::ToggleBoardEdit => self.board_edit_mode = !self.board_edit_mode,
            Message::SelectBoardCell(row, column) => {
                self.editing_cell = Some((row, column));
                self.cell_label.clear();
                self.cell_vocalization.clear();
                if let Some((label, vocalization)) = self.board_cell(row, column).map(|button| {
                    (
                        button.label.clone().unwrap_or_default(),
                        button.vocalization.clone().unwrap_or_default(),
                    )
                }) {
                    self.cell_label = label;
                    self.cell_vocalization = vocalization;
                }
            }
            Message::CellLabelChanged(v) => self.cell_label = v,
            Message::CellVoiceChanged(v) => self.cell_vocalization = v,
            Message::SaveBoardCell => {
                if let (Some(graph), Some(board_id), Some((row, column))) = (
                    &self.board_graph,
                    &self.active_board_id,
                    self.editing_cell.take(),
                ) {
                    return self.api.save_board_cell(
                        graph.board_set.id.clone(),
                        board_id.clone(),
                        row,
                        column,
                        self.cell_label.clone(),
                        self.cell_vocalization.clone(),
                    );
                }
            }
            Message::ClearBoardCell => {
                if let (Some(graph), Some(board_id), Some((row, column))) = (
                    &self.board_graph,
                    &self.active_board_id,
                    self.editing_cell.take(),
                ) {
                    return self.api.clear_board_cell(
                        graph.board_set.id.clone(),
                        board_id.clone(),
                        row,
                        column,
                    );
                }
            }
            Message::CancelBoardCell => self.editing_cell = None,
            Message::Refresh => return self.api.bootstrap(),
        }
        Task::none()
    }

    fn view(&self) -> Element<'_, Message> {
        if self.page == Page::Welcome {
            return container(self.welcome_view())
                .padding(40)
                .center(Fill)
                .into();
        }
        if self.page == Page::Fullscreen {
            return self.fullscreen_view();
        }
        let nav = column![
            text("Wingmate").size(26),
            Space::new().height(12),
            nav_button(
                "Communicate",
                self.page == Page::Communicate,
                Message::Navigate(Page::Communicate)
            ),
            nav_button(
                "Screens",
                self.page == Page::Screens,
                Message::Navigate(Page::Screens)
            ),
            nav_button(
                "Dictionary",
                self.page == Page::Dictionary,
                Message::Navigate(Page::Dictionary)
            ),
            nav_button(
                "Settings",
                self.page == Page::Settings,
                Message::Navigate(Page::Settings)
            ),
            Space::new().height(Fill),
            text(&self.status).size(13),
            button("Refresh").on_press(Message::Refresh),
        ]
        .spacing(8)
        .padding(18)
        .width(190);

        let content = match self.page {
            Page::Welcome => unreachable!(),
            Page::Communicate => self.communicate_view(),
            Page::Screens => self.screens_view(),
            Page::Dictionary => self.dictionary_view(),
            Page::Settings => self.settings_view(),
            Page::Fullscreen => unreachable!(),
        };

        row![
            container(nav).height(Fill),
            container(content).padding(24).width(Fill).height(Fill)
        ]
        .into()
    }

    fn communicate_view(&self) -> Element<'_, Message> {
        if self.editing_phrase_id.is_some() {
            return column![
                text("Edit saved phrase").size(30),
                text_input("Button label", &self.phrase_editor_text)
                    .on_input(Message::PhraseEditorChanged),
                text_input(
                    "Speak something different (optional)",
                    &self.phrase_editor_voice
                )
                .on_input(Message::PhraseEditorVoiceChanged),
                row![
                    button("Save").on_press(Message::SavePhraseEdit),
                    button("Cancel").on_press(Message::CancelPhraseEdit),
                ]
                .spacing(10)
            ]
            .spacing(16)
            .into();
        }
        let input = text_input("Enter text to speak…", &self.draft)
            .on_input(Message::DraftChanged)
            .on_submit(Message::Speak(self.draft.clone()))
            .padding(16)
            .size(22);

        let predictions = row(self.predictions.iter().take(6).map(|word| {
            button(text(word).size(15))
                .on_press(Message::PredictionSelected(word.clone()))
                .into()
        }))
        .spacing(8);

        let mut categories = row![button("All").on_press(Message::SelectCategory(None))].spacing(8);
        for category in &self.categories {
            categories = categories.push(
                row![
                    button(category.name.as_deref().unwrap_or("Unnamed"))
                        .on_press(Message::SelectCategory(Some(category.id.clone()))),
                    button("×").on_press(Message::DeleteCategory(category.id.clone()))
                ]
                .spacing(2),
            );
        }
        if self.settings.history_visible && !self.history.is_empty() {
            categories = categories.push(
                button("History").on_press(Message::SelectCategory(Some("__history__".into()))),
            );
        }

        let mut grid = column![].spacing(10);
        let history_phrases: Vec<Phrase> = self
            .history
            .iter()
            .enumerate()
            .filter_map(|(index, item)| {
                item.said_text.as_ref().map(|value| Phrase {
                    id: format!("history-{index}"),
                    text: value.clone(),
                    name: None,
                    background_color: None,
                    image_url: None,
                })
            })
            .collect();
        let shown_phrases = if self.selected_category.as_deref() == Some("__history__") {
            &history_phrases
        } else {
            &self.phrases
        };
        let columns = self.settings.grid_columns.max(1) as usize;
        for chunk in shown_phrases.chunks(columns) {
            let mut grid_row = row![].spacing(10);
            for phrase in chunk {
                let label = if phrase.image_url.is_some() && self.settings.show_symbols {
                    format!("▣  {}", phrase.text)
                } else if phrase.background_color.is_some() {
                    format!("■  {}", phrase.text)
                } else {
                    phrase.text.clone()
                };
                let spoken = phrase.name.clone().unwrap_or_else(|| phrase.text.clone());
                let mut card = column![button(text(label).size(18))
                    .on_press(Message::Speak(spoken))
                    .width(Fill)
                    .height(72),]
                .spacing(4)
                .width(Fill);
                if self.selected_category.as_deref() != Some("__history__") {
                    card = card.push(
                        row![
                            button("Edit").on_press(Message::EditPhrase(phrase.id.clone())),
                            button("Remove").on_press(Message::DeletePhrase(phrase.id.clone())),
                        ]
                        .spacing(4),
                    );
                }
                grid_row = grid_row.push(container(card).padding(6).width(Fill));
            }
            grid = grid.push(grid_row);
        }

        let adders: Element<'_, Message> = row![
            text_input("New phrase", &self.new_phrase)
                .on_input(Message::NewPhraseChanged)
                .on_submit(Message::AddPhrase),
            button("Add phrase").on_press(Message::AddPhrase),
            text_input("New category", &self.new_category)
                .on_input(Message::NewCategoryChanged)
                .on_submit(Message::AddCategory),
            button("Add category").on_press(Message::AddCategory),
        ]
        .spacing(8)
        .into();

        let controls = row![
            button("▶ Speak").on_press(Message::Speak(self.draft.clone())),
            button("⏸ Pause").on_press(Message::SpeechAction("/api/speak/pause")),
            button("■ Stop").on_press(Message::SpeechAction("/api/speak/stop")),
            button(if self.thought_draft.is_some() {
                "Resume thought"
            } else {
                "Hold that thought"
            })
            .on_press(Message::ToggleThought),
            button("Fullscreen").on_press(Message::Navigate(Page::Fullscreen)),
        ]
        .spacing(10);

        let ssml = row![
            text("Speech markup:"),
            button("Pause 0.5s").on_press(Message::AppendMarkup(" [0.5s] ")),
            button("Emphasis").on_press(Message::AppendMarkup(" [strong] ")),
            button("Secondary language").on_press(Message::AppendMarkup(" <en></en> ")),
        ]
        .spacing(8);

        column![
            text("Communicate").size(30),
            input,
            predictions,
            ssml,
            scrollable(categories)
                .direction(scrollable::Direction::Horizontal(
                    scrollable::Scrollbar::default()
                ))
                .height(48),
            scrollable(grid).height(Fill),
            if self.selected_category.as_deref() == Some("__history__") {
                row![button("Clear history").on_press(Message::ClearHistory)].into()
            } else {
                adders
            },
            controls,
        ]
        .spacing(14)
        .into()
    }

    fn welcome_view(&self) -> Element<'_, Message> {
        let body: Element<'_, Message> = match self.onboarding_step {
            0 => column![
                text("Welcome to Wingmate").size(40),
                text("A voice and symbol communication aid built around how you communicate."),
                text("Use the keyboard for fast text-to-speech, or Screens for visual AAC boards."),
                button("Get started").on_press(Message::OnboardingNext),
            ].spacing(18).into(),
            1 => column![
                text("Choose your starting workspace").size(32),
                checkbox(!self.onboarding_screens).label("Keyboard — type, predict, save, and speak").on_toggle(|selected| Message::OnboardingMode(!selected)),
                checkbox(self.onboarding_screens).label("Screens — visual communication boards").on_toggle(Message::OnboardingMode),
                row![button("Back").on_press(Message::OnboardingBack), button("Next").on_press(Message::OnboardingNext)].spacing(10),
            ].spacing(18).into(),
            _ => column![
                text("Privacy").size(32),
                text("Optional anonymous feature-usage reporting helps improve Wingmate. Communication content is never included."),
                checkbox(self.onboarding_analytics).label("Share anonymous feature usage").on_toggle(Message::OnboardingAnalytics),
                row![button("Back").on_press(Message::OnboardingBack), button("Finish setup").on_press(Message::CompleteOnboarding)].spacing(10),
            ].spacing(18).into(),
        };
        container(body).max_width(720).padding(30).into()
    }

    fn fullscreen_view(&self) -> Element<'_, Message> {
        container(
            column![
                text(&self.draft).size(52).width(Fill),
                row![
                    button("▶ Speak").on_press(Message::Speak(self.draft.clone())),
                    button("Close").on_press(Message::Navigate(Page::Communicate)),
                ]
                .spacing(12),
            ]
            .spacing(30),
        )
        .padding(48)
        .center(Fill)
        .into()
    }

    fn screens_view(&self) -> Element<'_, Message> {
        if let Some(graph) = &self.board_graph {
            return self.board_workspace_view(graph);
        }

        let library = self
            .board_sets
            .iter()
            .fold(column![].spacing(10), |list, set| {
                list.push(
                    container(
                        row![
                            column![
                                text(&set.name).size(20),
                                text(format!(
                                    "{} pages{}",
                                    set.board_ids.len(),
                                    if set.is_locked { " · locked" } else { "" }
                                ))
                                .size(13),
                            ]
                            .width(Fill),
                            button("Run").on_press(Message::OpenBoardSet(set.id.clone(), false)),
                            button("Edit").on_press(Message::OpenBoardSet(set.id.clone(), true)),
                            button("Duplicate")
                                .on_press(Message::DuplicateBoardSet(set.id.clone())),
                            button(if set.is_locked { "Unlock" } else { "Lock" })
                                .on_press(Message::ToggleBoardSetLock(set.id.clone())),
                            button("Export").on_press(Message::ExportBoardSet(
                                set.id.clone(),
                                set.name.clone()
                            )),
                            button("Delete").on_press(Message::DeleteBoardSet(set.id.clone())),
                        ]
                        .spacing(8)
                        .align_y(iced::Alignment::Center),
                    )
                    .padding(10),
                )
            });

        column![
            text("Screens").size(30),
            text("Create and run visual AAC board sets."),
            button("Import OBF/OBZ…").on_press(Message::ImportBoardSet),
            scrollable(library).height(Fill),
            row![
                text_input("New screen set", &self.new_board_set)
                    .on_input(Message::BoardSetNameChanged)
                    .on_submit(Message::CreateBoardSet),
                text(format!("Rows {}", self.board_rows)),
                slider(1..=12, self.board_rows, Message::BoardRowsChanged).width(120),
                text(format!("Columns {}", self.board_columns)),
                slider(1..=12, self.board_columns, Message::BoardColumnsChanged).width(120),
                checkbox(self.calculator_template)
                    .label("Calculator template")
                    .on_toggle(Message::CalculatorTemplate),
                button("Create").on_press(Message::CreateBoardSet),
            ]
            .spacing(8)
            .align_y(iced::Alignment::Center),
        ]
        .spacing(14)
        .into()
    }

    fn board_workspace_view<'a>(&'a self, graph: &'a BoardGraph) -> Element<'a, Message> {
        if let Some((row_index, column_index)) = self.editing_cell {
            return column![
                text("Edit board field").size(30),
                text_input("Label", &self.cell_label).on_input(Message::CellLabelChanged),
                text_input(
                    "Speak something different (optional)",
                    &self.cell_vocalization
                )
                .on_input(Message::CellVoiceChanged),
                row![
                    button("Save").on_press(Message::SaveBoardCell),
                    button("Clear field").on_press(Message::ClearBoardCell),
                    button("Cancel").on_press(Message::CancelBoardCell),
                ]
                .spacing(10),
                text(format!(
                    "Row {}, column {}",
                    row_index + 1,
                    column_index + 1
                )),
            ]
            .spacing(16)
            .into();
        }

        let active_id = self
            .active_board_id
            .as_deref()
            .unwrap_or(&graph.board_set.root_board_id);
        let board = graph
            .boards
            .iter()
            .find(|board| board.id == active_id)
            .or_else(|| graph.boards.first());
        let Some(board) = board else {
            return text("This screen set has no pages.").into();
        };
        let mut cells = column![].spacing(8);
        if let Some(grid) = &board.grid {
            for (row_index, order_row) in grid.order.iter().enumerate().take(grid.rows) {
                let mut cell_row = row![].spacing(8);
                for column_index in 0..grid.columns {
                    let button_data = order_row
                        .get(column_index)
                        .and_then(|id| id.as_ref())
                        .and_then(|id| board.buttons.iter().find(|button| &button.id == id));
                    let raw_label = button_data
                        .and_then(|button| button.label.as_deref())
                        .unwrap_or(if self.board_edit_mode { "+" } else { "" });
                    let label = if button_data
                        .and_then(|button| button.background_color.as_ref())
                        .is_some()
                    {
                        format!("■  {raw_label}")
                    } else {
                        raw_label.to_string()
                    };
                    let action = if self.board_edit_mode {
                        Message::SelectBoardCell(row_index, column_index)
                    } else if let Some(target) = button_data
                        .and_then(|button| button.load_board.as_ref())
                        .and_then(|link| link.id.clone())
                    {
                        Message::SelectBoard(target)
                    } else {
                        Message::Speak(
                            button_data
                                .and_then(|button| {
                                    button.vocalization.clone().or_else(|| button.label.clone())
                                })
                                .unwrap_or_default(),
                        )
                    };
                    cell_row = cell_row.push(
                        button(text(label).size(18))
                            .on_press(action)
                            .width(Fill)
                            .height(80),
                    );
                }
                cells = cells.push(cell_row);
            }
        }

        let pages = row(graph.boards.iter().map(|item| {
            button(item.name.as_deref().unwrap_or("Untitled page"))
                .on_press(Message::SelectBoard(item.id.clone()))
                .into()
        }))
        .spacing(6);

        let page_editor: Element<'_, Message> = if self.board_edit_mode {
            row![
                text_input("New page name", &self.new_page)
                    .on_input(Message::PageNameChanged)
                    .on_submit(Message::CreatePage),
                button("Add page").on_press(Message::CreatePage),
            ]
            .spacing(8)
            .into()
        } else {
            Space::new().height(1).into()
        };

        column![
            row![
                button("← Library").on_press(Message::ExitBoardSet),
                text(format!(
                    "{} · {}",
                    graph.board_set.name,
                    board.name.as_deref().unwrap_or("Page")
                ))
                .size(26)
                .width(Fill),
                button(if self.board_edit_mode { "Run" } else { "Edit" })
                    .on_press(Message::ToggleBoardEdit),
                button("Keyboard").on_press(Message::Navigate(Page::Communicate)),
            ]
            .spacing(10)
            .align_y(iced::Alignment::Center),
            scrollable(pages)
                .direction(scrollable::Direction::Horizontal(
                    scrollable::Scrollbar::default()
                ))
                .height(48),
            scrollable(cells).height(Fill),
            page_editor
        ]
        .spacing(12)
        .into()
    }

    fn board_cell(&self, row: usize, column: usize) -> Option<&BoardButton> {
        let graph = self.board_graph.as_ref()?;
        let board = graph
            .boards
            .iter()
            .find(|board| Some(board.id.as_str()) == self.active_board_id.as_deref())?;
        let id = board.grid.as_ref()?.order.get(row)?.get(column)?.as_ref()?;
        board.buttons.iter().find(|button| &button.id == id)
    }

    fn dictionary_view(&self) -> Element<'_, Message> {
        let entries = self
            .pronunciations
            .iter()
            .fold(column![].spacing(8), |list, entry| {
                list.push(
                    row![
                        text(&entry.word).width(180),
                        text(&entry.phoneme).width(Fill),
                        button("Remove").on_press(Message::DeletePronunciation(entry.word.clone())),
                    ]
                    .align_y(iced::Alignment::Center),
                )
            });

        column![
            text("Pronunciation dictionary").size(30),
            text("Teach Wingmate how names and uncommon words should sound."),
            row![
                text_input("Word", &self.new_word).on_input(Message::NewWordChanged),
                text_input("Say it like…", &self.new_phoneme)
                    .on_input(Message::NewPhonemeChanged)
                    .on_submit(Message::AddPronunciation),
                button("Add").on_press(Message::AddPronunciation),
            ]
            .spacing(10),
            scrollable(entries).height(Fill),
        ]
        .spacing(16)
        .into()
    }

    fn settings_view(&self) -> Element<'_, Message> {
        let categories: Vec<(SettingsCategory, &'static str)> = vec![
            (SettingsCategory::Speech, "Speech"),
            (SettingsCategory::Display, "Display"),
            (SettingsCategory::Access, "Access"),
            (SettingsCategory::Startup, "Startup"),
            (SettingsCategory::Privacy, "Privacy"),
            (SettingsCategory::Partner, "Partner window"),
        ];

        let sidebar = column![
            text("Settings").size(26),
            Space::new().height(12),
            categories
                .iter()
                .map(|(category, label)| {
                    nav_button(label, self.settings_category == *category, Message::SelectSettingsCategory(*category))
                })
                .fold(column![].spacing(6), |col, b| col.push(b)),
        ]
        .spacing(6)
        .padding(18)
        .width(200);

        let content = match self.settings_category {
            SettingsCategory::Speech => self.speech_settings_view(),
            SettingsCategory::Display => self.display_settings_view(),
            SettingsCategory::Access => self.access_settings_view(),
            SettingsCategory::Startup => self.startup_settings_view(),
            SettingsCategory::Privacy => self.privacy_settings_view(),
            SettingsCategory::Partner => self.partner_settings_view(),
        };

        row![
            container(sidebar).height(Fill),
            container(content).padding(24).width(Fill).height(Fill)
        ]
        .into()
    }

    fn speech_settings_view(&self) -> Element<'_, Message> {
        let voice_names: Vec<String> = self.voices.iter().filter_map(|v| v.name.clone()).collect();
        let selected_voice = if voice_names.contains(&self.settings.voice) {
            Some(self.settings.voice.clone())
        } else {
            None
        };
        let mut languages: Vec<String> = self
            .voices
            .iter()
            .flat_map(|voice| voice.supported_languages.clone().unwrap_or_default())
            .collect();
        languages.push(self.settings.primary_language.clone());
        languages.sort();
        languages.dedup();
        let mut secondary_languages = vec!["Disabled".to_string()];
        secondary_languages.extend(languages.clone());
        let selected_secondary = if self.settings.secondary_language.is_empty() {
            "Disabled".into()
        } else {
            self.settings.secondary_language.clone()
        };

        scrollable(
            column![
                settings_row("Voice", pick_list(voice_names, selected_voice, Message::VoiceSelected).into()),
                settings_row(
                    "Engine",
                    pick_list(
                        vec!["SYSTEM".to_string(), "AZURE_USER_RESOURCE".to_string()],
                        Some(self.settings.tts_engine.clone()),
                        Message::EngineChanged
                    )
                    .into(),
                ),
                settings_row("Speed", slider(0.5..=2.0, self.settings.speech_rate, Message::RateChanged).step(0.1).into()),
                settings_row(
                    "Primary language",
                    pick_list(
                        languages.clone(),
                        Some(self.settings.primary_language.clone()),
                        Message::PrimaryLanguageChanged
                    )
                    .into(),
                ),
                settings_row(
                    "Secondary language",
                    pick_list(
                        secondary_languages,
                        Some(selected_secondary),
                        Message::SecondaryLanguageChanged
                    )
                    .into(),
                ),
                column![
                    text("Azure Speech (endpoint + key)").size(15),
                    text_input("Azure Speech endpoint", &self.azure_endpoint).on_input(Message::AzureEndpointChanged),
                    text_input("Azure Speech key", &self.azure_key).on_input(Message::AzureKeyChanged),
                    button("Save Azure configuration and refresh voices").on_press(Message::SaveAzureConfig),
                ]
                .spacing(6),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }

    fn display_settings_view(&self) -> Element<'_, Message> {
        let selected_theme = match self.settings.force_dark_theme {
            Some(true) => "Dark",
            Some(false) => "Light",
            None => "System",
        }
        .to_string();

        scrollable(
            column![
                settings_row(
                    "Theme",
                    pick_list(
                        vec!["System".into(), "Light".into(), "Dark".into()],
                        Some(selected_theme),
                        Message::ThemeChanged
                    )
                    .into(),
                ),
                settings_row("Text scale", slider(0.5..=2.0, self.settings.font_size_scale, Message::FontScaleChanged).step(0.1).into()),
                settings_row("Button scale", slider(0.5..=2.0, self.settings.button_scale, Message::ButtonScaleChanged).step(0.1).into()),
                settings_row("Input scale", slider(0.5..=2.0, self.settings.input_field_scale, Message::InputScaleChanged).step(0.1).into()),
                settings_row(
                    "Grid columns",
                    slider(1..=12, self.settings.grid_columns, Message::GridColumnsChanged).into(),
                ),
                checkbox(self.settings.show_labels)
                    .label("Show labels on symbol buttons")
                    .on_toggle(|v| Message::SettingBool("showLabels", v)),
                checkbox(self.settings.show_symbols)
                    .label("Show symbols on buttons")
                    .on_toggle(|v| Message::SettingBool("showSymbols", v)),
                checkbox(self.settings.label_at_top)
                    .label("Place labels above symbols")
                    .on_toggle(|v| Message::SettingBool("labelAtTop", v)),
                checkbox(self.settings.high_contrast_mode)
                    .label("High contrast mode")
                    .on_toggle(|v| Message::SettingBool("highContrastMode", v)),
                checkbox(self.settings.board_show_message_bar)
                    .label("Show the message bar on boards")
                    .on_toggle(|v| Message::SettingBool("boardShowMessageBar", v)),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }

    fn access_settings_view(&self) -> Element<'_, Message> {
        let selected_scan_order = match self.settings.scan_phrase_grid_order.as_str() {
            "column-major" => "Column-major",
            "linear" => "Linear",
            _ => "Row-major",
        }
        .to_string();

        scrollable(
            column![
                settings_row(
                    "Hold to select",
                    slider(0..=3000, self.settings.hold_to_select_millis as i32, |v| Message::HoldChanged(v as i64)).step(100).into(),
                ),
                settings_row(
                    "Dwell to select",
                    slider(0..=5000, self.settings.dwell_to_select_millis as i32, |v| Message::DwellChanged(v as i64)).step(100).into(),
                ),
                settings_row(
                    "Selection highlight",
                    slider(0..=5000, self.settings.selection_highlight_millis as i32, |v| Message::SelectionHighlightChanged(v as i64)).step(100).into(),
                ),
                settings_row(
                    "Selection debounce",
                    slider(0..=1000, self.settings.selection_debounce_millis as i32, |v| Message::SelectionDebounceChanged(v as i64)).step(50).into(),
                ),
                checkbox(self.settings.selection_sound_enabled)
                    .label("Play a selection sound")
                    .on_toggle(|v| Message::SettingBool("selectionSoundEnabled", v)),
                checkbox(self.settings.auditory_fishing_enabled)
                    .label("Auditory fishing")
                    .on_toggle(|v| Message::SettingBool("auditoryFishingEnabled", v)),
                Space::new().height(6),
                text("Switch scanning").size(20),
                checkbox(self.settings.scanning_enabled)
                    .label("Enable switch scanning")
                    .on_toggle(|v| Message::SettingBool("scanningEnabled", v)),
                checkbox(self.settings.scan_playback_area_enabled)
                    .label("Scan the playback area")
                    .on_toggle(|v| Message::SettingBool("scanPlaybackAreaEnabled", v)),
                checkbox(self.settings.scan_input_field_enabled)
                    .label("Scan the input field")
                    .on_toggle(|v| Message::SettingBool("scanInputFieldEnabled", v)),
                checkbox(self.settings.scan_phrase_grid_enabled)
                    .label("Scan the phrase grid")
                    .on_toggle(|v| Message::SettingBool("scanPhraseGridEnabled", v)),
                checkbox(self.settings.scan_category_items_enabled)
                    .label("Scan category items")
                    .on_toggle(|v| Message::SettingBool("scanCategoryItemsEnabled", v)),
                checkbox(self.settings.scan_top_bar_enabled)
                    .label("Scan the top bar")
                    .on_toggle(|v| Message::SettingBool("scanTopBarEnabled", v)),
                settings_row(
                    "Scan order",
                    pick_list(
                        vec!["Row-major".into(), "Column-major".into(), "Linear".into()],
                        Some(selected_scan_order),
                        |v: String| {
                            let key = match v.as_str() {
                                "Column-major" => "column-major",
                                "Linear" => "linear",
                                _ => "row-major",
                            };
                            Message::ScanOrderChanged(key.to_string())
                        }
                    )
                    .into(),
                ),
                settings_row(
                    "Scan dwell",
                    slider(0.3..=2.0, self.settings.scan_dwell_time_seconds, Message::ScanDwellChanged).step(0.1).into(),
                ),
                settings_row(
                    "Auto-advance",
                    slider(0.5..=3.0, self.settings.scan_auto_advance_seconds, Message::ScanAutoAdvanceChanged).step(0.1).into(),
                ),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }

    fn startup_settings_view(&self) -> Element<'_, Message> {
        let startup_board_set_id = self.settings.startup_board_set_id.clone().unwrap_or_default();

        scrollable(
            column![
                settings_row(
                    "Startup mode",
                    pick_list(
                        vec!["Keyboard".to_string(), "Screens".to_string()],
                        Some(self.settings.startup_mode.clone()),
                        Message::StartupModeChanged
                    )
                    .into(),
                ),
                settings_row(
                    "Startup screen set",
                    pick_list(
                        self.board_sets.iter().map(|set| set.name.clone()).collect::<Vec<_>>(),
                        if startup_board_set_id.is_empty() {
                            None
                        } else {
                            self.board_sets
                                .iter()
                                .find(|set| set.id == startup_board_set_id)
                                .map(|set| set.name.clone())
                        },
                        |name| {
                            let id = self
                                .board_sets
                                .iter()
                                .find(|set| set.name == name)
                                .map(|set| set.id.clone())
                                .unwrap_or_default();
                            Message::StartupBoardSetChanged(id)
                        }
                    )
                    .into(),
                ),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }

    fn privacy_settings_view(&self) -> Element<'_, Message> {
        scrollable(
            column![
                checkbox(self.settings.history_visible)
                    .label("Show speech history")
                    .on_toggle(|v| Message::SettingBool("historyVisible", v)),
                checkbox(self.settings.usage_logging_enabled)
                    .label("Keep local AAC usage logs")
                    .on_toggle(|v| Message::SettingBool("usageLoggingEnabled", v)),
                checkbox(self.settings.feature_usage_reporting_enabled)
                    .label("Share anonymous feature usage")
                    .on_toggle(|v| Message::SettingBool("featureUsageReportingEnabled", v)),
                Space::new().height(6),
                row![
                    button("Export speech history…").on_press(Message::ExportHistory),
                    button("Import speech history…").on_press(Message::ImportHistory),
                    button("Clear speech history").on_press(Message::ClearHistory),
                ]
                .spacing(8),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }

    fn partner_settings_view(&self) -> Element<'_, Message> {
        let (connected, active) = self.partner.state();

        scrollable(
            column![
                checkbox(self.settings.partner_window_enabled)
                    .label("Mirror speech on the external display")
                    .on_toggle(Message::PartnerEnabled),
                settings_row(
                    "Font size",
                    slider(16..=34, self.settings.partner_window_font_size, Message::PartnerFontChanged).into(),
                ),
                checkbox(self.settings.partner_window_idle_enabled)
                    .label("Show idle face")
                    .on_toggle(Message::PartnerIdleChanged),
                Space::new().height(6),
                text(format!(
                    "Device: {} · Display: {}",
                    if connected { "connected" } else { "not connected" },
                    if active { "active" } else { "inactive" }
                )),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }
}

fn settings_row<'a>(label: &'a str, control: Element<'a, Message>) -> Element<'a, Message> {
    row![
        text(label).width(210),
        container(control).width(360),
    ]
    .align_y(iced::Alignment::Center)
    .into()
}

fn nav_button<'a>(label: &'a str, selected: bool, message: Message) -> Element<'a, Message> {    let label = if selected {
        format!("●  {label}")
    } else {
        format!("   {label}")
    };
    button(text(label).size(16))
        .on_press(message)
        .width(Fill)
        .into()
}

#[derive(Clone)]
struct Api {
    base: String,
    client: Client,
}

impl Api {
    fn new() -> Self {
        Self {
            base: env::var("WINGMATE_API_URL").unwrap_or_else(|_| DEFAULT_API_URL.into()),
            client: Client::new(),
        }
    }

    fn bootstrap(&self) -> Task<Message> {
        Task::batch(vec![
            self.load_phrases(),
            self.load_categories(),
            self.load_voices(),
            self.load_settings(),
            self.load_history(),
            self.load_board_sets(),
            self.load_azure_config(),
        ])
    }

    fn load_phrases(&self) -> Task<Message> {
        self.get("/api/phrases", Message::LoadedPhrases)
    }
    fn load_categories(&self) -> Task<Message> {
        self.get("/api/categories", Message::LoadedCategories)
    }
    fn load_voices(&self) -> Task<Message> {
        self.get("/api/voices", Message::LoadedVoices)
    }
    fn load_settings(&self) -> Task<Message> {
        self.get("/api/settings", Message::LoadedSettings)
    }
    fn load_dictionary(&self) -> Task<Message> {
        self.get("/api/pronunciation", Message::LoadedDictionary)
    }
    fn load_azure_config(&self) -> Task<Message> {
        self.get("/api/azure-config", Message::LoadedAzureConfig)
    }
    fn load_history(&self) -> Task<Message> {
        self.get("/api/history", Message::LoadedHistory)
    }
    fn load_board_sets(&self) -> Task<Message> {
        self.get("/api/boardsets", Message::LoadedBoardSets)
    }

    fn get<T, F>(&self, path: &'static str, map: F) -> Task<Message>
    where
        T: for<'de> Deserialize<'de> + Send + 'static,
        F: Fn(Result<T, String>) -> Message + Send + 'static,
    {
        let api = self.clone();
        Task::perform(
            async move { api.request_json(Method::GET, path, None).await },
            map,
        )
    }

    fn predict(&self, context: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_json(
                    Method::POST,
                    "/api/predict",
                    Some(serde_json::json!({"context": context, "maxWords": 6, "maxLetters": 0})),
                )
                .await
            },
            Message::LoadedPredictions,
        )
    }

    fn select_category(&self, id: Option<String>) -> Task<Message> {
        self.request(
            Method::POST,
            "/api/categories/select",
            Some(serde_json::json!({"categoryId": id})),
            Message::CategorySelected,
        )
    }

    fn speak(&self, value: String) -> Task<Message> {
        self.request(
            Method::POST,
            "/api/speak",
            Some(serde_json::json!({"text": value})),
            Message::ActionFinished,
        )
    }

    fn learn(&self, value: String) -> Task<Message> {
        self.request(
            Method::POST,
            "/api/predict/learn",
            Some(serde_json::json!({"text": value})),
            Message::ActionFinished,
        )
    }

    fn empty_post(&self, path: &'static str) -> Task<Message> {
        self.request(Method::POST, path, None, Message::ActionFinished)
    }

    fn add_phrase(&self, value: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/phrases",
                    Some(serde_json::json!({"text": value})),
                )
                .await?;
                api.request_json(Method::GET, "/api/phrases", None).await
            },
            Message::LoadedPhrases,
        )
    }

    fn delete_phrase(&self, id: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/phrases/{}", encode_segment(&id));
                api.request_unit(Method::DELETE, &path, None).await?;
                api.request_json(Method::GET, "/api/phrases", None).await
            },
            Message::LoadedPhrases,
        )
    }

    fn update_phrase(&self, id: String, text: String, voice: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/phrases/{}", encode_segment(&id));
                api.request_unit(
                    Method::PUT,
                    &path,
                    Some(serde_json::json!({"text": text, "name": voice})),
                )
                .await?;
                tokio::time::sleep(Duration::from_millis(100)).await;
                api.request_json(Method::GET, "/api/phrases", None).await
            },
            Message::LoadedPhrases,
        )
    }

    fn add_category(&self, value: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/categories",
                    Some(serde_json::json!({"name": value})),
                )
                .await?;
                api.request_json(Method::GET, "/api/categories", None).await
            },
            Message::LoadedCategories,
        )
    }

    fn delete_category(&self, id: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/categories/{}", encode_segment(&id));
                api.request_unit(Method::DELETE, &path, None).await?;
                tokio::time::sleep(Duration::from_millis(100)).await;
                api.request_json(Method::GET, "/api/categories", None).await
            },
            Message::LoadedCategories,
        )
    }

    fn put_json(&self, path: &'static str, body: serde_json::Value) -> Task<Message> {
        self.request(Method::PUT, path, Some(body), Message::ActionFinished)
    }

    fn patch_setting(&self, key: &'static str, value: serde_json::Value) -> Task<Message> {
        self.put_json("/api/settings", serde_json::json!({ key: value }))
    }

    fn complete_onboarding(&self, analytics: bool, screens: bool) -> Task<Message> {
        Task::batch(vec![
            self.put_json(
                "/api/settings",
                serde_json::json!({
                    "welcomeFlowCompleted": true,
                    "startupMode": if screens { "Screens" } else { "Keyboard" },
                    "featureUsageReportingEnabled": analytics,
                }),
            ),
            if screens {
                self.load_board_sets()
            } else {
                self.load_phrases()
            },
        ])
    }

    fn save_azure_config(&self, endpoint: String, key: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/azure-config",
                    Some(serde_json::json!({"endpoint": endpoint, "key": key})),
                )
                .await
            },
            Message::ActionFinished,
        )
    }

    fn partner_display(&self, settings: &Settings) -> Task<Message> {
        self.put_json(
            "/api/settings/partnerwindow-display",
            serde_json::json!({
                "fontSize": settings.partner_window_font_size,
                "idleEnabled": settings.partner_window_idle_enabled,
            }),
        )
    }

    fn add_pronunciation(&self, word: String, phoneme: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/pronunciation",
                    Some(serde_json::json!({"word": word, "phoneme": phoneme})),
                )
                .await?;
                api.request_json(Method::GET, "/api/pronunciation", None)
                    .await
            },
            Message::LoadedDictionary,
        )
    }

    fn delete_pronunciation(&self, word: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/pronunciation/{}", encode_segment(&word));
                api.request_unit(Method::DELETE, &path, None).await?;
                api.request_json(Method::GET, "/api/pronunciation", None)
                    .await
            },
            Message::LoadedDictionary,
        )
    }

    fn clear_history(&self) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(Method::DELETE, "/api/history", None)
                    .await?;
                Ok(Vec::new())
            },
            Message::LoadedHistory,
        )
    }

    fn export_history(&self, path: PathBuf) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let response = api
                    .send_with_startup_retry(Method::GET, "/api/history/export", None)
                    .await?;
                let bytes = response
                    .bytes()
                    .await
                    .map_err(|error| format!("Could not read history export: {error}"))?;
                std::fs::write(path, bytes)
                    .map_err(|error| format!("Could not save history: {error}"))
            },
            Message::ActionFinished,
        )
    }

    fn import_history(&self, path: PathBuf) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let body = std::fs::read_to_string(path)
                    .map_err(|error| format!("Could not read history: {error}"))?;
                let url = format!("{}/api/history/import", api.base);
                let response = api
                    .client
                    .post(url)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .send()
                    .await
                    .map_err(|error| format!("Could not import history: {error}"))?;
                if response.status().is_success() {
                    Ok(())
                } else {
                    Err(format!("History import failed: {}", response.status()))
                }
            },
            Message::ActionFinished,
        )
    }

    fn load_board_graph(&self, id: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/boardsets/{}", encode_segment(&id));
                api.request_json(Method::GET, &path, None).await
            },
            Message::LoadedBoardGraph,
        )
    }

    fn create_board_set(
        &self,
        name: String,
        rows: i32,
        columns: i32,
        calculator: bool,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/boardsets",
                    Some(serde_json::json!({
                        "name": name, "rows": rows, "columns": columns,
                        "template": if calculator { "calculator" } else { "blank" },
                    })),
                )
                .await?;
                api.request_json(Method::GET, "/api/boardsets", None).await
            },
            Message::LoadedBoardSets,
        )
    }

    fn import_board_set(&self, path: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/boardsets/import",
                    Some(serde_json::json!({"path": path})),
                )
                .await?;
                api.request_json(Method::GET, "/api/boardsets", None).await
            },
            Message::LoadedBoardSets,
        )
    }

    fn export_board_set(&self, id: String, path: PathBuf) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let endpoint = format!("/api/boardsets/{}/export", encode_segment(&id));
                let response = api
                    .send_with_startup_retry(Method::GET, &endpoint, None)
                    .await?;
                let bytes = response
                    .bytes()
                    .await
                    .map_err(|error| format!("Could not read export: {error}"))?;
                std::fs::write(path, bytes)
                    .map_err(|error| format!("Could not save export: {error}"))
            },
            Message::ActionFinished,
        )
    }

    fn board_set_action(&self, id: String, action: &'static str, method: Method) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/boardsets/{}/{}", encode_segment(&id), action);
                api.request_unit(method, &path, None).await?;
                api.request_json(Method::GET, "/api/boardsets", None).await
            },
            Message::LoadedBoardSets,
        )
    }

    fn delete_board_set(&self, id: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/boardsets/{}", encode_segment(&id));
                api.request_unit(Method::DELETE, &path, None).await?;
                api.request_json(Method::GET, "/api/boardsets", None).await
            },
            Message::LoadedBoardSets,
        )
    }

    fn create_board(&self, set_id: String, name: String, rows: i32, columns: i32) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/boardsets/{}/boards", encode_segment(&set_id));
                api.request_unit(
                    Method::POST,
                    &path,
                    Some(serde_json::json!({"name": name, "rows": rows, "columns": columns})),
                )
                .await?;
                let graph_path = format!("/api/boardsets/{}", encode_segment(&set_id));
                api.request_json(Method::GET, &graph_path, None).await
            },
            Message::LoadedBoardGraph,
        )
    }

    fn save_board_cell(
        &self,
        set_id: String,
        board_id: String,
        row: usize,
        column: usize,
        label: String,
        vocalization: String,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!(
                    "/api/boardsets/{}/boards/{}/cells/{row}/{column}",
                    encode_segment(&set_id),
                    encode_segment(&board_id)
                );
                api.request_unit(
                    Method::PUT,
                    &path,
                    Some(serde_json::json!({"label": label, "vocalization": vocalization})),
                )
                .await?;
                let graph_path = format!("/api/boardsets/{}", encode_segment(&set_id));
                api.request_json(Method::GET, &graph_path, None).await
            },
            Message::LoadedBoardGraph,
        )
    }

    fn clear_board_cell(
        &self,
        set_id: String,
        board_id: String,
        row: usize,
        column: usize,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!(
                    "/api/boardsets/{}/boards/{}/cells/{row}/{column}",
                    encode_segment(&set_id),
                    encode_segment(&board_id)
                );
                api.request_unit(Method::DELETE, &path, None).await?;
                let graph_path = format!("/api/boardsets/{}", encode_segment(&set_id));
                api.request_json(Method::GET, &graph_path, None).await
            },
            Message::LoadedBoardGraph,
        )
    }

    fn request<F>(
        &self,
        method: Method,
        path: &'static str,
        body: Option<serde_json::Value>,
        map: F,
    ) -> Task<Message>
    where
        F: Fn(Result<(), String>) -> Message + Send + 'static,
    {
        let api = self.clone();
        Task::perform(
            async move { api.request_unit(method, path, body).await },
            map,
        )
    }

    async fn request_json<T: for<'de> Deserialize<'de>>(
        &self,
        method: Method,
        path: &str,
        body: Option<serde_json::Value>,
    ) -> Result<T, String> {
        let response = self.send_with_startup_retry(method, path, body).await?;
        response
            .json::<T>()
            .await
            .map_err(|e| format!("Invalid response from Wingmate service: {e}"))
    }

    async fn request_unit(
        &self,
        method: Method,
        path: &str,
        body: Option<serde_json::Value>,
    ) -> Result<(), String> {
        self.send_with_startup_retry(method, path, body)
            .await
            .map(|_| ())
    }

    async fn send_with_startup_retry(
        &self,
        method: Method,
        path: &str,
        body: Option<serde_json::Value>,
    ) -> Result<reqwest::Response, String> {
        let url = format!("{}{}", self.base, path);
        let mut last_error = String::new();
        for attempt in 0..8 {
            let mut request = self.client.request(method.clone(), &url);
            if let Some(value) = body.clone() {
                request = request.json(&value);
            }
            match request.send().await {
                Ok(response) if response.status().is_success() => return Ok(response),
                Ok(response) => {
                    last_error =
                        format!("Wingmate service returned {} for {path}", response.status())
                }
                Err(error) => last_error = format!("Cannot reach Wingmate service: {error}"),
            }
            if attempt < 7 {
                tokio::time::sleep(Duration::from_millis(300)).await;
            }
        }
        Err(last_error)
    }
}

fn encode_segment(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}

fn safe_filename(value: &str) -> String {
    let cleaned: String = value
        .chars()
        .map(|character| {
            if character.is_alphanumeric() || matches!(character, '-' | '_' | ' ') {
                character
            } else {
                '_'
            }
        })
        .collect();
    cleaned.trim().replace(' ', "-")
}

fn find_fat_jar() -> PathBuf {
    if let Ok(path) = env::var("WINGMATE_LINUXAPP_JAR") {
        return PathBuf::from(path);
    }
    let candidates = [
        PathBuf::from("build/libs/linuxApp-all.jar"),
        PathBuf::from("linuxApp/build/libs/linuxApp-all.jar"),
        PathBuf::from("../linuxApp/build/libs/linuxApp-all.jar"),
    ];
    candidates
        .into_iter()
        .find(|p| p.exists())
        .unwrap_or_else(|| PathBuf::from("build/libs/linuxApp-all.jar"))
}

fn start_bridge_server() -> Option<Child> {
    if env::var_os("WINGMATE_API_URL").is_some() {
        return None;
    }
    let jar = find_fat_jar();
    match Command::new("java")
        .arg("-jar")
        .arg(&jar)
        .arg("--no-partner-window")
        .spawn()
    {
        Ok(child) => Some(child),
        Err(error) => {
            eprintln!(
                "Wingmate backend could not start from {}: {error}",
                jar.display()
            );
            None
        }
    }
}
