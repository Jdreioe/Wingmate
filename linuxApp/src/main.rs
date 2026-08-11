use cosmic::iced::widget::{
    button, checkbox, column, container, image, mouse_area, pick_list, progress_bar, row,
    scrollable, slider, stack, svg, text, text_input, Space,
};
use cosmic::iced::{event, keyboard, window, Fill, Padding, Subscription, Task};
use cosmic::prelude::*;
use cosmic::widget::{button as cosmic_button, icon};
use reqwest::{Client, Method};
use serde::{Deserialize, Serialize};
use std::borrow::Cow;
use std::collections::{hash_map::DefaultHasher, HashMap, HashSet, VecDeque};
use std::hash::{Hash, Hasher};
use std::env;
use std::fs;
use std::io::Write;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::OnceLock;
use std::time::{Duration, Instant};
use wingmate::partner_window_bridge::{self, PartnerWindowController};

mod i18n;

const DEFAULT_API_URL: &str = "http://127.0.0.1:8765";
const WINGMATE_APP_ID: &str = "com.hojmoseit.wingmate";
const APP_ICON_PNG: &[u8] =
    include_bytes!("../icons/hicolor/192x192/apps/com.hojmoseit.wingmate.png");
const DESKTOP_ENTRY: &str = include_str!("../com.hojmoseit.wingmate.desktop");

fn wingmate_window_icon() -> Option<window::Icon> {
    let pixels = ::image::load_from_memory(APP_ICON_PNG).ok()?.into_rgba8();
    let (width, height) = pixels.dimensions();
    window::icon::from_rgba(pixels.into_raw(), width, height).ok()
}

/// `cargo run` has no install phase, but Plasma resolves Wayland taskbar icons
/// by matching the window application ID to an installed desktop entry. Keep
/// this strictly scoped to executables launched from Cargo's target directory.
fn ensure_cargo_run_desktop_integration() {
    let Ok(executable) = env::current_exe() else {
        return;
    };
    let launched_from_cargo = executable
        .parent()
        .and_then(|path| path.parent())
        .and_then(|path| path.file_name())
        .is_some_and(|name| name == "target");
    if !launched_from_cargo {
        return;
    }

    let data_home = env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .or_else(|| env::var_os("HOME").map(|home| PathBuf::from(home).join(".local/share")));
    let Some(data_home) = data_home else {
        return;
    };
    let desktop_path = data_home
        .join("applications")
        .join(format!("{WINGMATE_APP_ID}.desktop"));
    let icon_path = data_home
        .join("icons/hicolor/192x192/apps")
        .join(format!("{WINGMATE_APP_ID}.png"));
    let desktop = DESKTOP_ENTRY.replace(
        "Exec=wingmate-kde",
        &format!("Exec={}", executable.display()),
    );

    let desktop_changed = fs::read_to_string(&desktop_path).ok().as_deref() != Some(&desktop);
    let icon_changed = fs::read(&icon_path).ok().as_deref() != Some(APP_ICON_PNG);
    if desktop_changed {
        if let Some(parent) = desktop_path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let _ = fs::write(&desktop_path, desktop);
    }
    if icon_changed {
        if let Some(parent) = icon_path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let _ = fs::write(&icon_path, APP_ICON_PNG);
    }

    if desktop_changed || icon_changed {
        for cache_builder in ["kbuildsycoca6", "kbuildsycoca5"] {
            if Command::new(cache_builder)
                .arg("--noincremental")
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .is_ok_and(|status| status.success())
            {
                break;
            }
        }
    }
}

const MAX_IMAGE_CACHE_ENTRIES: usize = 256;

fn as_system_managed(theme: cosmic::theme::Theme) -> cosmic::theme::Theme {
    if matches!(&theme.theme_type, cosmic::theme::ThemeType::System { .. }) {
        theme
    } else {
        // Outside COSMIC there may be no cosmic-config theme. Keep the fallback
        // palette system-managed so libcosmic can apply freedesktop appearance
        // portal updates instead of leaving the application permanently dark.
        cosmic::theme::Theme::system(Arc::new(theme.cosmic().clone()))
    }
}

fn system_managed_theme() -> cosmic::theme::Theme {
    as_system_managed(cosmic::theme::system_preference())
}

fn theme_for_preference(
    force_dark: Option<bool>,
    system_is_dark: bool,
    high_contrast: bool,
) -> cosmic::theme::Theme {
    let is_dark = force_dark.unwrap_or(system_is_dark);
    if high_contrast {
        if is_dark {
            cosmic::theme::Theme::dark_hc()
        } else {
            cosmic::theme::Theme::light_hc()
        }
    } else {
        match force_dark {
            Some(true) => cosmic::theme::Theme::dark(),
            Some(false) => cosmic::theme::Theme::light(),
            None => as_system_managed(if system_is_dark {
                cosmic::theme::system_dark()
            } else {
                cosmic::theme::system_light()
            }),
        }
    }
}

fn desktop_icon_theme() -> Option<String> {
    if let Ok(theme) = env::var("WINGMATE_ICON_THEME") {
        if !theme.trim().is_empty() {
            return Some(theme);
        }
    }

    let desktop = env::var("XDG_CURRENT_DESKTOP").unwrap_or_default();
    if desktop
        .split(':')
        .any(|part| part.eq_ignore_ascii_case("cosmic"))
    {
        return None;
    }

    Command::new("gsettings")
        .args(["get", "org.gnome.desktop.interface", "icon-theme"])
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .map(|theme| theme.trim().trim_matches(['\'', '"']).to_string())
        .filter(|theme| !theme.is_empty())
        // Adwaita is the freedesktop-compatible last resort on installations
        // where a non-COSMIC session does not publish its icon theme.
        .or_else(|| Some("Adwaita".into()))
}

fn main() -> cosmic::iced::Result {
    ensure_cargo_run_desktop_integration();
    ctrlc::set_handler(|| {
        partner_window_bridge::send_global_shutdown();
        std::process::exit(0);
    })
    .expect("failed to install signal handler");

    let requested_languages = i18n_embed::DesktopLanguageRequester::requested_languages();
    i18n::init(&requested_languages);

    let mut settings = cosmic::app::Settings::default()
        .theme(system_managed_theme())
        .size_limits(
            cosmic::iced::Limits::NONE
                .min_width(720.0)
                .min_height(480.0),
        );
    if let Some(icon_theme) = desktop_icon_theme() {
        settings = settings.default_icon_theme(icon_theme);
    }

    cosmic::app::run::<Wingmate>(settings, ())
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Phrase {
    id: String,
    text: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    image_url: Option<String>,
    #[serde(default)]
    parent_id: Option<String>,
    #[serde(default)]
    linked_board_id: Option<String>,
    #[serde(default)]
    recording_path: Option<String>,
    #[serde(default)]
    is_hidden: bool,
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
    primary_language: Option<String>,
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
    word_type_color_scheme: String,
    hold_to_select_millis: i64,
    dwell_to_select_millis: i64,
    select_key_binding: String,
    rest_mode_key_binding: String,
    pointer_emphasis_style: String,
    pointer_emphasis_scale: f32,
    selection_sound_enabled: bool,
    auditory_fishing_enabled: bool,
    speech_policy: String,
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
            word_type_color_scheme: "None".into(),
            hold_to_select_millis: 0,
            dwell_to_select_millis: 0,
            select_key_binding: String::new(),
            rest_mode_key_binding: String::new(),
            pointer_emphasis_style: "System".into(),
            pointer_emphasis_scale: 1.5,
            selection_sound_enabled: false,
            auditory_fishing_enabled: false,
            speech_policy: "Immediate".into(),
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

impl Settings {
    fn font_px(&self, base: f32) -> f32 {
        scaled_px(base, self.font_size_scale, 10.0, 96.0)
    }

    fn button_px(&self, base: f32) -> f32 {
        scaled_px(base, self.button_scale, 36.0, 240.0)
    }

    fn input_px(&self, base: f32) -> f32 {
        scaled_px(base, self.input_field_scale, 40.0, 240.0)
    }
}

fn scaled_px(base: f32, scale: f32, minimum: f32, maximum: f32) -> f32 {
    let safe_scale = if scale.is_finite() { scale } else { 1.0 };
    (base * safe_scale.clamp(0.5, 2.0)).clamp(minimum, maximum)
}

#[derive(Debug, Clone, Deserialize)]
struct Pronunciation {
    word: String,
    phoneme: String,
    #[serde(default = "default_pronunciation_alphabet")]
    alphabet: String,
}

fn default_pronunciation_alphabet() -> String {
    "text".into()
}

#[derive(Debug, Clone, Deserialize)]
struct Symbol {
    #[serde(default)]
    id: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default, rename = "imageUrl")]
    image_url: Option<String>,
    #[serde(default)]
    source: String,
}

#[derive(Debug, Clone, Deserialize)]
struct SymbolSearchResult {
    #[serde(default)]
    symbols: Vec<Symbol>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImagePayload {
    data: String,
    #[serde(default)]
    content_type: String,
}

#[derive(Debug, Clone)]
struct LoadedImageData {
    bytes: Vec<u8>,
    content_type: String,
}

#[derive(Debug, Clone)]
enum CachedVisual {
    Raster(image::Handle),
    Svg(svg::Handle),
}

#[derive(Debug, Clone, Deserialize)]
struct ImportedImage {
    url: String,
}

#[derive(Debug, Clone, Deserialize)]
struct SpeechState {
    #[serde(default)]
    state: String,
    #[serde(default)]
    playing: bool,
    #[serde(default)]
    paused: bool,
    #[serde(default)]
    error: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EditingAccessState {
    #[serde(default)]
    enabled: bool,
    #[serde(default = "default_true")]
    unlocked: bool,
    #[serde(default)]
    supported: bool,
    #[serde(default)]
    failed_attempts: i32,
}

const fn default_true() -> bool {
    true
}

impl Default for EditingAccessState {
    fn default() -> Self {
        Self {
            enabled: false,
            unlocked: true,
            supported: false,
            failed_attempts: 0,
        }
    }
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
    #[serde(rename = "credentialConfigured")]
    credential_configured: bool,
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
    #[serde(default)]
    resolved_settings: HashMap<String, ResolvedBoardSettings>,
    #[serde(default)]
    field_items: HashMap<String, Vec<BoardField>>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BoardField {
    row: usize,
    column: usize,
    row_span: usize,
    column_span: usize,
    #[serde(default)]
    button_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ResolvedBoardSettings {
    show_labels: bool,
    show_symbols: bool,
    label_at_top: bool,
    show_message_bar: bool,
    #[serde(default)]
    activation_behavior: String,
    #[serde(default)]
    return_behavior: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BoardSessionResponse {
    #[serde(default)]
    tokens: Vec<String>,
    #[serde(default)]
    sentence: String,
    #[serde(default)]
    speak_text: Option<String>,
    #[serde(default)]
    navigate_home: bool,
    #[serde(default)]
    navigate_board_id: Option<String>,
    #[serde(default)]
    open_native_keyboard: bool,
    #[serde(default)]
    unsupported_actions: Vec<String>,
    settings: ResolvedBoardSettings,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PresetDownloadProgress {
    stage: String,
    downloaded_bytes: i64,
    total_bytes: Option<i64>,
}

#[derive(Debug, Clone, Deserialize)]
struct Board {
    id: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    buttons: Vec<BoardButton>,
    #[serde(default)]
    images: Vec<BoardImage>,
    #[serde(default)]
    grid: Option<BoardGrid>,
}

#[derive(Debug, Clone, Deserialize)]
struct BoardImage {
    id: String,
    #[serde(default)]
    data: Option<String>,
    #[serde(default, rename = "dataUrl")]
    data_url: Option<String>,
    #[serde(default)]
    path: Option<String>,
    #[serde(default)]
    url: Option<String>,
    #[serde(default, rename = "contentType")]
    content_type: Option<String>,
    #[serde(default)]
    symbol: Option<BoardSymbol>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct BoardSymbol {
    #[serde(default)]
    set: Option<String>,
    #[serde(default)]
    filename: Option<String>,
    #[serde(default, rename = "libraryKey")]
    library_key: Option<String>,
}

impl BoardImage {
    fn has_source(&self) -> bool {
        !self.data.as_deref().unwrap_or_default().trim().is_empty()
            || !self.data_url.as_deref().unwrap_or_default().trim().is_empty()
            || !self.path.as_deref().unwrap_or_default().trim().is_empty()
            || !self.url.as_deref().unwrap_or_default().trim().is_empty()
            || self.symbol.is_some()
    }

    fn resolve_payload(&self) -> serde_json::Value {
        serde_json::json!({
            "id": self.id,
            "data": self.data,
            "dataUrl": self.data_url,
            "path": self.path,
            "url": self.url,
            "content_type": self.content_type,
            "symbol": self.symbol,
        })
    }
}

#[derive(Debug, Clone, Deserialize)]
struct BoardButton {
    id: String,
    #[serde(default)]
    label: Option<String>,
    #[serde(default)]
    vocalization: Option<String>,
    #[serde(default)]
    image_id: Option<String>,
    #[serde(default)]
    background_color: Option<String>,
    #[serde(default)]
    hidden: bool,
    #[serde(default)]
    load_board: Option<BoardLoad>,
    #[serde(default)]
    action: Option<String>,
    #[serde(default)]
    actions: Vec<String>,
    #[serde(default)]
    extensions: HashMap<String, serde_json::Value>,
}

impl BoardButton {
    fn rendered_background_color(&self) -> Option<&str> {
        self.background_color.as_deref().or_else(|| {
            self.extensions
                .get("ext_wingmate_resolved_background_color")
                .and_then(serde_json::Value::as_str)
        })
    }
}

#[derive(Debug, Clone, Deserialize)]
struct BoardLoad {
    id: String,
}

#[derive(Debug, Clone, Deserialize)]
struct BoardGrid {
    order: Vec<Vec<Option<String>>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Page {
    Welcome,
    Communicate,
    Screens,
    Settings,
    Fullscreen,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SettingsCategory {
    Speech,
    Dictionary,
    Display,
    Access,
    Startup,
    Privacy,
    Partner,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
enum AccessTarget {
    Speak(String),
    Recording(String),
    BoardButton(String, String),
    Category(Option<String>),
    /// Sentence-only composition: append text to the draft without speaking.
    Insert(String),
}

fn access_target_id(target: &AccessTarget) -> String {
    let mut hasher = DefaultHasher::new();
    target.hash(&mut hasher);
    format!("target:{:016x}", hasher.finish())
}

fn access_key_token(key: &keyboard::Key) -> Option<String> {
    match key {
        keyboard::Key::Named(keyboard::key::Named::Enter) => Some("Enter".into()),
        keyboard::Key::Named(named) => {
            let value = format!("{named:?}");
            value.strip_prefix('F').and_then(|number| number.parse::<u8>().ok())
                .filter(|number| (1..=12).contains(number))
                .map(|number| format!("F{number}"))
        }
        keyboard::Key::Character(value) if value.as_str() == " " => Some("Space".into()),
        _ => None,
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccessInputResponse {
    activation_target_id: Option<String>,
    is_paused: bool,
    current_target_id: Option<String>,
    dwell_progress: f32,
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
    core: cosmic::Core,
    api: Api,
    _backend: BackendProcess,
    partner: PartnerWindowController,
    page: Page,
    last_workspace: Page,
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
    native_keyboard_return_pending: bool,
    board_edit_mode: bool,
    onboarding_step: u8,
    onboarding_screens: bool,
    selected_category: Option<String>,
    settings: Settings,
    selected_voice_name: Option<String>,
    preview_voice_name: Option<String>,
    editing_access: EditingAccessState,
    editing_access_code: String,
    editing_access_new_code: String,
    editing_access_confirmation: String,
    settings_category: SettingsCategory,
    new_phrase: String,
    new_category: String,
    new_word: String,
    new_phoneme: String,
    pronunciation_alphabet: String,
    thought_draft: Option<String>,
    editing_phrase_id: Option<String>,
    phrase_editor_text: String,
    phrase_editor_voice: String,
    phrase_editor_image_url: Option<String>,
    phrase_editor_recording_path: Option<String>,
    phrase_editor_parent_id: Option<String>,
    phrase_editor_hidden: bool,
    editing_category_id: Option<String>,
    category_editor_name: String,
    manage_phrases: bool,
    new_board_set: String,
    new_page: String,
    current_page_name: String,
    board_rows: i32,
    board_columns: i32,
    board_template: String,
    preset_importing: bool,
    preset_progress: Option<f32>,
    editing_cell: Option<(usize, usize)>,
    cell_label: String,
    cell_vocalization: String,
    cell_image_url: Option<String>,
    cell_background_color: String,
    cell_word_type: String,
    cell_hidden: bool,
    cell_linked_board_id: Option<String>,
    cell_actions: String,
    symbol_query: String,
    symbol_package: String,
    symbols: Vec<Symbol>,
    symbol_loading: bool,
    pending_prediction_word: Option<String>,
    azure_endpoint: String,
    azure_key: String,
    azure_credential_configured: bool,
    replacing_azure_credentials: bool,
    status: String,
    speech_state: String,
    board_sentence_tokens: Vec<String>,
    board_sentence: String,
    board_stack: Vec<String>,
    image_cache: HashMap<String, CachedVisual>,
    image_cache_order: VecDeque<String>,
    pending_images: HashSet<String>,
    access_press: Option<(AccessTarget, Instant)>,
    last_access_activation: Option<Instant>,
    highlighted_access: Option<(AccessTarget, Instant)>,
    scan_index: usize,
    last_scan_advance: Instant,
    input_is_paused: bool,
    current_access_target_id: Option<String>,
    access_dwell_progress: f32,
    known_access_targets: HashMap<String, AccessTarget>,
    window_width: f32,
    window_height: f32,
}

#[derive(Debug, Clone)]
enum Message {
    Navigate(Page),
    ToggleSettings,
    DraftChanged(String),
    PredictionSelected(String),
    PredictionInsertionLoaded(Result<InsertionResult, String>),
    LoadedPhrases(Result<Vec<Phrase>, String>),
    LoadedCategories(Result<Vec<Category>, String>),
    LoadedVoices(Result<Vec<Voice>, String>),
    LoadedSelectedVoice(Result<Voice, String>),
    LoadedEditingAccess(Result<EditingAccessState, String>),
    LoadedSettings(Result<Settings, String>),
    LoadedDictionary(Result<Vec<Pronunciation>, String>),
    LoadedImages(Vec<(String, Result<LoadedImageData, String>)>),
    LoadedPredictions(Result<Predictions, String>),
    LoadedAzureConfig(Result<AzureConfig, String>),
    LoadedHistory(Result<Vec<HistoryEntry>, String>),
    LoadedBoardSets(Result<Vec<BoardSet>, String>),
    LoadedBoardGraph(Result<BoardGraph, String>),
    LoadedBoardPage(Result<BoardGraph, String>),
    LoadedPresetProgress(Result<PresetDownloadProgress, String>),
    BoardSentenceBackspace,
    BoardSentenceClear,
    BoardNavigateHome,
    BoardNavigateBack,
    ReturnToBoardFromKeyboard,
    BoardSessionUpdated(Result<BoardSessionResponse, String>),
    SelectCategory(Option<String>),
    CategorySelected(Result<(), String>),
    Speak(String),
    SpeechAction(&'static str),
    ClearDraft,
    PollSpeech,
    PollPresetProgress,
    PollEditingAccess,
    InputEvent(cosmic::iced::Event),
    AccessEnter(AccessTarget),
    AccessExit(AccessTarget),
    AccessPress(AccessTarget),
    AccessRelease(AccessTarget),
    AccessActivate(AccessTarget),
    AccessInputUpdated(Result<AccessInputResponse, String>),
    LoadedSpeechState(Result<SpeechState, String>),
    SpeechStarted(Result<(), String>),
    SpeechControlFinished(Result<(), String>),
    ActionFinished(Result<(), String>),
    NewPhraseChanged(String),
    AddPhrase,
    DeletePhrase(String),
    EditPhrase(String),
    PhraseEditorChanged(String),
    PhraseEditorVoiceChanged(String),
    PhraseEditorCategoryChanged(String),
    PhraseEditorHiddenChanged(bool),
    ChoosePhraseImage,
    PhraseImageImported(Result<ImportedImage, String>),
    ClearPhraseImage,
    ChoosePhraseRecording,
    ClearPhraseRecording,
    PlayRecording(String),
    MovePhrase(String, i32),
    SavePhraseEdit,
    CancelPhraseEdit,
    NewCategoryChanged(String),
    AddCategory,
    DeleteCategory(String),
    EditCategory(String),
    CategoryEditorChanged(String),
    SaveCategoryEdit,
    CancelCategoryEdit,
    MoveCategory(String, i32),
    ToggleManagePhrases,
    VoicePreviewSelected(String),
    PreviewVoice,
    ApplyPreviewVoice,
    RateChanged(f32),
    EngineChanged(String),
    AzureEndpointChanged(String),
    AzureKeyChanged(String),
    ReplaceAzureCredentials,
    SaveAzureConfig,
    AzureConfigSaved(Result<(), String>),
    PrimaryLanguageChanged(String),
    SecondaryLanguageChanged(String),
    AppearanceChanged(String),
    SelectSettingsCategory(SettingsCategory),
    EditingAccessCodeChanged(String),
    EditingAccessNewCodeChanged(String),
    EditingAccessConfirmationChanged(String),
    ConfigureEditingAccess,
    UnlockEditingAccess,
    LockEditingAccess,
    DisableEditingAccess,
    PartnerEnabled(bool),
    PartnerFontChanged(i32),
    PartnerIdleChanged(bool),
    SettingBool(&'static str, bool),
    SettingMillis(&'static str, i64),
    SettingFloat(&'static str, f32),
    SettingString(&'static str, String),
    ToggleInputPause,
    GridColumnsChanged(i32),
    StartupBoardSetChanged(String),
    StartupModeChanged(String),
    NewWordChanged(String),
    NewPhonemeChanged(String),
    PronunciationAlphabetChanged(String),
    AddPronunciation,
    DeletePronunciation(String),
    TestPronunciation(String),
    ImportPronunciations,
    ExportPronunciations,
    ClearHistory,
    ImportHistory,
    ExportHistory,
    ExportBackup,
    ImportBackup,
    BackupExported(Result<String, String>),
    BackupImported(Result<String, String>),
    AppendMarkup(&'static str),
    ToggleThought,
    OnboardingNext,
    OnboardingBack,
    OnboardingMode(bool),
    CompleteOnboarding,
    OpenBoardSet(String, bool),
    ExitBoardSet,
    BoardSetNameChanged(String),
    PageNameChanged(String),
    BoardRowsChanged(i32),
    BoardColumnsChanged(i32),
    BoardTemplateChanged(String),
    CreateBoardSet,
    ImportBoardSet,
    ExportBoardSet(String, String),
    DuplicateBoardSet(String),
    ToggleBoardSetLock(String),
    DeleteBoardSet(String),
    CreatePage,
    CurrentPageNameChanged(String),
    RenameCurrentPage,
    ResizeCurrentPage,
    DeleteCurrentPage,
    SetCurrentPageAsHome,
    PageActivationChanged(String),
    PageReturnChanged(String),
    SelectBoard(String),
    ToggleBoardEdit,
    SelectBoardCell(usize, usize),
    CellLabelChanged(String),
    CellVoiceChanged(String),
    CellSymbolQueryChanged(String),
    CellSymbolPackageChanged(String),
    CellSymbolSearch,
    CellSymbolsLoaded(Result<SymbolSearchResult, String>),
    CellSymbolPicked(usize),
    CellSymbolCleared,
    CellLocalImage,
    CellLocalImageImported(Result<ImportedImage, String>),
    CellBackgroundChanged(String),
    CellWordTypeChanged(String),
    CellHiddenChanged(bool),
    CellLinkedBoardChanged(String),
    CellActionsChanged(String),
    SaveBoardCell,
    ClearBoardCell,
    CancelBoardCell,
}

impl Message {
    fn requires_editing_access(&self) -> bool {
        matches!(
            self,
            Self::AddPhrase
                | Self::DeletePhrase(_)
                | Self::EditPhrase(_)
                | Self::SavePhraseEdit
                | Self::MovePhrase(_, _)
                | Self::AddCategory
                | Self::DeleteCategory(_)
                | Self::EditCategory(_)
                | Self::SaveCategoryEdit
                | Self::MoveCategory(_, _)
                | Self::ToggleManagePhrases
                | Self::CreateBoardSet
                | Self::ImportBoardSet
                | Self::DuplicateBoardSet(_)
                | Self::DeleteBoardSet(_)
                | Self::CreatePage
                | Self::RenameCurrentPage
                | Self::ResizeCurrentPage
                | Self::DeleteCurrentPage
                | Self::SetCurrentPageAsHome
                | Self::ToggleBoardEdit
                | Self::SelectBoardCell(_, _)
                | Self::SaveBoardCell
                | Self::ClearBoardCell
        )
    }
}

impl cosmic::Application for Wingmate {
    type Executor = cosmic::executor::Default;
    type Flags = ();
    type Message = Message;
    const APP_ID: &'static str = WINGMATE_APP_ID;

    fn core(&self) -> &cosmic::Core {
        &self.core
    }

    fn core_mut(&mut self) -> &mut cosmic::Core {
        &mut self.core
    }

    fn header_start(&self) -> Vec<Element<'_, Self::Message>> {
        if matches!(self.page, Page::Welcome | Page::Fullscreen) {
            return Vec::new();
        }

        vec![
            header_navigation_button(
                "input-keyboard-symbolic",
                fl!("nav-keyboard"),
                self.page == Page::Communicate,
                Message::Navigate(Page::Communicate),
            ),
            header_navigation_button(
                "view-grid-symbolic",
                fl!("nav-screens"),
                self.page == Page::Screens,
                Message::Navigate(Page::Screens),
            ),
        ]
    }

    fn header_end(&self) -> Vec<Element<'_, Self::Message>> {
        if matches!(self.page, Page::Welcome | Page::Fullscreen) {
            return Vec::new();
        }

        vec![header_navigation_button(
            "preferences-system-symbolic",
            if self.page == Page::Settings {
                fl!("nav-close-settings")
            } else {
                fl!("nav-settings")
            },
            self.page == Page::Settings,
            Message::ToggleSettings,
        )]
    }

    fn init(core: cosmic::Core, _flags: Self::Flags) -> (Self, Task<cosmic::Action<Message>>) {
        let window_icon_task = core
            .main_window_id()
            .and_then(|id| wingmate_window_icon().map(|icon| window::set_icon(id, icon)))
            .unwrap_or_else(Task::none);
        let api = Api::new();
        let backend = BackendProcess(start_bridge_server());
        let mut partner = PartnerWindowController::default();
        partner.start();

        let state = Self {
            core,
            api: api.clone(),
            _backend: backend,
            partner,
            page: Page::Welcome,
            last_workspace: Page::Communicate,
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
            native_keyboard_return_pending: false,
            board_edit_mode: false,
            onboarding_step: 0,
            onboarding_screens: false,
            selected_category: None,
            settings: Settings::default(),
            selected_voice_name: None,
            preview_voice_name: None,
            editing_access: EditingAccessState::default(),
            editing_access_code: String::new(),
            editing_access_new_code: String::new(),
            editing_access_confirmation: String::new(),
            settings_category: SettingsCategory::Speech,
            new_phrase: String::new(),
            new_category: String::new(),
            new_word: String::new(),
            new_phoneme: String::new(),
            pronunciation_alphabet: "text".into(),
            thought_draft: None,
            editing_phrase_id: None,
            phrase_editor_text: String::new(),
            phrase_editor_voice: String::new(),
            phrase_editor_image_url: None,
            phrase_editor_recording_path: None,
            phrase_editor_parent_id: None,
            phrase_editor_hidden: false,
            editing_category_id: None,
            category_editor_name: String::new(),
            manage_phrases: false,
            new_board_set: String::new(),
            new_page: String::new(),
            current_page_name: String::new(),
            board_rows: 4,
            board_columns: 4,
            board_template: "Blank".into(),
            preset_importing: false,
            preset_progress: None,
            editing_cell: None,
            cell_label: String::new(),
            cell_vocalization: String::new(),
            cell_image_url: None,
            cell_background_color: String::new(),
            cell_word_type: "Automatic".into(),
            cell_hidden: false,
            cell_linked_board_id: None,
            cell_actions: String::new(),
            symbol_query: String::new(),
            symbol_package: "all".into(),
            symbols: vec![],
            symbol_loading: false,
            pending_prediction_word: None,
            azure_endpoint: String::new(),
            azure_key: String::new(),
            azure_credential_configured: false,
            replacing_azure_credentials: false,
            status: fl!("status-starting"),
            speech_state: "idle".into(),
            board_sentence_tokens: Vec::new(),
            board_sentence: String::new(),
            board_stack: Vec::new(),
            image_cache: HashMap::new(),
            image_cache_order: VecDeque::new(),
            pending_images: HashSet::new(),
            access_press: None,
            last_access_activation: None,
            highlighted_access: None,
            scan_index: 0,
            last_scan_advance: Instant::now(),
            input_is_paused: false,
            current_access_target_id: None,
            access_dwell_progress: 0.0,
            known_access_targets: HashMap::new(),
            window_width: 1024.0,
            window_height: 768.0,
        };

        (
            state,
            Task::batch(vec![
                api.bootstrap().map(cosmic::Action::App),
                window_icon_task,
            ]),
        )
    }

    fn subscription(&self) -> Subscription<Message> {
        // Timer messages rebuild the current widget tree. On a large symbol
        // board even an idle one-second timer can keep software rendering busy,
        // so only run this timer while speech or access timing needs it.
        let speech_active = matches!(
            self.speech_state.as_str(),
            "starting" | "playing" | "paused"
        );
        let access_timer_active = self.settings.scanning_enabled
            || (self.settings.dwell_to_select_millis > 0 && self.current_access_target_id.is_some())
            || self.highlighted_access.is_some();
        let mut subscriptions = vec![event::listen().map(Message::InputEvent)];
        if self.editing_access.enabled && self.editing_access.unlocked {
            subscriptions.push(
                cosmic::iced::time::every(Duration::from_secs(30))
                    .map(|_| Message::PollEditingAccess),
            );
        }
        if speech_active || access_timer_active {
            subscriptions.push(
                cosmic::iced::time::every(if speech_active {
                    Duration::from_millis(200)
                } else {
                    Duration::from_millis(100)
                })
                .map(|_| Message::PollSpeech),
            );
        }
        if self.preset_importing {
            subscriptions.push(
                cosmic::iced::time::every(Duration::from_millis(200))
                    .map(|_| Message::PollPresetProgress),
            );
        }
        Subscription::batch(subscriptions)
    }

    fn update(&mut self, message: Message) -> Task<cosmic::Action<Message>> {
        if message.requires_editing_access()
            && self.editing_access.enabled
            && !self.editing_access.unlocked
        {
            self.board_edit_mode = false;
            self.manage_phrases = false;
            self.settings_category = SettingsCategory::Access;
            self.status = fl!("editing-access-unlock-required");
            return self.navigate(Page::Settings);
        }
        match message {
            Message::Navigate(page) => {
                if page == Page::Screens && self.native_keyboard_return_pending {
                    return self.return_native_keyboard_to_board();
                }
                return self.navigate(page);
            }
            Message::ToggleSettings => {
                if self.page == Page::Settings {
                    return self.navigate(self.last_workspace);
                }
                if matches!(self.page, Page::Communicate | Page::Screens) {
                    self.last_workspace = self.page;
                }
                return self.navigate(Page::Settings);
            }
            Message::DraftChanged(value) => {
                self.draft = value.clone();
                self.partner.update_text(value.clone());
                return self.api.predict(value).map(cosmic::Action::App);
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
                )
                .map(cosmic::Action::App);
            }
            Message::PredictionInsertionLoaded(result) => {
                if let Some(_word) = self.pending_prediction_word.take() {
                    let insertion = result.map(|r| r.insertion).unwrap_or_default();
                    self.draft = format!("{}{} ", self.draft, insertion);
                    self.partner.update_text(self.draft.clone());
                    return self
                        .api
                        .predict(self.draft.clone())
                        .map(cosmic::Action::App);
                }
            }
            Message::LoadedPhrases(result) => match result {
                Ok(v) => {
                    let sources = v
                        .iter()
                        .filter_map(|phrase| phrase.image_url.clone())
                        .collect();
                    self.phrases = v;
                    return self.queue_images(sources);
                }
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
            Message::LoadedSelectedVoice(result) => match result {
                Ok(v) => {
                    self.preview_voice_name = v.name.clone();
                    self.selected_voice_name = v.name;
                }
                Err(_) => {}
            },
            Message::LoadedEditingAccess(result) => match result {
                Ok(state) => {
                    if state.enabled && !state.unlocked {
                        self.board_edit_mode = false;
                        self.manage_phrases = false;
                    }
                    self.editing_access = state;
                }
                Err(error) => self.status = error,
            },
            Message::LoadedSettings(result) => match result {
                Ok(v) => {
                    let theme = theme_for_preference(
                        v.force_dark_theme,
                        self.core.system_is_dark(),
                        v.high_contrast_mode,
                    );
                    let startup_board_set_id =
                        if v.welcome_flow_completed && v.startup_mode == "Screens" {
                            v.startup_board_set_id
                                .clone()
                                .filter(|id| !id.trim().is_empty())
                        } else {
                            None
                        };
                    self.partner.set_enabled(v.partner_window_enabled);
                    self.partner.set_font_size(v.partner_window_font_size);
                    self.partner.set_idle_enabled(v.partner_window_idle_enabled);
                    if self.page == Page::Welcome && v.welcome_flow_completed {
                        self.page = if v.startup_mode == "Screens" {
                            Page::Screens
                        } else {
                            Page::Communicate
                        };
                        self.last_workspace = self.page;
                    }
                    self.settings = v;
                    self.status = fl!("status-ready");
                    let theme_task = cosmic::command::set_theme(theme);
                    if let Some(id) = startup_board_set_id {
                        self.status = fl!("status-opening-startup-screen");
                        return Task::batch(vec![
                            theme_task,
                            self.api
                                .load_board_graph(id, false)
                                .map(cosmic::Action::App),
                        ]);
                    }
                    return theme_task;
                }
                Err(e) => self.status = e,
            },
            Message::LoadedDictionary(result) => match result {
                Ok(v) => self.pronunciations = v,
                Err(e) => self.status = e,
            },
            Message::LoadedImages(results) => {
                for (source, result) in results {
                    self.cache_loaded_image(source, result);
                }
            }
            Message::LoadedPredictions(result) => {
                if let Ok(v) = result {
                    self.predictions = v.words
                }
            }
            Message::LoadedAzureConfig(result) => match result {
                Ok(config) => {
                    self.azure_endpoint = config.endpoint;
                    self.azure_key.clear();
                    self.azure_credential_configured = config.credential_configured;
                    self.replacing_azure_credentials = false;
                }
                Err(e) => self.status = e,
            },
            Message::LoadedHistory(result) => match result {
                Ok(v) => self.history = v,
                Err(e) => self.status = e,
            },
            Message::LoadedBoardSets(result) => match result {
                Ok(v) => {
                    self.board_sets = v;
                    self.preset_importing = false;
                    self.preset_progress = None;
                }
                Err(e) => {
                    self.preset_importing = false;
                    self.preset_progress = None;
                    self.status = e;
                }
            },
            Message::LoadedPresetProgress(result) => {
                if let Ok(progress) = result {
                    self.preset_progress =
                        progress
                            .total_bytes
                            .filter(|total| *total > 0)
                            .map(|total| {
                                (progress.downloaded_bytes as f32 / total as f32).clamp(0.0, 1.0)
                            });
                    if progress.stage == "importing" {
                        self.status = fl!("status-importing-quick-core");
                    }
                }
            }
            Message::LoadedBoardGraph(result) => match result {
                Ok(graph) => {
                    let same_set = self
                        .board_graph
                        .as_ref()
                        .is_some_and(|current| current.board_set.id == graph.board_set.id);
                    let active_board_id = self
                        .active_board_id
                        .clone()
                        .filter(|id| same_set && graph.boards.iter().any(|board| &board.id == id))
                        .unwrap_or_else(|| graph.board_set.root_board_id.clone());
                    if !same_set {
                        self.board_sentence_tokens.clear();
                        self.board_sentence.clear();
                        self.board_stack.clear();
                    }
                    if let Some(board) = graph
                        .boards
                        .iter()
                        .find(|board| board.id == active_board_id)
                    {
                        self.current_page_name = board.name.clone().unwrap_or_default();
                        if let Some(grid) = &board.grid {
                            self.board_rows = grid.order.len() as i32;
                            self.board_columns =
                                grid.order.iter().map(Vec::len).max().unwrap_or(1) as i32;
                        }
                    }
                    self.active_board_id = Some(active_board_id);
                    self.board_graph = Some(graph);
                    self.status = fl!("status-ready");
                    return self.queue_active_board_images();
                }
                Err(e) => self.status = e,
            },
            Message::LoadedBoardPage(result) => match result {
                Ok(mut page) => {
                    if let Some(graph) = &mut self.board_graph {
                        if graph.board_set.id == page.board_set.id {
                            for board in page.boards.drain(..) {
                                if let Some(existing) =
                                    graph.boards.iter_mut().find(|item| item.id == board.id)
                                {
                                    *existing = board;
                                } else {
                                    graph.boards.push(board);
                                }
                            }
                            graph.resolved_settings.extend(page.resolved_settings);
                            graph.field_items.extend(page.field_items);
                        }
                    }
                    self.status = fl!("status-ready");
                    return self.queue_active_board_images();
                }
                Err(error) => self.status = error,
            },
            Message::BoardSentenceBackspace => {
                if let Some(board_id) = self.active_board_id.clone() {
                    return self
                        .api
                        .update_board_session(
                            board_id,
                            "backspace",
                            None,
                            self.board_sentence_tokens.clone(),
                        )
                        .map(cosmic::Action::App);
                }
            }
            Message::BoardSentenceClear => {
                if let Some(board_id) = self.active_board_id.clone() {
                    return self
                        .api
                        .update_board_session(board_id, "clear", None, Vec::new())
                        .map(cosmic::Action::App);
                }
            }
            Message::BoardNavigateHome => {
                self.board_stack.clear();
                self.active_board_id = self
                    .board_graph
                    .as_ref()
                    .map(|graph| graph.board_set.root_board_id.clone());
                return self.queue_active_board_images();
            }
            Message::BoardNavigateBack => {
                if let Some(previous) = self.board_stack.pop() {
                    self.active_board_id = Some(previous);
                } else {
                    self.active_board_id = self
                        .board_graph
                        .as_ref()
                        .map(|graph| graph.board_set.root_board_id.clone());
                }
                return self.queue_active_board_images();
            }
            Message::BoardSessionUpdated(result) => match result {
                Ok(session) => {
                    let BoardSessionResponse {
                        tokens,
                        sentence,
                        speak_text,
                        navigate_home,
                        navigate_board_id,
                        open_native_keyboard,
                        unsupported_actions,
                        settings,
                    } = session;
                    let return_behavior = settings.return_behavior.clone();
                    self.board_sentence_tokens = tokens;
                    self.board_sentence = sentence;
                    self.partner.update_text(self.board_sentence.clone());
                    if let (Some(graph), Some(board_id)) =
                        (&mut self.board_graph, &self.active_board_id)
                    {
                        graph.resolved_settings.insert(board_id.clone(), settings);
                    }
                    if navigate_home {
                        self.board_stack.clear();
                        self.active_board_id = self
                            .board_graph
                            .as_ref()
                            .map(|graph| graph.board_set.root_board_id.clone());
                    } else if let Some(board_id) = navigate_board_id {
                        if let Some(current) = self.active_board_id.clone() {
                            self.board_stack.push(current);
                        }
                        self.active_board_id = Some(board_id);
                    } else if return_behavior == "Previous" {
                        if let Some(previous) = self.board_stack.pop() {
                            self.active_board_id = Some(previous);
                        }
                    } else if return_behavior == "StartPage" {
                        self.board_stack.clear();
                        self.active_board_id = self
                            .board_graph
                            .as_ref()
                            .map(|graph| graph.board_set.root_board_id.clone());
                    }
                    if !unsupported_actions.is_empty() {
                        self.status = fl!(
                            "error-unsupported-board-action",
                            action = unsupported_actions.join(", ")
                        );
                    }
                    if open_native_keyboard {
                        self.draft = self.board_sentence.clone();
                        self.native_keyboard_return_pending = true;
                        return Task::batch(vec![
                            self.navigate(Page::Communicate),
                            self.api
                                .predict(self.draft.clone())
                                .map(cosmic::Action::App),
                        ]);
                    }
                    let missing_page = self.active_board_id.as_ref().and_then(|board_id| {
                        self.board_graph.as_ref().and_then(|graph| {
                            (!graph.boards.iter().any(|board| &board.id == board_id))
                                .then(|| (graph.board_set.id.clone(), board_id.clone()))
                        })
                    });
                    let image_task = if let Some((set_id, board_id)) = missing_page {
                        self.status = fl!("status-loading-board");
                        self.api
                            .load_board_page(set_id, board_id)
                            .map(cosmic::Action::App)
                    } else {
                        self.queue_active_board_images()
                    };
                    if let Some(text) = speak_text.filter(|text| !text.trim().is_empty()) {
                        self.status = fl!("status-speaking");
                        self.speech_state = "starting".into();
                        return Task::batch(vec![
                            image_task,
                            self.api.speak(text).map(cosmic::Action::App),
                        ]);
                    }
                    return image_task;
                }
                Err(error) => self.status = error,
            },
            Message::ReturnToBoardFromKeyboard => {
                return self.return_native_keyboard_to_board();
            }
            Message::SelectCategory(id) => {
                self.selected_category = id.clone();
                return self.api.select_category(id).map(cosmic::Action::App);
            }
            Message::CategorySelected(result) => {
                if let Err(e) = result {
                    self.status = e;
                }
                return self.api.load_phrases().map(cosmic::Action::App);
            }
            Message::Speak(text) => {
                if text.trim().is_empty() {
                    return Task::none();
                }
                self.partner.update_text(text.clone());
                self.status = fl!("status-speaking");
                self.speech_state = "starting".into();
                return self.api.speak(text).map(cosmic::Action::App);
            }
            Message::SpeechAction(action) => {
                self.status = match action {
                    "/api/speak/pause" => fl!("status-pausing"),
                    "/api/speak/resume" => fl!("status-resuming"),
                    _ => fl!("status-stopping"),
                };
                return self.api.speech_action(action).map(cosmic::Action::App);
            }
            Message::ClearDraft => {
                self.draft.clear();
                self.partner.update_text(String::new());
                return self.api.predict(String::new()).map(cosmic::Action::App);
            }
            Message::AccessEnter(target) => {
                if self.settings.auditory_fishing_enabled && !self.input_is_paused {
                    play_selection_sound();
                }
                let target_id = access_target_id(&target);
                self.known_access_targets.insert(target_id.clone(), target);
                return self.api.access_input("enter", Some(target_id), None).map(cosmic::Action::App);
            }
            Message::AccessExit(target) => {
                let target_id = access_target_id(&target);
                self.known_access_targets.remove(&target_id);
                if self
                    .access_press
                    .as_ref()
                    .is_some_and(|(current, _)| current == &target)
                {
                    self.access_press = None;
                }
                return self.api.access_input("exit", Some(target_id), None).map(cosmic::Action::App);
            }
            Message::AccessPress(target) => self.access_press = Some((target, Instant::now())),
            Message::AccessRelease(target) => {
                let held_long_enough = self.access_press.take().is_some_and(|(pressed, since)| {
                    pressed == target
                        && since.elapsed()
                            >= Duration::from_millis(
                                self.settings.hold_to_select_millis.max(0) as u64
                            )
                });
                if self.settings.hold_to_select_millis == 0 || held_long_enough {
                    return self.activate_access(target);
                }
                self.status = fl!("status-keep-holding");
            }
            Message::AccessActivate(target) => return self.activate_access(target),
            Message::AccessInputUpdated(result) => {
                if let Ok(state) = result {
                    self.input_is_paused = state.is_paused;
                    self.current_access_target_id = state.current_target_id;
                    self.access_dwell_progress = state.dwell_progress;
                    if let Some(id) = state.activation_target_id {
                        if let Some(target) = self.known_access_targets.get(&id).cloned() {
                            return self.activate_access(target);
                        }
                    }
                }
            }
            Message::InputEvent(event) => {
                if let cosmic::iced::Event::Window(window::Event::Resized(size)) = &event {
                    self.window_width = size.width;
                    self.window_height = size.height;
                }
                if let cosmic::iced::Event::Keyboard(keyboard::Event::KeyPressed {
                    key,
                    modifiers,
                    ..
                }) = &event
                {
                    if matches!(key, keyboard::Key::Named(keyboard::key::Named::Escape)) {
                        self.status = fl!("status-stopping");
                        return self
                            .api
                            .speech_action("/api/speak/stop")
                            .map(cosmic::Action::App);
                    }
                    if modifiers.control()
                        && matches!(key, keyboard::Key::Named(keyboard::key::Named::Enter))
                    {
                        let text = if self.page == Page::Screens {
                            self.board_sentence.clone()
                        } else {
                            self.draft.clone()
                        };
                        if !text.trim().is_empty() {
                            self.partner.update_text(text.clone());
                            self.status = fl!("status-speaking");
                            return self.api.speak(text).map(cosmic::Action::App);
                        }
                    }
                }
                if let cosmic::iced::Event::Keyboard(keyboard::Event::KeyReleased { key, .. }) = &event {
                    if let Some(token) = access_key_token(key) {
                        return self.api.access_input("keyup", None, Some(token)).map(cosmic::Action::App);
                    }
                }
                let is_switch = match &event {
                    cosmic::iced::Event::Keyboard(keyboard::Event::KeyPressed { key, .. }) => {
                        matches!(key, keyboard::Key::Named(keyboard::key::Named::Enter))
                            || matches!(key, keyboard::Key::Character(value) if value.as_str() == " ")
                    }
                    _ => false,
                };
                if let cosmic::iced::Event::Keyboard(keyboard::Event::KeyPressed { key, .. }) = &event {
                    if let Some(token) = access_key_token(key) {
                        if !self.settings.rest_mode_key_binding.is_empty()
                            && token.eq_ignore_ascii_case(&self.settings.rest_mode_key_binding)
                        {
                            return self.api.access_input("keydown", None, Some(token)).map(cosmic::Action::App);
                        }
                    }
                }
                if self.settings.scanning_enabled && is_switch {
                    let targets = self.current_access_targets();
                    if let Some(target) =
                        targets.get(self.scan_index % targets.len().max(1)).cloned()
                    {
                        return self.activate_access(target);
                    }
                }
                if let cosmic::iced::Event::Keyboard(keyboard::Event::KeyPressed { key, .. }) = &event {
                    if let Some(token) = access_key_token(key) {
                        return self.api.access_input("keydown", None, Some(token)).map(cosmic::Action::App);
                    }
                }
            }
            Message::PollSpeech => {
                if self.settings_category == SettingsCategory::Partner
                    && !self.partner.is_available()
                {
                    self.settings_category = SettingsCategory::Speech;
                }
                let speech_status = self.api.load_speech_state().map(cosmic::Action::App);
                let access_tick = self.api.access_input("tick", None, None).map(cosmic::Action::App);
                if self.settings.scanning_enabled {
                    let interval =
                        Duration::from_secs_f32(self.settings.scan_auto_advance_seconds.max(0.2));
                    if self.last_scan_advance.elapsed() >= interval {
                        let targets = self.current_access_targets();
                        if !targets.is_empty() {
                            self.scan_index = (self.scan_index + 1) % targets.len();
                            self.highlighted_access =
                                Some((targets[self.scan_index].clone(), Instant::now()));
                        }
                        self.last_scan_advance = Instant::now();
                    }
                }
                if self.highlighted_access.as_ref().is_some_and(|(_, since)| {
                    since.elapsed()
                        > Duration::from_millis(
                            self.settings.selection_highlight_millis.max(250) as u64
                        )
                        && !self.settings.scanning_enabled
                }) {
                    self.highlighted_access = None;
                }
                if self.settings.high_contrast_mode
                    && self.settings.force_dark_theme.is_none()
                    && cosmic::theme::is_dark() != self.core.system_is_dark()
                {
                    return Task::batch(vec![
                        cosmic::command::set_theme(theme_for_preference(
                            None,
                            self.core.system_is_dark(),
                            true,
                        )),
                        speech_status,
                        access_tick,
                    ]);
                }
                return Task::batch(vec![speech_status, access_tick]);
            }
            Message::PollPresetProgress => {
                return self.api.load_preset_progress().map(cosmic::Action::App);
            }
            Message::PollEditingAccess => {
                return self.api.load_editing_access().map(cosmic::Action::App);
            }
            Message::LoadedSpeechState(result) => match result {
                Ok(state) => {
                    let previous = std::mem::replace(&mut self.speech_state, state.state.clone());
                    self.status = if let Some(error) = state.error {
                        fl!("error-speech", error = error)
                    } else if state.paused {
                        fl!("status-paused")
                    } else if state.playing {
                        fl!("status-speaking")
                    } else {
                        match state.state.as_str() {
                            "cancelled" => fl!("status-stopped"),
                            "completed" | "idle" => fl!("status-ready"),
                            _ => self.status.clone(),
                        }
                    };
                    if state.state == "completed" && previous != "completed" {
                        return self.api.load_history().map(cosmic::Action::App);
                    }
                }
                Err(error) if !error.contains("Cannot reach") => self.status = error,
                Err(_) => {}
            },
            Message::SpeechStarted(result) => match result {
                Ok(()) => self.speech_state = "playing".into(),
                Err(error) => {
                    self.speech_state = "error".into();
                    self.status = error;
                }
            },
            Message::SpeechControlFinished(result) => {
                if let Err(error) = result {
                    self.status = error;
                }
            }
            Message::ActionFinished(result) => {
                self.status = result.map(|_| fl!("status-ready")).unwrap_or_else(|e| e);
            }
            Message::NewPhraseChanged(v) => self.new_phrase = v,
            Message::AddPhrase => {
                let value = std::mem::take(&mut self.new_phrase);
                if !value.trim().is_empty() {
                    return self.api.add_phrase(value).map(cosmic::Action::App);
                }
            }
            Message::DeletePhrase(id) => {
                return self.api.delete_phrase(id).map(cosmic::Action::App)
            }
            Message::EditPhrase(id) => {
                if let Some(phrase) = self.phrases.iter().find(|p| p.id == id) {
                    self.editing_phrase_id = Some(id);
                    self.phrase_editor_text = phrase.text.clone();
                    self.phrase_editor_voice = phrase.name.clone().unwrap_or_default();
                    self.phrase_editor_image_url = phrase.image_url.clone();
                    self.phrase_editor_recording_path = phrase.recording_path.clone();
                    self.phrase_editor_parent_id = phrase.parent_id.clone();
                    self.phrase_editor_hidden = phrase.is_hidden;
                }
            }
            Message::PhraseEditorChanged(v) => self.phrase_editor_text = v,
            Message::PhraseEditorVoiceChanged(v) => self.phrase_editor_voice = v,
            Message::PhraseEditorCategoryChanged(value) => {
                self.phrase_editor_parent_id = if value == "No category" {
                    None
                } else {
                    self.categories
                        .iter()
                        .find(|category| category.name.as_deref().unwrap_or("Unnamed") == value)
                        .map(|category| category.id.clone())
                };
            }
            Message::PhraseEditorHiddenChanged(value) => self.phrase_editor_hidden = value,
            Message::ChoosePhraseImage => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("Images", &["png", "jpg", "jpeg", "svg"])
                    .pick_file()
                {
                    return self.api.import_phrase_image(path).map(cosmic::Action::App);
                }
            }
            Message::PhraseImageImported(result) => match result {
                Ok(imported) => {
                    self.phrase_editor_image_url = Some(imported.url.clone());
                    return self.queue_images(vec![imported.url]);
                }
                Err(error) => self.status = format!("Image import failed: {error}"),
            },
            Message::ClearPhraseImage => self.phrase_editor_image_url = None,
            Message::ChoosePhraseRecording => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("Audio", &["wav", "ogg", "mp3", "flac", "m4a"])
                    .pick_file()
                {
                    self.phrase_editor_recording_path = Some(path.to_string_lossy().into_owned());
                }
            }
            Message::ClearPhraseRecording => self.phrase_editor_recording_path = None,
            Message::PlayRecording(path) => return play_audio_file(path).map(cosmic::Action::App),
            Message::MovePhrase(id, delta) => {
                return self.api.move_phrase(id, delta).map(cosmic::Action::App)
            }
            Message::SavePhraseEdit => {
                if let Some(id) = self.editing_phrase_id.take() {
                    return self
                        .api
                        .update_phrase(
                            id,
                            self.phrase_editor_text.clone(),
                            self.phrase_editor_voice.clone(),
                            self.phrase_editor_image_url.clone(),
                            self.phrase_editor_parent_id.clone(),
                            self.phrase_editor_recording_path.clone(),
                            self.phrase_editor_hidden,
                        )
                        .map(cosmic::Action::App);
                }
            }
            Message::CancelPhraseEdit => self.editing_phrase_id = None,
            Message::NewCategoryChanged(v) => self.new_category = v,
            Message::AddCategory => {
                let value = std::mem::take(&mut self.new_category);
                if !value.trim().is_empty() {
                    return self.api.add_category(value).map(cosmic::Action::App);
                }
            }
            Message::DeleteCategory(id) => {
                return self.api.delete_category(id).map(cosmic::Action::App)
            }
            Message::EditCategory(id) => {
                if let Some(category) = self.categories.iter().find(|category| category.id == id) {
                    self.editing_category_id = Some(id);
                    self.category_editor_name = category.name.clone().unwrap_or_default();
                }
            }
            Message::CategoryEditorChanged(value) => self.category_editor_name = value,
            Message::SaveCategoryEdit => {
                if let Some(id) = self.editing_category_id.take() {
                    return self
                        .api
                        .rename_category(id, self.category_editor_name.clone())
                        .map(cosmic::Action::App);
                }
            }
            Message::CancelCategoryEdit => self.editing_category_id = None,
            Message::MoveCategory(id, delta) => {
                return self.api.move_category(id, delta).map(cosmic::Action::App)
            }
            Message::ToggleManagePhrases => {
                self.manage_phrases = !self.manage_phrases;
            }
            Message::VoicePreviewSelected(voice) => self.preview_voice_name = Some(voice),
            Message::PreviewVoice => {
                if let Some(voice) = self.preview_voice_name.clone() {
                    self.status = fl!("voice-preview-playing");
                    return self
                        .api
                        .preview_voice(voice, fl!("voice-preview-sample"))
                        .map(cosmic::Action::App);
                }
            }
            Message::ApplyPreviewVoice => {
                if let Some(voice) = self.preview_voice_name.clone() {
                    self.settings.voice = voice.clone();
                    self.selected_voice_name = Some(voice.clone());
                    return self
                        .api
                        .put_json("/api/settings/voice", serde_json::json!({"voice": voice}))
                        .map(cosmic::Action::App);
                }
            }
            Message::RateChanged(rate) => {
                self.settings.speech_rate = rate;
                return self
                    .api
                    .put_json("/api/settings/rate", serde_json::json!({"rate": rate}))
                    .map(cosmic::Action::App);
            }
            Message::EngineChanged(engine) => {
                self.settings.tts_engine = engine.clone();
                return self
                    .api
                    .put_json(
                        "/api/settings/systemtts",
                        serde_json::json!({"ttsEngine": engine}),
                    )
                    .map(cosmic::Action::App);
            }
            Message::AzureEndpointChanged(value) => self.azure_endpoint = value,
            Message::AzureKeyChanged(value) => self.azure_key = value,
            Message::ReplaceAzureCredentials => {
                self.azure_endpoint.clear();
                self.azure_key.clear();
                self.replacing_azure_credentials = true;
            }
            Message::SaveAzureConfig => {
                return self
                    .api
                    .save_azure_config(self.azure_endpoint.clone(), self.azure_key.clone())
                    .map(cosmic::Action::App);
            }
            Message::AzureConfigSaved(result) => match result {
                Ok(()) => {
                    self.azure_credential_configured = true;
                    self.replacing_azure_credentials = false;
                    self.azure_endpoint.clear();
                    self.azure_key.clear();
                    self.status = fl!("status-ready");
                }
                Err(error) => self.status = error,
            },
            Message::PrimaryLanguageChanged(language) => {
                self.settings.primary_language = language.clone();
                self.settings.language = language.clone();
                return self
                    .api
                    .put_json(
                        "/api/settings",
                        serde_json::json!({"primaryLanguage": language}),
                    )
                    .map(cosmic::Action::App);
            }
            Message::SecondaryLanguageChanged(language) => {
                self.settings.secondary_language = if language == "Disabled" {
                    String::new()
                } else {
                    language.clone()
                };
                return self
                    .api
                    .put_json(
                        "/api/settings",
                        serde_json::json!({"secondaryLanguage": self.settings.secondary_language}),
                    )
                    .map(cosmic::Action::App);
            }
            Message::AppearanceChanged(appearance) => {
                let force_dark = match appearance.as_str() {
                    "Light" => Some(false),
                    "Dark" => Some(true),
                    _ => None,
                };
                self.settings.force_dark_theme = force_dark;
                let theme = theme_for_preference(
                    force_dark,
                    self.core.system_is_dark(),
                    self.settings.high_contrast_mode,
                );
                let value = force_dark
                    .map(serde_json::Value::Bool)
                    .unwrap_or(serde_json::Value::Null);
                return Task::batch(vec![
                    cosmic::command::set_theme(theme),
                    self.api
                        .patch_setting("forceDarkTheme", value)
                        .map(cosmic::Action::App),
                ]);
            }
            Message::SelectSettingsCategory(category) => {
                if category == SettingsCategory::Partner && !self.partner.is_available() {
                    return Task::none();
                }
                self.settings_category = category;
                if category == SettingsCategory::Dictionary {
                    return self.api.load_dictionary().map(cosmic::Action::App);
                }
            }
            Message::EditingAccessCodeChanged(value) => self.editing_access_code = value,
            Message::EditingAccessNewCodeChanged(value) => self.editing_access_new_code = value,
            Message::EditingAccessConfirmationChanged(value) => {
                self.editing_access_confirmation = value;
            }
            Message::ConfigureEditingAccess => {
                if self.editing_access_new_code != self.editing_access_confirmation {
                    self.status = fl!("editing-access-code-mismatch");
                } else {
                    let code = self.editing_access_new_code.clone();
                    self.editing_access_new_code.clear();
                    self.editing_access_confirmation.clear();
                    return self
                        .api
                        .configure_editing_access(code)
                        .map(cosmic::Action::App);
                }
            }
            Message::UnlockEditingAccess => {
                let code = std::mem::take(&mut self.editing_access_code);
                return self
                    .api
                    .unlock_editing_access(code)
                    .map(cosmic::Action::App);
            }
            Message::LockEditingAccess => {
                self.board_edit_mode = false;
                self.manage_phrases = false;
                return self.api.lock_editing_access().map(cosmic::Action::App);
            }
            Message::DisableEditingAccess => {
                let code = std::mem::take(&mut self.editing_access_code);
                return self
                    .api
                    .disable_editing_access(code)
                    .map(cosmic::Action::App);
            }
            Message::PartnerEnabled(enabled) => {
                self.settings.partner_window_enabled = enabled;
                self.partner.set_enabled(enabled);
                return self
                    .api
                    .put_json(
                        "/api/settings/partnerwindow",
                        serde_json::json!({"enabled": enabled}),
                    )
                    .map(cosmic::Action::App);
            }
            Message::PartnerFontChanged(font) => {
                self.settings.partner_window_font_size = font;
                self.partner.set_font_size(font);
                return self
                    .api
                    .partner_display(&self.settings)
                    .map(cosmic::Action::App);
            }
            Message::PartnerIdleChanged(enabled) => {
                self.settings.partner_window_idle_enabled = enabled;
                self.partner.set_idle_enabled(enabled);
                return self
                    .api
                    .partner_display(&self.settings)
                    .map(cosmic::Action::App);
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
                    "wordTypeColorScheme" => {
                        self.settings.word_type_color_scheme = if enabled {
                            "Fitzgerald".into()
                        } else {
                            "None".into()
                        }
                    }
                    "selectionSoundEnabled" => self.settings.selection_sound_enabled = enabled,
                    "auditoryFishingEnabled" => self.settings.auditory_fishing_enabled = enabled,
                    "usageLoggingEnabled" => self.settings.usage_logging_enabled = enabled,
                    "historyVisible" => self.settings.history_visible = enabled,
                    "boardShowMessageBar" => self.settings.board_show_message_bar = enabled,
                    "scanningEnabled" => self.settings.scanning_enabled = enabled,
                    "scanPlaybackAreaEnabled" => self.settings.scan_playback_area_enabled = enabled,
                    "scanInputFieldEnabled" => self.settings.scan_input_field_enabled = enabled,
                    "scanPhraseGridEnabled" => self.settings.scan_phrase_grid_enabled = enabled,
                    "scanCategoryItemsEnabled" => {
                        self.settings.scan_category_items_enabled = enabled
                    }
                    "scanTopBarEnabled" => self.settings.scan_top_bar_enabled = enabled,
                    _ => {}
                }
                let setting_value = if key == "wordTypeColorScheme" {
                    serde_json::Value::String(self.settings.word_type_color_scheme.clone())
                } else {
                    serde_json::Value::Bool(enabled)
                };
                let save = if matches!(
                    key,
                    "showLabels" | "showSymbols" | "labelAtTop" | "boardShowMessageBar" | "wordTypeColorScheme"
                ) {
                    self.board_graph.as_ref().map_or_else(
                        || {
                            self.api
                                .patch_setting(key, setting_value.clone())
                        },
                        |graph| {
                            self.api.patch_setting_and_reload_board(
                                key,
                                setting_value.clone(),
                                graph.board_set.id.clone(),
                            )
                        },
                    )
                } else {
                    self.api
                        .patch_setting(key, setting_value)
                };
                if key == "highContrastMode" {
                    let theme = theme_for_preference(
                        self.settings.force_dark_theme,
                        self.core.system_is_dark(),
                        enabled,
                    );
                    return Task::batch(vec![
                        cosmic::command::set_theme(theme),
                        save.map(cosmic::Action::App),
                    ]);
                }
                return save.map(cosmic::Action::App);
            }
            Message::SettingMillis(key, value) => {
                let value = value.max(0);
                match key {
                    "holdToSelectMillis" => self.settings.hold_to_select_millis = value,
                    "dwellToSelectMillis" => self.settings.dwell_to_select_millis = value,
                    "selectionHighlightMillis" => self.settings.selection_highlight_millis = value,
                    "selectionDebounceMillis" => self.settings.selection_debounce_millis = value,
                    _ => {}
                }
                return self
                    .api
                    .patch_setting(key, serde_json::json!(value))
                    .map(cosmic::Action::App);
            }
            Message::SettingFloat(key, value) => {
                match key {
                    "fontSizeScale" => self.settings.font_size_scale = value,
                    "buttonScale" => self.settings.button_scale = value,
                    "inputFieldScale" => self.settings.input_field_scale = value,
                    "scanDwellTimeSeconds" => self.settings.scan_dwell_time_seconds = value,
                    "scanAutoAdvanceSeconds" => self.settings.scan_auto_advance_seconds = value,
                    "pointerEmphasisScale" => self.settings.pointer_emphasis_scale = value,
                    _ => {}
                }
                return self
                    .api
                    .patch_setting(key, serde_json::json!(value))
                    .map(cosmic::Action::App);
            }
            Message::SettingString(key, value) => {
                match key {
                    "selectKeyBinding" => self.settings.select_key_binding = value.clone(),
                    "restModeKeyBinding" => self.settings.rest_mode_key_binding = value.clone(),
                    "pointerEmphasisStyle" => self.settings.pointer_emphasis_style = value.clone(),
                    "speechPolicy" => self.settings.speech_policy = value.clone(),
                    _ => {}
                }
                return self.api.patch_setting(key, serde_json::json!(value)).map(cosmic::Action::App);
            }
            Message::ToggleInputPause => {
                return self.api.access_input("togglePause", None, None).map(cosmic::Action::App);
            }
            Message::GridColumnsChanged(v) => {
                self.settings.grid_columns = v;
                return self
                    .api
                    .patch_setting("gridColumns", serde_json::json!(v))
                    .map(cosmic::Action::App);
            }
            Message::StartupBoardSetChanged(v) => {
                self.settings.startup_board_set_id = if v.trim().is_empty() {
                    None
                } else {
                    Some(v.clone())
                };
                return self
                    .api
                    .patch_setting("startupBoardSetId", serde_json::json!(v))
                    .map(cosmic::Action::App);
            }
            Message::StartupModeChanged(v) => {
                self.settings.startup_mode = v.clone();
                return self
                    .api
                    .patch_setting("startupMode", serde_json::json!(v))
                    .map(cosmic::Action::App);
            }
            Message::NewWordChanged(v) => self.new_word = v,
            Message::NewPhonemeChanged(v) => self.new_phoneme = v,
            Message::PronunciationAlphabetChanged(v) => self.pronunciation_alphabet = v,
            Message::AddPronunciation => {
                let word = std::mem::take(&mut self.new_word);
                let phoneme = std::mem::take(&mut self.new_phoneme);
                if !word.trim().is_empty() && !phoneme.trim().is_empty() {
                    return self
                        .api
                        .add_pronunciation(word, phoneme, self.pronunciation_alphabet.clone())
                        .map(cosmic::Action::App);
                }
            }
            Message::DeletePronunciation(word) => {
                return self.api.delete_pronunciation(word).map(cosmic::Action::App)
            }
            Message::TestPronunciation(word) => {
                return self.api.speak(word).map(cosmic::Action::App)
            }
            Message::ImportPronunciations => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("Pronunciation dictionary", &["json", "csv"])
                    .pick_file()
                {
                    return self
                        .api
                        .import_pronunciations(path)
                        .map(cosmic::Action::App);
                }
            }
            Message::ExportPronunciations => {
                if let Some(path) = rfd::FileDialog::new()
                    .set_file_name("wingmate-pronunciations.csv")
                    .add_filter("CSV", &["csv"])
                    .save_file()
                {
                    return self
                        .api
                        .export_pronunciations(path)
                        .map(cosmic::Action::App);
                }
            }
            Message::ClearHistory => return self.api.clear_history().map(cosmic::Action::App),
            Message::ImportHistory => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("JSON", &["json"])
                    .pick_file()
                {
                    return self.api.import_history(path).map(cosmic::Action::App);
                }
            }
            Message::ExportHistory => {
                if let Some(path) = rfd::FileDialog::new()
                    .set_file_name("wingmate-history.json")
                    .add_filter("JSON", &["json"])
                    .save_file()
                {
                    return self.api.export_history(path).map(cosmic::Action::App);
                }
            }
            Message::ExportBackup => {
                if let Some(path) = rfd::FileDialog::new()
                    .set_file_name("wingmate-backup.wingmate-backup")
                    .add_filter("Wingmate backup", &["wingmate-backup", "backup", "zip"])
                    .add_filter("All files", &["*"])
                    .save_file()
                {
                    let api = self.api.clone();
                    return Task::perform(
                        async move {
                            api.request_json::<serde_json::Value>(
                                Method::GET,
                                "/api/backup/export",
                                None,
                            )
                            .await
                            .map(|json| {
                                let file_name = json
                                    .get("fileName")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("wingmate-backup.wingmate-backup")
                                    .to_string();
                                (
                                    file_name,
                                    json.get("data")
                                        .and_then(|v| v.as_str())
                                        .unwrap_or("")
                                        .to_string(),
                                )
                            })
                            .and_then(|(name, data)| {
                                use base64::Engine as _;
                                let bytes = base64::engine::general_purpose::STANDARD
                                    .decode(&data)
                                    .map_err(|e| format!("Could not decode backup: {e}"))?;
                                std::fs::write(&path, bytes)
                                    .map_err(|e| format!("Could not write backup: {e}"))?;
                                Ok(name)
                            })
                        },
                        Message::BackupExported,
                    )
                    .map(cosmic::Action::App);
                }
            }
            Message::ImportBackup => {
                let dialog = rfd::FileDialog::new()
                    .add_filter(
                        "Wingmate backup",
                        &["wingmate-backup", "backup", "zip", "obz"],
                    )
                    .add_filter("All files", &["*"]);
                if let Some(path) = dialog.pick_file() {
                    let api = self.api.clone();
                    return Task::perform(
                        async move {
                            api.request_json::<serde_json::Value>(
                                Method::POST,
                                "/api/backup/import",
                                Some(
                                    serde_json::json!({"path": path.to_string_lossy().to_string()}),
                                ),
                            )
                            .await
                            .map(|json| {
                                json.get("status")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("ok")
                                    .to_string()
                            })
                        },
                        Message::BackupImported,
                    )
                    .map(cosmic::Action::App);
                }
            }
            Message::BackupExported(result) => match result {
                Ok(name) => self.status = format!("Backup saved: {name}"),
                Err(e) => self.status = format!("Backup failed: {e}"),
            },
            Message::BackupImported(result) => {
                match result {
                    Ok(status) if status == "ok" => {
                        self.status = "Backup restored successfully".to_string();
                        // Reload everything so the restored settings/voices/boards appear immediately.
                        let api = self.api.clone();
                        return Task::batch([
                            api.load_settings().map(cosmic::Action::App),
                            api.load_selected_voice().map(cosmic::Action::App),
                            api.load_voices().map(cosmic::Action::App),
                            api.load_board_sets().map(cosmic::Action::App),
                            api.load_phrases().map(cosmic::Action::App),
                            api.load_categories().map(cosmic::Action::App),
                            api.load_history().map(cosmic::Action::App),
                            api.load_dictionary().map(cosmic::Action::App),
                        ]);
                    }
                    Ok(status) => self.status = format!("Backup import: {status}"),
                    Err(e) => self.status = format!("Backup import failed: {e}"),
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
            Message::OnboardingMode(screens) => self.onboarding_screens = screens,
            Message::CompleteOnboarding => {
                self.settings.welcome_flow_completed = true;
                self.settings.feature_usage_reporting_enabled = false;
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
                self.last_workspace = self.page;
                return self
                    .api
                    .complete_onboarding(false, self.onboarding_screens)
                    .map(cosmic::Action::App);
            }
            Message::OpenBoardSet(id, edit) => {
                self.board_edit_mode = edit;
                self.status = fl!("status-loading-board");
                return self.api.load_board_graph(id, edit).map(cosmic::Action::App);
            }
            Message::ExitBoardSet => {
                self.board_graph = None;
                self.active_board_id = None;
                self.native_keyboard_return_pending = false;
                self.board_sentence_tokens.clear();
                self.board_sentence.clear();
                return self.api.load_board_sets().map(cosmic::Action::App);
            }
            Message::BoardSetNameChanged(v) => self.new_board_set = v,
            Message::PageNameChanged(v) => self.new_page = v,
            Message::BoardRowsChanged(v) => self.board_rows = v,
            Message::BoardColumnsChanged(v) => self.board_columns = v,
            Message::BoardTemplateChanged(value) => {
                if self.new_board_set.trim().is_empty() && value.starts_with("Quick Core ") {
                    self.new_board_set = value.clone();
                }
                self.board_template = value;
            }
            Message::CreateBoardSet => {
                let name = std::mem::take(&mut self.new_board_set);
                if !name.trim().is_empty() {
                    self.preset_importing = true;
                    self.preset_progress = None;
                    self.status = fl!("status-creating-screen");
                    if self.board_template.starts_with("Quick Core ") {
                        self.status = fl!("status-loading-quick-core");
                        self.preset_importing = true;
                        self.preset_progress = Some(0.0);
                    }
                    return self
                        .api
                        .create_board_set(
                            name,
                            self.board_rows,
                            self.board_columns,
                            self.board_template.clone(),
                        )
                        .map(cosmic::Action::App);
                }
            }
            Message::ImportBoardSet => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("Open Board Format", &["obf", "obz", "json"])
                    .pick_file()
                {
                    return self
                        .api
                        .import_board_set(path.to_string_lossy().into_owned())
                        .map(cosmic::Action::App);
                }
            }
            Message::ExportBoardSet(id, name) => {
                if let Some(path) = rfd::FileDialog::new()
                    .set_file_name(format!("{}.obz", safe_filename(&name)))
                    .add_filter("Open Board Archive", &["obz"])
                    .save_file()
                {
                    return self.api.export_board_set(id, path).map(cosmic::Action::App);
                }
            }
            Message::DuplicateBoardSet(id) => {
                return self
                    .api
                    .board_set_action(id, "duplicate", Method::POST)
                    .map(cosmic::Action::App)
            }
            Message::ToggleBoardSetLock(id) => {
                return self
                    .api
                    .board_set_action(id, "lock", Method::PUT)
                    .map(cosmic::Action::App)
            }
            Message::DeleteBoardSet(id) => {
                return self.api.delete_board_set(id).map(cosmic::Action::App)
            }
            Message::CreatePage => {
                if let Some(graph) = &self.board_graph {
                    let name = std::mem::take(&mut self.new_page);
                    if !name.trim().is_empty() {
                        return self
                            .api
                            .create_board(
                                graph.board_set.id.clone(),
                                name,
                                self.board_rows,
                                self.board_columns,
                            )
                            .map(cosmic::Action::App);
                    }
                }
            }
            Message::CurrentPageNameChanged(value) => self.current_page_name = value,
            Message::RenameCurrentPage => {
                if let (Some(graph), Some(board_id)) = (&self.board_graph, &self.active_board_id) {
                    return self
                        .api
                        .rename_board(
                            graph.board_set.id.clone(),
                            board_id.clone(),
                            self.current_page_name.clone(),
                        )
                        .map(cosmic::Action::App);
                }
            }
            Message::ResizeCurrentPage => {
                if let (Some(graph), Some(board_id)) = (&self.board_graph, &self.active_board_id) {
                    return self
                        .api
                        .resize_board(
                            graph.board_set.id.clone(),
                            board_id.clone(),
                            self.board_rows,
                            self.board_columns,
                        )
                        .map(cosmic::Action::App);
                }
            }
            Message::DeleteCurrentPage => {
                if let (Some(graph), Some(board_id)) = (&self.board_graph, &self.active_board_id) {
                    return self
                        .api
                        .delete_board(graph.board_set.id.clone(), board_id.clone())
                        .map(cosmic::Action::App);
                }
            }
            Message::SetCurrentPageAsHome => {
                if let (Some(graph), Some(board_id)) = (&self.board_graph, &self.active_board_id) {
                    return self
                        .api
                        .set_root_board(graph.board_set.id.clone(), board_id.clone())
                        .map(cosmic::Action::App);
                }
            }
            Message::PageActivationChanged(value) => {
                if let (Some(graph), Some(board_id)) = (&self.board_graph, &self.active_board_id) {
                    return self
                        .api
                        .update_page_behavior(
                            graph.board_set.id.clone(),
                            board_id.clone(),
                            Some(value),
                            None,
                        )
                        .map(cosmic::Action::App);
                }
            }
            Message::PageReturnChanged(value) => {
                if let (Some(graph), Some(board_id)) = (&self.board_graph, &self.active_board_id) {
                    return self
                        .api
                        .update_page_behavior(
                            graph.board_set.id.clone(),
                            board_id.clone(),
                            None,
                            Some(value),
                        )
                        .map(cosmic::Action::App);
                }
            }
            Message::SelectBoard(id) => {
                self.active_board_id = Some(id.clone());
                if let Some(board) = self
                    .board_graph
                    .as_ref()
                    .and_then(|graph| graph.boards.iter().find(|board| board.id == id))
                {
                    self.current_page_name = board.name.clone().unwrap_or_default();
                    if let Some(grid) = &board.grid {
                        self.board_rows = grid.order.len() as i32;
                        self.board_columns =
                            grid.order.iter().map(Vec::len).max().unwrap_or(1) as i32;
                    }
                }
                return self.queue_active_board_images();
            }
            Message::ToggleBoardEdit => {
                self.board_edit_mode = !self.board_edit_mode;
                if self.board_edit_mode {
                    self.board_sentence_tokens.clear();
                    self.board_sentence.clear();
                    if let Some(board) = self.board_graph.as_ref().and_then(|graph| {
                        graph.boards.iter().find(|board| {
                            Some(board.id.as_str()) == self.active_board_id.as_deref()
                        })
                    }) {
                        self.current_page_name = board.name.clone().unwrap_or_default();
                        if let Some(grid) = &board.grid {
                            self.board_rows = grid.order.len() as i32;
                            self.board_columns =
                                grid.order.iter().map(Vec::len).max().unwrap_or(1) as i32;
                        }
                    }
                }
            }
            Message::SelectBoardCell(row, column) => {
                self.editing_cell = Some((row, column));
                self.cell_label.clear();
                self.cell_vocalization.clear();
                self.cell_image_url = None;
                self.cell_background_color.clear();
                self.cell_word_type = "Automatic".into();
                self.cell_hidden = false;
                self.cell_linked_board_id = None;
                self.cell_actions.clear();
                self.symbol_query.clear();
                self.symbols.clear();
                let details = self.board_cell(row, column).map(|button| {
                    let image_id = button.image_id.clone();
                    let image_url = image_id.as_ref().and_then(|image_id| {
                        let active_id = self.active_board_id.clone();
                        let graph = &self.board_graph;
                        graph
                            .as_ref()?
                            .boards
                            .iter()
                            .find(|board| board.id == active_id.as_deref().unwrap_or_default())?
                            .images
                            .iter()
                            .find(|image| &image.id == image_id)?
                            .url
                            .clone()
                            .or_else(|| {
                                graph
                                    .as_ref()?
                                    .boards
                                    .iter()
                                    .find(|board| {
                                        board.id == active_id.as_deref().unwrap_or_default()
                                    })?
                                    .images
                                    .iter()
                                    .find(|image| &image.id == image_id)?
                                    .path
                                    .clone()
                            })
                    });
                    (
                        button.label.clone().unwrap_or_default(),
                        button.vocalization.clone().unwrap_or_default(),
                        image_url,
                        button.background_color.clone().unwrap_or_default(),
                        button.extensions.get("ext_wingmate_word_type")
                            .and_then(serde_json::Value::as_str)
                            .unwrap_or("Automatic").to_string(),
                        button.hidden,
                        button.load_board.as_ref().map(|target| target.id.clone()),
                        if button.actions.is_empty() {
                            button.action.clone().unwrap_or_default()
                        } else {
                            button.actions.join(", ")
                        },
                    )
                });
                if let Some((
                    label,
                    vocalization,
                    image_url,
                    background,
                    word_type,
                    hidden,
                    linked,
                    actions,
                )) = details
                {
                    self.cell_label = label;
                    self.cell_vocalization = vocalization;
                    self.cell_image_url = image_url;
                    self.cell_background_color = background;
                    self.cell_word_type = word_type;
                    self.cell_hidden = hidden;
                    self.cell_linked_board_id = linked;
                    self.cell_actions = actions;
                }
            }
            Message::CellLabelChanged(v) => self.cell_label = v,
            Message::CellVoiceChanged(v) => self.cell_vocalization = v,
            Message::CellSymbolQueryChanged(v) => {
                self.symbol_query = v;
            }
            Message::CellSymbolPackageChanged(v) => {
                self.symbol_package = v;
                self.symbols.clear();
            }
            Message::CellSymbolSearch => {
                let query = self.symbol_query.clone();
                if query.trim().is_empty() {
                    return Task::none();
                }
                self.symbol_loading = true;
                let api = self.api.clone();
                let locale = self.settings.primary_language.clone();
                let symbol_package = self.symbol_package.clone();
                return Task::perform(
                    async move {
                        api.request_json(
                            Method::POST,
                            "/api/symbols/search",
                            Some(serde_json::json!({
                                "query": query,
                                "locale": locale,
                                "symbolPackage": symbol_package,
                            })),
                        )
                        .await
                    },
                    Message::CellSymbolsLoaded,
                )
                .map(cosmic::Action::App);
            }
            Message::CellSymbolsLoaded(result) => {
                self.symbol_loading = false;
                match result {
                    Ok(search) => {
                        let sources = search
                            .symbols
                            .iter()
                            .filter_map(|symbol| symbol.image_url.clone())
                            .collect();
                        self.symbols = search.symbols;
                        return self.queue_images(sources);
                    }
                    Err(e) => self.status = format!("Symbol search failed: {e}"),
                }
            }
            Message::CellSymbolPicked(index) => {
                if let Some(symbol) = self.symbols.get(index) {
                    self.cell_image_url = symbol.image_url.clone();
                    if let Some(source) = self.cell_image_url.clone() {
                        return self.queue_images(vec![source]);
                    }
                }
            }
            Message::CellSymbolCleared => self.cell_image_url = None,
            Message::CellLocalImage => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("Images", &["png", "jpg", "jpeg", "svg"])
                    .pick_file()
                {
                    return self.api.import_image(path).map(cosmic::Action::App);
                }
            }
            Message::CellLocalImageImported(result) => match result {
                Ok(imported) => {
                    self.cell_image_url = Some(imported.url.clone());
                    return self.queue_images(vec![imported.url]);
                }
                Err(error) => self.status = format!("Image import failed: {error}"),
            },
            Message::CellBackgroundChanged(value) => self.cell_background_color = value,
            Message::CellWordTypeChanged(value) => self.cell_word_type = value,
            Message::CellHiddenChanged(value) => self.cell_hidden = value,
            Message::CellLinkedBoardChanged(value) => {
                self.cell_linked_board_id = if value == "No page link" {
                    None
                } else {
                    self.board_graph.as_ref().and_then(|graph| {
                        graph
                            .boards
                            .iter()
                            .find(|board| board.name.as_deref().unwrap_or("Untitled page") == value)
                            .map(|board| board.id.clone())
                    })
                };
            }
            Message::CellActionsChanged(value) => self.cell_actions = value,
            Message::SaveBoardCell => {
                if let (Some(graph), Some(board_id), Some((row, column))) = (
                    &self.board_graph,
                    &self.active_board_id,
                    self.editing_cell.take(),
                ) {
                    return self
                        .api
                        .save_board_cell(
                            graph.board_set.id.clone(),
                            board_id.clone(),
                            row,
                            column,
                            self.cell_label.clone(),
                            self.cell_vocalization.clone(),
                            self.cell_image_url.clone(),
                            self.cell_background_color.clone(),
                            self.cell_word_type.clone(),
                            self.cell_hidden,
                            self.cell_linked_board_id.clone(),
                            self.cell_actions
                                .split(',')
                                .map(str::trim)
                                .filter(|value| !value.is_empty())
                                .map(str::to_owned)
                                .collect(),
                        )
                        .map(cosmic::Action::App);
                }
            }
            Message::ClearBoardCell => {
                if let (Some(graph), Some(board_id), Some((row, column))) = (
                    &self.board_graph,
                    &self.active_board_id,
                    self.editing_cell.take(),
                ) {
                    return self
                        .api
                        .clear_board_cell(graph.board_set.id.clone(), board_id.clone(), row, column)
                        .map(cosmic::Action::App);
                }
            }
            Message::CancelBoardCell => self.editing_cell = None,
        }
        Task::none()
    }

    fn view(&self) -> Element<'_, Message> {
        if self.page == Page::Welcome {
            return column![
                container(self.welcome_view()).padding(40).center(Fill),
                self.status_view(),
            ]
            .height(Fill)
            .into();
        }
        if self.page == Page::Fullscreen {
            return column![self.fullscreen_view(), self.status_view()]
                .height(Fill)
                .into();
        }

        let content: Element<'_, Message> = match self.page {
            Page::Welcome => unreachable!(),
            Page::Communicate => self.communicate_view(),
            Page::Screens => self.screens_view(),
            Page::Settings => self.settings_view(),
            Page::Fullscreen => unreachable!(),
        };

        let content_padding = if self.page == Page::Screens && self.board_graph.is_some() {
            10
        } else {
            24
        };

        let page_content: Element<'_, Message> = column![
            container(content)
                .padding(content_padding)
                .width(Fill)
                .height(Fill),
            self.status_view(),
        ]
        .height(Fill)
        .into();
        let enabled = matches!(self.page, Page::Communicate | Page::Screens)
            && (self.settings.dwell_to_select_millis > 0 || !self.settings.select_key_binding.is_empty());
        if !enabled {
            return page_content;
        }

        let fab_label = if self.input_is_paused {
            "Resume input"
        } else {
            "Rest mode"
        };
        let fab = aac_toolbar_button(
            if self.input_is_paused {
                "media-playback-start-symbolic"
            } else {
                "media-playback-pause-symbolic"
            },
            fab_label,
            Message::ToggleInputPause,
        );
        let fab_layer = container(fab)
            .width(Fill)
            .height(Fill)
            .align_x(cosmic::iced::alignment::Horizontal::Right)
            .align_y(cosmic::iced::alignment::Vertical::Bottom)
            .padding(Padding {
                top: 0.0,
                right: 24.0,
                bottom: 24.0,
                left: 0.0,
            });
        let mut layers = vec![page_content, fab_layer.into()];
        if self.input_is_paused {
            layers.push(
                container(text("Rest mode — hover selection and the Select key are paused").size(16))
                    .width(Fill)
                    .height(Fill)
                    .align_x(cosmic::iced::alignment::Horizontal::Center)
                    .align_y(cosmic::iced::alignment::Vertical::Top)
                    .padding(16)
                    .into(),
            );
        }
        stack(layers).width(Fill).height(Fill).into()
    }
}

impl Wingmate {

    fn status_view(&self) -> Element<'_, Message> {
        let status_text = if self.status.trim().is_empty() {
            fl!("status-ready")
        } else {
            self.status.clone()
        };
        let is_error = status_is_error(&status_text);
        container(
            row![
                symbolic_icon(if is_error {
                    "dialog-error-symbolic"
                } else {
                    "dialog-information-symbolic"
                })
                .size(20)
                .icon(),
                text(status_text).size(15).width(Fill),
            ]
            .spacing(10)
            .align_y(cosmic::iced::alignment::Alignment::Center),
        )
        .class(if is_error {
            cosmic::theme::iced::Container::Custom(Box::new(|theme| {
                let component = &theme.cosmic().destructive;
                cosmic::iced::widget::container::Style {
                    background: Some(component.base.into()),
                    text_color: Some(component.on.into()),
                    icon_color: Some(component.on.into()),
                    ..Default::default()
                }
            }))
        } else {
            cosmic::theme::iced::Container::Secondary
        })
        .padding([10, 24])
        .width(Fill)
        .into()
    }

    fn queue_images(&mut self, sources: Vec<String>) -> Task<cosmic::Action<Message>> {
        let sources = sources
            .into_iter()
            .filter(|source| {
                !source.trim().is_empty()
                    && !self.image_cache.contains_key(source)
                    && self.pending_images.insert(source.clone())
            })
            .collect::<Vec<_>>();
        let tasks = sources
            .chunks(16)
            .map(|chunk| {
                self.api
                    .fetch_images(chunk.to_vec())
                    .map(cosmic::Action::App)
            })
            .collect::<Vec<_>>();
        Task::batch(tasks)
    }

    fn cache_loaded_image(&mut self, source: String, result: Result<LoadedImageData, String>) {
        self.pending_images.remove(&source);
        match result {
            Ok(payload) => {
                if payload.bytes.is_empty() {
                    self.status = fl!("error-image-empty");
                    return;
                }
                let is_svg = payload.content_type.contains("svg")
                    || payload
                        .bytes
                        .windows(4)
                        .any(|window| window.eq_ignore_ascii_case(b"<svg"));
                let visual = if is_svg {
                    CachedVisual::Svg(svg::Handle::from_memory(payload.bytes))
                } else {
                    CachedVisual::Raster(image::Handle::from_bytes(payload.bytes))
                };
                self.image_cache_order.push_back(source.clone());
                self.image_cache.insert(source, visual);
                while self.image_cache_order.len() > MAX_IMAGE_CACHE_ENTRIES {
                    if let Some(oldest) = self.image_cache_order.pop_front() {
                        self.image_cache.remove(&oldest);
                    }
                }
            }
            Err(error) => self.status = fl!("error-image-load", error = error),
        }
    }

    fn queue_active_board_images(&mut self) -> Task<cosmic::Action<Message>> {
        let sources: Vec<String> = self
            .board_graph
            .as_ref()
            .and_then(|graph| {
                let active_id = self
                    .active_board_id
                    .as_deref()
                    .unwrap_or(&graph.board_set.root_board_id);
                graph.boards.iter().find(|board| board.id == active_id)
            })
            .map(|board| {
                board
                    .images
                    .iter()
                    .filter_map(|item| item.url.clone().or_else(|| item.path.clone()))
                    .collect()
            })
            .unwrap_or_default();
        // OBZ media is already local. Cache its handles in the same update that
        // opens the page so the first rendered frame includes the symbols.
        let mut deferred = Vec::new();
        for source in sources {
            if source.starts_with('/') {
                if self.image_cache.contains_key(&source) {
                    continue;
                }
                let result = std::fs::read(&source)
                    .map(|bytes| LoadedImageData {
                        content_type: local_image_content_type(&source).into(),
                        bytes,
                    })
                    .map_err(|error| format!("Could not read local symbol: {error}"));
                self.cache_loaded_image(source, result);
            } else {
                deferred.push(source);
            }
        }
        self.queue_images(deferred)
    }

    fn image_for(&self, source: Option<&str>, height: f32) -> Option<Element<'_, Message>> {
        match self.image_cache.get(source?)?.clone() {
            CachedVisual::Raster(handle) => Some(
                image(handle)
                    .height(height)
                    .width(Fill)
                    .content_fit(cosmic::iced::ContentFit::Contain)
                    .into(),
            ),
            CachedVisual::Svg(handle) => Some(
                svg(handle)
                    .height(height)
                    .width(Fill)
                    .content_fit(cosmic::iced::ContentFit::Contain)
                    .into(),
            ),
        }
    }

    /// Compose silently: append text to the draft with word spacing.
    fn compose_phrase(&mut self, text: &str) {
        let text = text.trim();
        if text.is_empty() {
            return;
        }
        if !self.draft.is_empty() && !self.draft.ends_with(' ') {
            self.draft.push(' ');
        }
        self.draft.push_str(text);
    }

    /// Resolve a phrase's access target, honoring the global speech policy.
    /// Sentence-only mode composes silently instead of speaking on selection;
    /// linked-board phrases always navigate.
    fn phrase_access_target(&self, phrase: &Phrase) -> AccessTarget {
        if let Some(id) = phrase.linked_board_id.clone() {
            return AccessTarget::Category(Some(id));
        }
        let spoken = phrase.name.clone().unwrap_or_else(|| phrase.text.clone());
        if self.settings.speech_policy == "SentenceOnly" {
            AccessTarget::Insert(spoken)
        } else {
            phrase
                .recording_path
                .clone()
                .map(AccessTarget::Recording)
                .unwrap_or_else(|| AccessTarget::Speak(spoken))
        }
    }

    fn activate_access(&mut self, target: AccessTarget) -> Task<cosmic::Action<Message>> {
        let debounce = Duration::from_millis(self.settings.selection_debounce_millis.max(0) as u64);
        if self
            .last_access_activation
            .is_some_and(|last| last.elapsed() < debounce)
        {
            return Task::none();
        }
        self.last_access_activation = Some(Instant::now());
        self.highlighted_access = Some((target.clone(), Instant::now()));
        if self.settings.selection_sound_enabled {
            play_selection_sound();
        }
        match target {
            AccessTarget::Speak(text) => {
                self.partner.update_text(text.clone());
                self.status = fl!("status-speaking");
                self.speech_state = "starting".into();
                self.api.speak(text).map(cosmic::Action::App)
            }
            AccessTarget::Recording(path) => {
                self.status = fl!("status-playing-recording");
                play_audio_file(path).map(cosmic::Action::App)
            }
            AccessTarget::Insert(text) => {
                self.compose_phrase(&text);
                self.partner.update_text(self.draft.clone());
                Task::none()
            }
            AccessTarget::BoardButton(board_id, button_id) => self
                .api
                .update_board_session(
                    board_id,
                    "activate",
                    Some(button_id),
                    self.board_sentence_tokens.clone(),
                )
                .map(cosmic::Action::App),
            AccessTarget::Category(category_id) => {
                self.selected_category = category_id.clone();
                self.api
                    .select_category(category_id)
                    .map(cosmic::Action::App)
            }
        }
    }

    fn current_access_targets(&self) -> Vec<AccessTarget> {
        match self.page {
            Page::Communicate => {
                let mut targets = Vec::new();
                if self.settings.scan_category_items_enabled {
                    targets.push(AccessTarget::Category(None));
                    targets.extend(
                        self.categories
                            .iter()
                            .map(|category| AccessTarget::Category(Some(category.id.clone()))),
                    );
                }
                if self.settings.scan_phrase_grid_enabled {
                    targets.extend(self.phrases.iter().filter(|phrase| !phrase.is_hidden).map(
                        |phrase| self.phrase_access_target(phrase),
                    ));
                }
                targets
            }
            Page::Screens if !self.board_edit_mode && self.settings.scan_phrase_grid_enabled => {
                let Some(graph) = &self.board_graph else {
                    return Vec::new();
                };
                let active = self
                    .active_board_id
                    .as_deref()
                    .unwrap_or(&graph.board_set.root_board_id);
                graph
                    .boards
                    .iter()
                    .find(|board| board.id == active)
                    .into_iter()
                    .flat_map(|board| {
                        board
                            .buttons
                            .iter()
                            .filter(|button| !button.hidden)
                            .map(|button| {
                                AccessTarget::BoardButton(board.id.clone(), button.id.clone())
                            })
                    })
                    .collect()
            }
            _ => Vec::new(),
        }
    }

    fn access_widget<'a>(
        &self,
        content: Element<'a, Message>,
        target: AccessTarget,
    ) -> Element<'a, Message> {
        let area = mouse_area(content)
            .on_enter(Message::AccessEnter(target.clone()))
            .on_exit(Message::AccessExit(target.clone()));
        if self.settings.hold_to_select_millis > 0 {
            area.on_press(Message::AccessPress(target.clone()))
                .on_release(Message::AccessRelease(target))
                .into()
        } else {
            area.into()
        }
    }

    fn access_highlighted(&self, target: &AccessTarget) -> bool {
        let selected = self.highlighted_access
            .as_ref()
            .is_some_and(|(current, _)| current == target);
        let emphasized = self.settings.pointer_emphasis_style != "System"
            && self.current_access_target_id.as_deref() == Some(access_target_id(target).as_str());
        selected || emphasized
    }

    fn navigate(&mut self, page: Page) -> Task<cosmic::Action<Message>> {
        let returning_to_open_screen = self.page == Page::Settings
            && page == Page::Screens
            && self.last_workspace == Page::Screens
            && self.board_graph.is_some();
        if matches!(page, Page::Communicate | Page::Screens) {
            self.last_workspace = page;
        }
        self.page = page;
        match page {
            Page::Screens if returning_to_open_screen => Task::none(),
            Page::Screens => {
                self.board_graph = None;
                self.api.load_board_sets().map(cosmic::Action::App)
            }
            Page::Communicate if self.settings.history_visible => {
                self.api.load_history().map(cosmic::Action::App)
            }
            _ => Task::none(),
        }
    }

    fn return_native_keyboard_to_board(&mut self) -> Task<cosmic::Action<Message>> {
        self.board_sentence = self.draft.clone();
        self.board_sentence_tokens = if self.draft.is_empty() {
            Vec::new()
        } else {
            vec![self.draft.clone()]
        };
        self.partner.update_text(self.board_sentence.clone());
        self.native_keyboard_return_pending = false;
        self.page = Page::Screens;
        self.last_workspace = Page::Screens;
        self.status = fl!("status-ready");
        self.queue_active_board_images()
    }

    fn communicate_view(&self) -> Element<'_, Message> {
        if self.editing_category_id.is_some() {
            return column![
                text(fl!("category-rename-title")).size(30),
                text_input(&fl!("category-name"), &self.category_editor_name)
                    .on_input(Message::CategoryEditorChanged)
                    .on_submit(Message::SaveCategoryEdit)
                    .padding(12),
                row![
                    labeled_icon_button(
                        "document-save-symbolic",
                        "Save",
                        Message::SaveCategoryEdit
                    ),
                    labeled_icon_button(
                        "window-close-symbolic",
                        "Cancel",
                        Message::CancelCategoryEdit
                    ),
                ]
                .spacing(10),
            ]
            .spacing(16)
            .into();
        }
        if self.editing_phrase_id.is_some() {
            let mut category_options = vec!["No category".to_string()];
            category_options.extend(
                self.categories
                    .iter()
                    .map(|category| category.name.clone().unwrap_or_else(|| "Unnamed".into())),
            );
            let selected_category = self
                .phrase_editor_parent_id
                .as_ref()
                .and_then(|id| {
                    self.categories
                        .iter()
                        .find(|category| &category.id == id)
                        .map(|category| category.name.clone().unwrap_or_else(|| "Unnamed".into()))
                })
                .or_else(|| Some("No category".into()));
            return column![
                text(fl!("phrase-edit-title")).size(30),
                text_input(&fl!("phrase-button-label"), &self.phrase_editor_text)
                    .on_input(Message::PhraseEditorChanged)
                    .padding(12),
                text_input(
                    "Speak something different (optional)",
                    &self.phrase_editor_voice
                )
                .on_input(Message::PhraseEditorVoiceChanged)
                .padding(12),
                row![
                    pick_list(
                        category_options,
                        selected_category,
                        Message::PhraseEditorCategoryChanged
                    ),
                    checkbox(self.phrase_editor_hidden)
                        .label(fl!("phrase-hide"))
                        .on_toggle(Message::PhraseEditorHiddenChanged),
                ]
                .spacing(10)
                .wrap(),
                row![
                    labeled_icon_button(
                        "document-open-symbolic",
                        "Choose image…",
                        Message::ChoosePhraseImage
                    ),
                    if self.phrase_editor_image_url.is_some() {
                        labeled_icon_button(
                            "edit-delete-symbolic",
                            "Remove image",
                            Message::ClearPhraseImage,
                        )
                    } else {
                        Space::new().into()
                    },
                    labeled_icon_button(
                        "audio-x-generic-symbolic",
                        "Choose recording…",
                        Message::ChoosePhraseRecording
                    ),
                    if let Some(path) = &self.phrase_editor_recording_path {
                        compact_icon_button(
                            "media-playback-start-symbolic",
                            "Play recording",
                            Message::PlayRecording(path.clone()),
                        )
                    } else {
                        Space::new().into()
                    },
                    if self.phrase_editor_recording_path.is_some() {
                        compact_icon_button(
                            "edit-delete-symbolic",
                            "Remove recording",
                            Message::ClearPhraseRecording,
                        )
                    } else {
                        Space::new().into()
                    },
                ]
                .spacing(8)
                .wrap(),
                if let Some(preview) =
                    self.image_for(self.phrase_editor_image_url.as_deref(), 140.0)
                {
                    preview
                } else {
                    Space::new().into()
                },
                row![
                    labeled_icon_button("document-save-symbolic", "Save", Message::SavePhraseEdit),
                    labeled_icon_button(
                        "window-close-symbolic",
                        "Cancel",
                        Message::CancelPhraseEdit
                    ),
                ]
                .spacing(10)
            ]
            .spacing(16)
            .into();
        }
        let input: Element<'_, Message> = container(
            text_input(&fl!("communicate-input-placeholder"), &self.draft)
                .on_input(Message::DraftChanged)
                .on_submit(Message::Speak(self.draft.clone()))
                .padding(16)
                .size(self.settings.font_px(22.0)),
        )
        .height(self.settings.input_px(56.0))
        .into();

        let predictions = row(self.predictions.iter().take(6).map(|word| {
            button(text(word).size(self.settings.font_px(15.0)))
                .on_press(Message::PredictionSelected(word.clone()))
                .height(self.settings.button_px(48.0))
                .padding([10, 14])
                .into()
        }))
        .spacing(8);

        let all_target = AccessTarget::Category(None);
        let mut all_button = button(text(fl!("communicate-all")).size(self.settings.font_px(16.0)))
            .height(self.settings.button_px(48.0))
            .padding([10, 16]);
        if self.settings.hold_to_select_millis == 0 {
            all_button = all_button.on_press(Message::AccessActivate(all_target.clone()));
        }
        if self.access_highlighted(&all_target) {
            all_button = all_button.class(cosmic::theme::iced::Button::Positive);
        }
        let mut categories = row![self.access_widget(all_button.into(), all_target)].spacing(8);
        for category in &self.categories {
            let target = AccessTarget::Category(Some(category.id.clone()));
            let mut category_button = button(
                text(category.name.as_deref().unwrap_or("Unnamed"))
                    .size(self.settings.font_px(16.0)),
            )
            .height(self.settings.button_px(48.0))
            .padding([10, 16]);
            if self.settings.hold_to_select_millis == 0 {
                category_button = category_button.on_press(Message::AccessActivate(target.clone()));
            }
            if self.access_highlighted(&target) {
                category_button = category_button.class(cosmic::theme::iced::Button::Positive);
            }
            let category_activation = self.access_widget(category_button.into(), target);
            let category_content: Element<'_, Message> = if self.manage_phrases {
                row![
                    category_activation,
                    compact_icon_button(
                        "go-up-symbolic",
                        "Move category left",
                        Message::MoveCategory(category.id.clone(), -1)
                    ),
                    compact_icon_button(
                        "go-down-symbolic",
                        "Move category right",
                        Message::MoveCategory(category.id.clone(), 1)
                    ),
                    compact_icon_button(
                        "document-edit-symbolic",
                        "Rename category",
                        Message::EditCategory(category.id.clone())
                    ),
                    compact_icon_button(
                        "edit-delete-symbolic",
                        "Delete category",
                        Message::DeleteCategory(category.id.clone()),
                    )
                ]
                .spacing(2)
                .into()
            } else {
                category_activation
            };
            categories = categories.push(category_content);
        }
        if self.settings.history_visible && !self.history.is_empty() {
            categories = categories.push(
                button(text(fl!("communicate-history")))
                    .on_press(Message::SelectCategory(Some("__history__".into())))
                    .height(self.settings.button_px(48.0))
                    .padding([10, 16]),
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
                    image_url: None,
                    parent_id: None,
                    linked_board_id: None,
                    recording_path: None,
                    is_hidden: false,
                })
            })
            .collect();
        let phrase_source = if self.selected_category.as_deref() == Some("__history__") {
            &history_phrases
        } else {
            &self.phrases
        };
        let shown_phrases: Vec<&Phrase> = phrase_source
            .iter()
            .filter(|phrase| self.manage_phrases || !phrase.is_hidden)
            .collect();
        let columns = self.settings.grid_columns.max(1) as usize;
        for chunk in shown_phrases.chunks(columns) {
            let mut grid_row = row![].spacing(10);
            for phrase in chunk {
                let label = phrase.text.clone();
                let access_target = self.phrase_access_target(phrase);
                let show_symbol = phrase.image_url.is_some() && self.settings.show_symbols;
                let show_label = self.settings.show_labels || !show_symbol;
                let mut phrase_content = column![]
                    .spacing(4)
                    .align_x(cosmic::iced::alignment::Alignment::Center);
                if self.settings.label_at_top && show_label {
                    phrase_content =
                        phrase_content.push(text(label.clone()).size(self.settings.font_px(18.0)));
                }
                if show_symbol {
                    phrase_content = phrase_content.push(
                        self.image_for(phrase.image_url.as_deref(), 48.0)
                            .unwrap_or_else(|| {
                                symbolic_icon("image-x-generic-symbolic")
                                    .size(28)
                                    .icon()
                                    .into()
                            }),
                    );
                }
                if !self.settings.label_at_top && show_label {
                    phrase_content =
                        phrase_content.push(text(label).size(self.settings.font_px(18.0)));
                }
                let mut phrase_button = button(phrase_content)
                    .width(Fill)
                    .height(self.settings.button_px(72.0));
                if self.settings.hold_to_select_millis == 0 {
                    phrase_button =
                        phrase_button.on_press(Message::AccessActivate(access_target.clone()));
                }
                if self.access_highlighted(&access_target) {
                    phrase_button = phrase_button.class(cosmic::theme::iced::Button::Positive);
                }
                let phrase_activation = self.access_widget(phrase_button.into(), access_target);
                let mut card = column![phrase_activation].spacing(4).width(Fill);
                if self.manage_phrases && self.selected_category.as_deref() != Some("__history__") {
                    card = card.push(
                        row![
                            compact_icon_button(
                                "go-up-symbolic",
                                "Move phrase earlier",
                                Message::MovePhrase(phrase.id.clone(), -1)
                            ),
                            compact_icon_button(
                                "go-down-symbolic",
                                "Move phrase later",
                                Message::MovePhrase(phrase.id.clone(), 1)
                            ),
                            if let Some(path) = &phrase.recording_path {
                                compact_icon_button(
                                    "media-playback-start-symbolic",
                                    "Play recording",
                                    Message::PlayRecording(path.clone()),
                                )
                            } else {
                                Space::new().into()
                            },
                            labeled_icon_button(
                                "document-edit-symbolic",
                                "Edit",
                                Message::EditPhrase(phrase.id.clone())
                            ),
                            labeled_icon_button(
                                "edit-delete-symbolic",
                                "Remove",
                                Message::DeletePhrase(phrase.id.clone())
                            ),
                        ]
                        .spacing(4),
                    );
                }
                grid_row = grid_row.push(container(card).padding(6).width(Fill));
            }
            grid = grid.push(grid_row);
        }

        let adders: Element<'_, Message> = if self.manage_phrases {
            row![
                text_input(&fl!("communicate-new-phrase"), &self.new_phrase)
                    .on_input(Message::NewPhraseChanged)
                    .on_submit(Message::AddPhrase)
                    .padding(12),
                labeled_icon_button(
                    "list-add-symbolic",
                    fl!("communicate-add-phrase"),
                    Message::AddPhrase
                ),
                text_input(&fl!("communicate-new-category"), &self.new_category)
                    .on_input(Message::NewCategoryChanged)
                    .on_submit(Message::AddCategory)
                    .padding(12),
                labeled_icon_button(
                    "list-add-symbolic",
                    fl!("communicate-add-category"),
                    Message::AddCategory
                ),
                labeled_icon_button(
                    "object-select-symbolic",
                    fl!("action-done"),
                    Message::ToggleManagePhrases
                ),
            ]
            .spacing(8)
            .wrap()
            .into()
        } else {
            row![labeled_icon_button(
                "document-edit-symbolic",
                fl!("action-manage"),
                Message::ToggleManagePhrases
            )]
            .into()
        };

        let controls = row![
            touch_icon_button(
                if self.thought_draft.is_some() {
                    "edit-undo-symbolic"
                } else {
                    "document-save-symbolic"
                },
                if self.thought_draft.is_some() {
                    fl!("communicate-restore-thought")
                } else {
                    fl!("communicate-hold-thought")
                },
                Message::ToggleThought,
            ),
            touch_icon_button(
                "media-playback-start-symbolic",
                fl!("action-speak"),
                Message::Speak(self.draft.clone())
            ),
            touch_icon_button(
                "media-playback-pause-symbolic",
                fl!("action-pause"),
                Message::SpeechAction("/api/speak/pause")
            ),
            touch_icon_button(
                "media-playback-start-symbolic",
                fl!("action-resume"),
                Message::SpeechAction("/api/speak/resume")
            ),
            touch_icon_button(
                "media-playback-stop-symbolic",
                fl!("action-stop"),
                Message::SpeechAction("/api/speak/stop")
            ),
            touch_icon_button(
                "edit-clear-symbolic",
                fl!("action-clear-message"),
                Message::ClearDraft
            ),
            touch_icon_button(
                "view-fullscreen-symbolic",
                fl!("action-fullscreen"),
                Message::Navigate(Page::Fullscreen)
            ),
        ]
        .spacing(18)
        .align_y(cosmic::iced::alignment::Alignment::Center)
        .width(Fill)
        .wrap();

        let ssml = row![
            text(fl!("communicate-speech-markup")),
            touch_text_button(
                fl!("communicate-pause-markup"),
                Message::AppendMarkup(" [0.5s] ")
            ),
            touch_text_button(
                fl!("communicate-emphasis"),
                Message::AppendMarkup(" [strong] ")
            ),
            touch_text_button(
                fl!("communicate-secondary-language"),
                Message::AppendMarkup(" <en></en> ")
            ),
        ]
        .spacing(8)
        .wrap();

        let return_to_board: Element<'_, Message> = if self.native_keyboard_return_pending {
            container(
                row![
                    text(fl!("communicate-native-keyboard-hint")).width(Fill),
                    labeled_icon_button(
                        "go-previous-symbolic",
                        fl!("communicate-return-to-board"),
                        Message::ReturnToBoardFromKeyboard,
                    ),
                ]
                .spacing(12)
                .align_y(cosmic::iced::alignment::Alignment::Center),
            )
            .class(cosmic::theme::iced::Container::Secondary)
            .padding(12)
            .width(Fill)
            .into()
        } else {
            Space::new().into()
        };

        column![
            text(fl!("communicate-title")).size(self.settings.font_px(30.0)),
            return_to_board,
            input,
            predictions,
            ssml,
            scrollable(categories)
                .direction(scrollable::Direction::Horizontal(
                    scrollable::Scrollbar::default()
                ))
                .height(self.settings.button_px(48.0)),
            scrollable(grid).height(Fill),
            if self.selected_category.as_deref() == Some("__history__") {
                row![labeled_icon_button(
                    "edit-delete-symbolic",
                    fl!("action-clear-history"),
                    Message::ClearHistory
                )]
                .into()
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
                text(fl!("onboarding-welcome-title")).size(40),
                text(fl!("onboarding-welcome-description")),
                text(fl!("onboarding-workspaces-description")),
                touch_text_button("Get started", Message::OnboardingNext),
            ]
            .spacing(18)
            .into(),
            1 => column![
                text(fl!("onboarding-workspace-title")).size(32),
                checkbox(!self.onboarding_screens)
                    .label(fl!("onboarding-keyboard"))
                    .on_toggle(|selected| Message::OnboardingMode(!selected)),
                checkbox(self.onboarding_screens)
                    .label(fl!("onboarding-screens"))
                    .on_toggle(Message::OnboardingMode),
                row![
                    labeled_icon_button("go-previous-symbolic", "Back", Message::OnboardingBack),
                    labeled_icon_button("go-next-symbolic", "Next", Message::OnboardingNext)
                ]
                .spacing(10),
            ]
            .spacing(18)
            .into(),
            _ => column![
                text(fl!("settings-privacy")).size(32),
                text(fl!("onboarding-privacy-description")),
                row![
                    labeled_icon_button("go-previous-symbolic", "Back", Message::OnboardingBack),
                    labeled_icon_button(
                        "object-select-symbolic",
                        "Finish setup",
                        Message::CompleteOnboarding
                    )
                ]
                .spacing(10),
            ]
            .spacing(18)
            .into(),
        };
        container(body).max_width(720).padding(30).into()
    }

    fn fullscreen_view(&self) -> Element<'_, Message> {
        container(
            column![
                text(&self.draft)
                    .size(self.settings.font_px(52.0))
                    .width(Fill),
                row![
                    touch_icon_button(
                        "media-playback-start-symbolic",
                        "Speak",
                        Message::Speak(self.draft.clone())
                    ),
                    touch_icon_button(
                        "window-close-symbolic",
                        "Close fullscreen",
                        Message::Navigate(self.last_workspace)
                    ),
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
        if self.preset_importing {
            return container(
                column![
                    text(fl!("status-creating-screen")).size(20),
                    progress_bar::<cosmic::Theme>(0.0..=1.0, self.preset_progress.unwrap_or(0.0)),
                ]
                .spacing(12)
                .align_x(cosmic::iced::alignment::Alignment::Center),
            )
            .center(Fill)
            .into();
        }

        if let Some(graph) = &self.board_graph {
            return self.board_workspace_view(graph);
        }

        let board_cards = self
            .board_sets
            .iter()
            .fold(row![].spacing(16), |cards, set| {
                let run_card = button(
                    column![
                        symbolic_icon("view-grid-symbolic").size(42).icon(),
                        text(&set.name).size(24),
                        text(fl!(
                            "screens-page-count",
                            count = (set.board_ids.len() as i64),
                            locked = if set.is_locked {
                                fl!("screens-locked-suffix")
                            } else {
                                String::new()
                            }
                        ))
                        .size(14),
                    ]
                    .spacing(10)
                    .align_x(cosmic::iced::alignment::Alignment::Center),
                )
                .on_press(Message::OpenBoardSet(set.id.clone(), false))
                .width(Fill)
                .height(150)
                .padding(18);

                cards.push(
                    container(
                        column![
                            run_card,
                            row![
                                compact_icon_button(
                                    "document-edit-symbolic",
                                    fl!("screens-edit-set"),
                                    Message::OpenBoardSet(set.id.clone(), true)
                                ),
                                compact_icon_button(
                                    "edit-copy-symbolic",
                                    fl!("screens-duplicate-set"),
                                    Message::DuplicateBoardSet(set.id.clone())
                                ),
                                compact_icon_button(
                                    if set.is_locked {
                                        "changes-allow-symbolic"
                                    } else {
                                        "changes-prevent-symbolic"
                                    },
                                    if set.is_locked {
                                        fl!("screens-unlock-set")
                                    } else {
                                        fl!("screens-lock-set")
                                    },
                                    Message::ToggleBoardSetLock(set.id.clone())
                                ),
                                compact_icon_button(
                                    "document-save-symbolic",
                                    fl!("screens-export-set"),
                                    Message::ExportBoardSet(set.id.clone(), set.name.clone())
                                ),
                                compact_icon_button(
                                    "edit-delete-symbolic",
                                    fl!("screens-delete-set"),
                                    Message::DeleteBoardSet(set.id.clone())
                                ),
                            ]
                            .spacing(6)
                            .align_y(cosmic::iced::alignment::Alignment::Center),
                        ]
                        .spacing(8),
                    )
                    .class(cosmic::theme::iced::Container::Card)
                    .padding(10)
                    .width(310),
                )
            })
            .wrap();

        let library: Element<'_, Message> = if self.board_sets.is_empty() {
            container(
                column![
                    symbolic_icon("view-grid-symbolic").size(56).icon(),
                    text(fl!("screens-empty-title")).size(24),
                    text(fl!("screens-empty-description")),
                ]
                .spacing(10)
                .align_x(cosmic::iced::alignment::Alignment::Center),
            )
            .center(Fill)
            .into()
        } else {
            scrollable(container(board_cards).padding([8, 2]))
                .height(Fill)
                .into()
        };

        let preset_progress: Element<'_, Message> = if self.preset_importing {
            progress_bar::<cosmic::Theme>(0.0..=1.0, self.preset_progress.unwrap_or(0.0)).into()
        } else {
            Space::new().into()
        };
        let create_panel = container(
            column![
                text(fl!("screens-create-title")).size(20),
                row![
                    text_input(&fl!("screens-new-set"), &self.new_board_set)
                        .on_input(Message::BoardSetNameChanged)
                        .on_submit(Message::CreateBoardSet)
                        .padding(12)
                        .width(Fill),
                    column![
                        text(fl!("screens-rows", count = self.board_rows)).size(14),
                        slider(1..=12, self.board_rows, Message::BoardRowsChanged).width(150),
                    ]
                    .spacing(4),
                    column![
                        text(fl!("screens-columns", count = self.board_columns)).size(14),
                        slider(1..=12, self.board_columns, Message::BoardColumnsChanged).width(150),
                    ]
                    .spacing(4),
                    column![
                        text(fl!("screens-template")).size(14),
                        pick_list(
                            board_template_options(),
                            Some(self.board_template.clone()),
                            Message::BoardTemplateChanged,
                        )
                        .width(190),
                    ]
                    .spacing(4),
                    labeled_icon_button(
                        "list-add-symbolic",
                        fl!("screens-create"),
                        Message::CreateBoardSet
                    ),
                ]
                .spacing(14)
                .align_y(cosmic::iced::alignment::Alignment::Center)
                .wrap(),
                preset_progress,
            ]
            .spacing(10),
        )
        .class(cosmic::theme::iced::Container::Card)
        .padding(16);

        column![
            row![
                column![
                    text(fl!("screens-library-title")).size(30),
                    text(fl!("screens-description")),
                ]
                .spacing(4)
                .width(Fill),
                labeled_icon_button(
                    "document-open-symbolic",
                    fl!("screens-import"),
                    Message::ImportBoardSet
                ),
            ]
            .spacing(16)
            .align_y(cosmic::iced::alignment::Alignment::Center),
            library,
            create_panel,
        ]
        .spacing(16)
        .into()
    }

    fn board_workspace_view<'a>(&'a self, graph: &'a BoardGraph) -> Element<'a, Message> {
        if let Some((row_index, column_index)) = self.editing_cell {
            let package_filters = [
                ("all", fl!("symbol-package-all")),
                ("opensymbols", "OpenSymbols".to_string()),
                ("mulberry", "Mulberry".to_string()),
                ("arasaac", "ARASAAC".to_string()),
            ];
            let package_row = row(package_filters.into_iter().map(|(value, label)| {
                let label = if self.symbol_package == value {
                    format!("✓ {label}")
                } else {
                    label
                };
                button(text(label))
                    .on_press(Message::CellSymbolPackageChanged(value.to_string()))
                    .into()
            }))
            .spacing(8);
            let mut editor = column![
                text(fl!("board-field-edit-title")).size(30),
                text_input(&fl!("board-field-label"), &self.cell_label)
                    .on_input(Message::CellLabelChanged)
                    .padding(12),
                text_input(
                    "Speak something different (optional)",
                    &self.cell_vocalization
                )
                .on_input(Message::CellVoiceChanged)
                .padding(12),
                package_row,
                row![
                    text_input(&fl!("board-symbol-search"), &self.symbol_query)
                        .on_input(Message::CellSymbolQueryChanged)
                        .on_submit(Message::CellSymbolSearch)
                        .padding(12)
                        .width(360),
                    labeled_icon_button(
                        "system-search-symbolic",
                        "Search",
                        Message::CellSymbolSearch
                    ),
                    labeled_icon_button(
                        "document-open-symbolic",
                        "Choose image…",
                        Message::CellLocalImage
                    ),
                    {
                        let el: Element<'_, Message> = if self.cell_image_url.is_some() {
                            labeled_icon_button(
                                "edit-delete-symbolic",
                                "Remove image",
                                Message::CellSymbolCleared,
                            )
                        } else {
                            Space::new().into()
                        };
                        el
                    },
                ]
                .spacing(8)
                .align_y(cosmic::iced::alignment::Alignment::Center),
            ]
            .spacing(16);

            if self.symbol_loading {
                editor = editor.push(text(fl!("status-searching")).size(15));
            }
            if !self.symbols.is_empty() {
                let results = row(self
                        .symbols
                        .iter()
                        .enumerate()
                        .take(20)
                        .map(|(index, symbol)| {
                            let mut content = column![]
                                .spacing(3)
                                .align_x(cosmic::iced::alignment::Alignment::Center);
                            if let Some(preview) = self.image_for(symbol.image_url.as_deref(), 44.0)
                            {
                                content = content.push(preview);
                            }
                            content = content.push(text(
                                symbol
                                    .name
                                    .clone()
                                    .unwrap_or_else(|| format!("#{}", symbol.id)),
                            ));
                            content = content.push(text(match symbol.source.as_str() {
                                "mulberry" => "Mulberry",
                                "arasaac" => "ARASAAC",
                                _ => "OpenSymbols",
                            }).size(11));
                            button(content)
                                .on_press(Message::CellSymbolPicked(index))
                                .width(124)
                                .height(96)
                                .padding([6, 10])
                                .into()
                        }))
                    .spacing(6);
                editor = editor.push(
                    scrollable(results)
                        .direction(scrollable::Direction::Horizontal(
                            scrollable::Scrollbar::default(),
                        ))
                        .width(Fill)
                        .height(108),
                );
            }
            if let Some(image_url) = &self.cell_image_url {
                if let Some(preview) = self.image_for(Some(image_url), 120.0) {
                    editor = editor.push(preview);
                }
            }

            let mut page_options = vec!["No page link".to_string()];
            page_options.extend(
                graph
                    .boards
                    .iter()
                    .map(|board| board.name.clone().unwrap_or_else(|| "Untitled page".into())),
            );
            let selected_page = self
                .cell_linked_board_id
                .as_ref()
                .and_then(|id| {
                    graph
                        .boards
                        .iter()
                        .find(|board| &board.id == id)
                        .map(|board| board.name.clone().unwrap_or_else(|| "Untitled page".into()))
                })
                .or_else(|| Some("No page link".into()));
            editor = editor
                .push(
                    row![
                        text_input(&fl!("board-background-color"), &self.cell_background_color)
                            .on_input(Message::CellBackgroundChanged)
                            .padding(12),
                        pick_list(
                            vec![
                                "Automatic",
                                "pronoun",
                                "verb",
                                "descriptor",
                                "noun",
                                "social",
                                "other",
                            ]
                            .into_iter()
                            .map(str::to_string)
                            .collect::<Vec<_>>(),
                            Some(self.cell_word_type.clone()),
                            Message::CellWordTypeChanged,
                        ),
                        pick_list(page_options, selected_page, Message::CellLinkedBoardChanged),
                        checkbox(self.cell_hidden)
                            .label(fl!("board-hidden-run"))
                            .on_toggle(Message::CellHiddenChanged),
                    ]
                    .spacing(10)
                    .wrap(),
                )
                .push(
                    text_input(
                        "OBF actions, comma separated (optional)",
                        &self.cell_actions,
                    )
                    .on_input(Message::CellActionsChanged)
                    .padding(12),
                );

            return editor
                .push(
                    row![
                        labeled_icon_button(
                            "document-save-symbolic",
                            "Save",
                            Message::SaveBoardCell
                        ),
                        labeled_icon_button(
                            "edit-clear-symbolic",
                            "Clear field",
                            Message::ClearBoardCell
                        ),
                        labeled_icon_button(
                            "window-close-symbolic",
                            "Cancel",
                            Message::CancelBoardCell
                        ),
                    ]
                    .spacing(10),
                )
                .push(text(format!(
                    "Row {}, column {}",
                    row_index + 1,
                    column_index + 1
                )))
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
            return text(fl!("board-no-pages")).into();
        };
        let (show_labels, show_symbols, label_at_top, show_message_bar) = graph
            .resolved_settings
            .get(&board.id)
            .map(|settings| {
                (
                    settings.show_labels,
                    settings.show_symbols,
                    settings.label_at_top,
                    settings.show_message_bar,
                )
            })
            .unwrap_or((
                self.settings.show_labels,
                self.settings.show_symbols,
                self.settings.label_at_top,
                self.settings.board_show_message_bar,
            ));
        // Position fields explicitly because libcosmic's implicit grid tracks
        // currently mis-measure Fill children: columns are positioned correctly
        // but each button can repaint across the rest of its row.
        let available_grid_width = (self.window_width - 36.0).max(320.0);
        let grid_columns = board
            .grid
            .as_ref()
            .and_then(|grid| grid.order.iter().map(Vec::len).max())
            .unwrap_or_else(|| {
                graph
                    .field_items
                    .get(&board.id)
                    .into_iter()
                    .flatten()
                    .map(|field| field.column + field.column_span.max(1))
                    .max()
                    .unwrap_or(1)
            })
            .max(1);
        let grid_rows = board
            .grid
            .as_ref()
            .map(|grid| grid.order.len())
            .unwrap_or_else(|| {
                graph
                    .field_items
                    .get(&board.id)
                    .into_iter()
                    .flatten()
                    .map(|field| field.row + field.row_span.max(1))
                    .max()
                    .unwrap_or(1)
            })
            .max(1);
        let cell_gap = 8.0;
        // Fill the remaining desktop height by default. Edit mode keeps room
        // for its page controls, while a board without a message bar can use
        // the space that control would otherwise occupy.
        let reserved_height = if self.board_edit_mode {
            390.0
        } else if show_message_bar {
            245.0
        } else {
            190.0
        };
        let available_grid_height = (self.window_height - reserved_height).max(0.0);
        let cell_height = ((available_grid_height - cell_gap * grid_rows.saturating_sub(1) as f32)
            / grid_rows as f32)
            .max(48.0);
        let cell_width = ((available_grid_width - cell_gap * (grid_columns - 1) as f32)
            / grid_columns as f32)
            .max(48.0);
        let board_grid_width =
            cell_width * grid_columns as f32 + cell_gap * (grid_columns - 1) as f32;
        let board_grid_height =
            cell_height * grid_rows as f32 + cell_gap * grid_rows.saturating_sub(1) as f32;
        let board_fields = graph
            .field_items
            .get(&board.id)
            .map(Vec::as_slice)
            .unwrap_or(&[]);
        let use_regular_grid = uses_regular_board_grid(board_fields, grid_rows, grid_columns);
        let mut regular_cells: HashMap<(usize, usize), Element<'_, Message>> = HashMap::new();
        let mut cell_layers: Vec<Element<'_, Message>> = vec![Space::new()
            .width(cosmic::iced::Length::Fixed(board_grid_width))
            .height(cosmic::iced::Length::Fixed(board_grid_height))
            .into()];
        for field in board_fields {
            let button_data = field
                .button_id
                .as_ref()
                .and_then(|id| board.buttons.iter().find(|button| &button.id == id));
            if !self.board_edit_mode && button_data.is_some_and(|button| button.hidden) {
                continue;
            }
            let raw_label = button_data
                .and_then(|button| button.label.as_deref())
                .unwrap_or(if self.board_edit_mode { "+" } else { "" });
            let label = raw_label.to_string();
            let action = Message::SelectBoardCell(field.row, field.column);
            let symbol_source = button_data
                .and_then(|button| button.image_id.as_ref())
                .and_then(|image_id| board.images.iter().find(|image| &image.id == image_id))
                .and_then(|item| item.url.as_deref().or(item.path.as_deref()));
            let row_span = field.row_span.max(1);
            let column_span = field.column_span.max(1);
            let field_width =
                cell_width * column_span as f32 + cell_gap * column_span.saturating_sub(1) as f32;
            let field_height =
                cell_height * row_span as f32 + cell_gap * row_span.saturating_sub(1) as f32;
            let field_x = field.column as f32 * (cell_width + cell_gap);
            let field_y = field.row as f32 * (cell_height + cell_gap);
            let symbol_height = (field_height * 0.58).clamp(40.0, 160.0);
            let label_size = ((field_height * 0.18).clamp(22.0, 38.0)
                * self.settings.font_size_scale)
                .clamp(18.0, 48.0);
            let field_color = if self.settings.high_contrast_mode {
                None
            } else {
                button_data
                    .and_then(BoardButton::rendered_background_color)
                    .and_then(parse_hex_color)
            };
            let label_color = field_color.map(contrasting_foreground);
            let has_symbol = symbol_source.is_some();
            let show_symbol = has_symbol && show_symbols;
            let show_label = show_labels || !show_symbol;
            let mut cell_content = column![]
                .spacing(4)
                .align_x(cosmic::iced::alignment::Alignment::Center);
            if label_at_top && show_label {
                let label_text = text(label.clone()).size(label_size);
                cell_content = cell_content.push(if let Some(color) = label_color {
                    label_text.class(cosmic::theme::iced::Text::Color(color))
                } else {
                    label_text
                });
            }
            if show_symbol {
                cell_content = cell_content.push(
                    self.image_for(symbol_source, symbol_height)
                        .unwrap_or_else(|| {
                            symbolic_icon("image-x-generic-symbolic")
                                .size(32)
                                .icon()
                                .into()
                        }),
                );
            }
            if !label_at_top && show_label {
                let label_text = text(label).size(label_size);
                cell_content = cell_content.push(if let Some(color) = label_color {
                    label_text.class(cosmic::theme::iced::Text::Color(color))
                } else {
                    label_text
                });
            }
            let centered_content = container(cell_content)
                .width(Fill)
                .height(Fill)
                .align_x(cosmic::iced::alignment::Horizontal::Center)
                .align_y(cosmic::iced::alignment::Vertical::Center);
            let field_widget: Element<'_, Message> = if self.board_edit_mode {
                let mut field_button = button(centered_content)
                    .on_press(action)
                    .width(cosmic::iced::Length::Fixed(field_width))
                    .height(field_height)
                    .class(cosmic::theme::iced::Button::Secondary);
                if let Some(color) = field_color {
                    field_button = field_button.class(colored_button_class(color));
                }
                field_button.into()
            } else if button_data.is_none() {
                button(centered_content)
                    .width(cosmic::iced::Length::Fixed(field_width))
                    .height(field_height)
                    .class(cosmic::theme::iced::Button::Secondary)
                    .into()
            } else {
                let button_data = button_data.expect("checked above");
                let target = AccessTarget::BoardButton(board.id.clone(), button_data.id.clone());
                let mut field_button = button(centered_content)
                    .width(cosmic::iced::Length::Fixed(field_width))
                    .height(field_height)
                    .class(cosmic::theme::iced::Button::Secondary);
                if let Some(color) = field_color {
                    field_button = field_button.class(colored_button_class(color));
                }
                if self.settings.hold_to_select_millis == 0 {
                    field_button = field_button.on_press(Message::AccessActivate(target.clone()));
                }
                if self.access_highlighted(&target) {
                    field_button = field_button.class(cosmic::theme::iced::Button::Positive);
                }
                self.access_widget(field_button.into(), target)
            };
            if use_regular_grid {
                regular_cells.insert((field.row, field.column), field_widget);
            } else {
                cell_layers.push(
                    container(field_widget)
                        .width(cosmic::iced::Length::Fixed(board_grid_width))
                        .height(cosmic::iced::Length::Fixed(board_grid_height))
                        .padding(Padding {
                            top: field_y,
                            right: (board_grid_width - field_x - field_width).max(0.0),
                            bottom: (board_grid_height - field_y - field_height).max(0.0),
                            left: field_x,
                        })
                        .into(),
                );
            }
        }
        let cells: Element<'_, Message> = if use_regular_grid {
            let mut grid = column![].spacing(cell_gap);
            for row_index in 0..grid_rows {
                let mut grid_row = row![].spacing(cell_gap);
                for column_index in 0..grid_columns {
                    let cell = regular_cells
                        .remove(&(row_index, column_index))
                        .unwrap_or_else(|| {
                            Space::new()
                                .width(cosmic::iced::Length::Fixed(cell_width))
                                .height(cosmic::iced::Length::Fixed(cell_height))
                                .into()
                        });
                    grid_row = grid_row.push(cell);
                }
                grid = grid.push(grid_row);
            }
            container(grid)
                .width(cosmic::iced::Length::Fixed(board_grid_width))
                .height(cosmic::iced::Length::Fixed(board_grid_height))
                .into()
        } else {
            stack(cell_layers)
                .width(cosmic::iced::Length::Fixed(board_grid_width))
                .height(cosmic::iced::Length::Fixed(board_grid_height))
                .clip(true)
                .into()
        };

        let pages: Element<'_, Message> = if self.board_edit_mode {
            scrollable(
                row(graph.boards.iter().map(|item| {
                    button(item.name.as_deref().unwrap_or("Untitled page"))
                        .on_press(Message::SelectBoard(item.id.clone()))
                        .height(48)
                        .padding([10, 16])
                        .class(if item.id == active_id {
                            cosmic::theme::iced::Button::Primary
                        } else {
                            cosmic::theme::iced::Button::Secondary
                        })
                        .into()
                }))
                .spacing(6),
            )
            .direction(scrollable::Direction::Horizontal(
                scrollable::Scrollbar::default(),
            ))
            .height(48)
            .into()
        } else {
            Space::new().height(1).into()
        };

        let page_editor: Element<'_, Message> = if self.board_edit_mode {
            let resolved = graph.resolved_settings.get(&board.id);
            let activation = resolved
                .map(|value| value.activation_behavior.clone())
                .filter(|value| !value.is_empty())
                .unwrap_or_else(|| "SpeakAndAdd".into());
            let return_behavior = resolved
                .map(|value| value.return_behavior.clone())
                .filter(|value| !value.is_empty())
                .unwrap_or_else(|| "Stay".into());
            column![
                row![
                    text_input(&fl!("board-current-page-name"), &self.current_page_name)
                        .on_input(Message::CurrentPageNameChanged)
                        .on_submit(Message::RenameCurrentPage)
                        .padding(12),
                    labeled_icon_button(
                        "document-save-symbolic",
                        "Rename page",
                        Message::RenameCurrentPage
                    ),
                    labeled_icon_button(
                        "go-home-symbolic",
                        "Set as home page",
                        Message::SetCurrentPageAsHome
                    ),
                    labeled_icon_button(
                        "edit-delete-symbolic",
                        "Delete page",
                        Message::DeleteCurrentPage
                    ),
                ]
                .spacing(8)
                .wrap(),
                row![
                    text(format!("Rows {}", self.board_rows)),
                    slider(1..=12, self.board_rows, Message::BoardRowsChanged).width(120),
                    text(format!("Columns {}", self.board_columns)),
                    slider(1..=12, self.board_columns, Message::BoardColumnsChanged).width(120),
                    labeled_icon_button(
                        "view-refresh-symbolic",
                        "Resize page",
                        Message::ResizeCurrentPage
                    ),
                    text(fl!("board-activation")),
                    pick_list(
                        vec![
                            "SpeakAndAdd".to_string(),
                            "AddOnly".to_string(),
                            "SpeakOnly".to_string()
                        ],
                        Some(activation),
                        Message::PageActivationChanged
                    ),
                    text(fl!("board-after-selection")),
                    pick_list(
                        vec![
                            "Stay".to_string(),
                            "Previous".to_string(),
                            "StartPage".to_string()
                        ],
                        Some(return_behavior),
                        Message::PageReturnChanged
                    ),
                ]
                .spacing(8)
                .wrap(),
                row![
                    text_input(&fl!("board-new-page-name"), &self.new_page)
                        .on_input(Message::PageNameChanged)
                        .on_submit(Message::CreatePage)
                        .padding(12),
                    labeled_icon_button("list-add-symbolic", "Add page", Message::CreatePage),
                ]
                .spacing(8),
            ]
            .spacing(8)
            .into()
        } else {
            Space::new().height(1).into()
        };

        let message_bar: Element<'_, Message> = if !self.board_edit_mode && show_message_bar {
            container(
                row![
                    aac_toolbar_button(
                        "go-home-symbolic",
                        fl!("board-home"),
                        Message::BoardNavigateHome,
                    ),
                    aac_toolbar_button(
                        "go-previous-symbolic",
                        fl!("board-back"),
                        Message::BoardNavigateBack,
                    ),
                    button(
                        scrollable(
                            text(if self.board_sentence.is_empty() {
                                fl!("board-message-placeholder")
                            } else {
                                self.board_sentence.clone()
                            })
                            .size(22)
                            .wrapping(cosmic::iced::widget::text::Wrapping::None),
                        )
                        .direction(scrollable::Direction::Horizontal(
                            scrollable::Scrollbar::default(),
                        ))
                        .height(Fill)
                        .width(Fill)
                    )
                    .on_press(Message::Speak(self.board_sentence.clone()))
                    .class(cosmic::theme::iced::Button::Secondary)
                    .height(68)
                    .width(Fill)
                    .padding([10, 16]),
                    aac_toolbar_button(
                        "media-playback-start-symbolic",
                        fl!("board-speak-message"),
                        Message::Speak(self.board_sentence.clone()),
                    ),
                    aac_toolbar_button(
                        "edit-undo-symbolic",
                        fl!("board-backspace-message"),
                        Message::BoardSentenceBackspace,
                    ),
                    aac_toolbar_button(
                        "edit-clear-symbolic",
                        fl!("board-clear-message"),
                        Message::BoardSentenceClear,
                    ),
                ]
                .spacing(10)
                .align_y(cosmic::iced::alignment::Alignment::Center),
            )
            .class(cosmic::theme::iced::Container::Card)
            .padding(8)
            .width(Fill)
            .into()
        } else {
            container(
                row![
                    aac_toolbar_button(
                        "go-home-symbolic",
                        fl!("board-home"),
                        Message::BoardNavigateHome,
                    ),
                    aac_toolbar_button(
                        "go-previous-symbolic",
                        fl!("board-back"),
                        Message::BoardNavigateBack,
                    ),
                    text(board.name.as_deref().unwrap_or("Page"))
                        .size(22)
                        .width(Fill),
                ]
                .spacing(10)
                .align_y(cosmic::iced::alignment::Alignment::Center),
            )
            .class(cosmic::theme::iced::Container::Card)
            .padding(8)
            .width(Fill)
            .into()
        };

        if self.board_edit_mode {
            column![
                row![
                    labeled_icon_button(
                        "go-previous-symbolic",
                        fl!("board-library"),
                        Message::ExitBoardSet
                    ),
                    text(format!(
                        "{} · {}",
                        graph.board_set.name,
                        board.name.as_deref().unwrap_or("Page")
                    ))
                    .size(26)
                    .width(Fill),
                    labeled_icon_button(
                        "media-playback-start-symbolic",
                        fl!("board-run"),
                        Message::ToggleBoardEdit
                    ),
                ]
                .spacing(10)
                .align_y(cosmic::iced::alignment::Alignment::Center),
                pages,
                scrollable(cells)
                    .direction(scrollable::Direction::Both {
                        vertical: scrollable::Scrollbar::default(),
                        horizontal: scrollable::Scrollbar::default(),
                    })
                    .width(Fill)
                    .height(Fill),
                page_editor,
            ]
            .spacing(12)
            .into()
        } else {
            column![
                message_bar,
                scrollable(cells)
                    .direction(scrollable::Direction::Both {
                        vertical: scrollable::Scrollbar::default(),
                        horizontal: scrollable::Scrollbar::default(),
                    })
                    .width(Fill)
                    .height(Fill),
                row![
                    text(format!(
                        "{} · {}",
                        graph.board_set.name,
                        board.name.as_deref().unwrap_or("Page")
                    ))
                    .size(16)
                    .width(Fill),
                    compact_icon_button(
                        "view-grid-symbolic",
                        fl!("board-library"),
                        Message::ExitBoardSet
                    ),
                ]
                .spacing(8)
                .align_y(cosmic::iced::alignment::Alignment::Center),
            ]
            .spacing(10)
            .into()
        }
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
                        text(entry.alphabet.to_uppercase()).width(90),
                        compact_icon_button(
                            "media-playback-start-symbolic",
                            "Test pronunciation",
                            Message::TestPronunciation(entry.word.clone())
                        ),
                        compact_icon_button(
                            "edit-delete-symbolic",
                            "Remove pronunciation",
                            Message::DeletePronunciation(entry.word.clone())
                        ),
                    ]
                    .align_y(cosmic::iced::alignment::Alignment::Center),
                )
            });

        column![
            text(fl!("pronunciation-title")).size(30),
            text(fl!("pronunciation-description")),
            row![
                text_input(&fl!("pronunciation-word"), &self.new_word)
                    .on_input(Message::NewWordChanged)
                    .padding(12),
                text_input(&fl!("pronunciation-phoneme"), &self.new_phoneme)
                    .on_input(Message::NewPhonemeChanged)
                    .on_submit(Message::AddPronunciation)
                    .padding(12),
                pick_list(
                    vec![
                        "text".to_string(),
                        "ipa".to_string(),
                        "x-sampa".to_string(),
                        "sapi".to_string(),
                        "ups".to_string()
                    ],
                    Some(self.pronunciation_alphabet.clone()),
                    Message::PronunciationAlphabetChanged,
                ),
                labeled_icon_button("list-add-symbolic", "Add", Message::AddPronunciation),
            ]
            .spacing(10)
            .wrap(),
            scrollable(entries).height(Fill),
            row![
                labeled_icon_button(
                    "document-open-symbolic",
                    "Import JSON/CSV…",
                    Message::ImportPronunciations
                ),
                labeled_icon_button(
                    "document-save-symbolic",
                    "Export CSV…",
                    Message::ExportPronunciations
                ),
            ]
            .spacing(8)
            .wrap(),
        ]
        .spacing(16)
        .into()
    }

    fn settings_view(&self) -> Element<'_, Message> {
        let mut categories: Vec<(SettingsCategory, String, &'static str)> = vec![
            (
                SettingsCategory::Speech,
                fl!("settings-speech"),
                "audio-speakers-symbolic",
            ),
            (
                SettingsCategory::Dictionary,
                fl!("settings-pronunciation"),
                "accessories-dictionary-symbolic",
            ),
            (
                SettingsCategory::Display,
                fl!("settings-display"),
                "video-display-symbolic",
            ),
            (
                SettingsCategory::Access,
                fl!("settings-access"),
                "preferences-desktop-accessibility-symbolic",
            ),
            (
                SettingsCategory::Startup,
                fl!("settings-startup"),
                "system-run-symbolic",
            ),
            (
                SettingsCategory::Privacy,
                fl!("settings-privacy"),
                "security-high-symbolic",
            ),
        ];
        if self.partner.is_available() {
            categories.push((
                SettingsCategory::Partner,
                fl!("settings-partner-window"),
                "video-joined-displays-symbolic",
            ));
        }

        let sidebar = column![
            text(fl!("nav-settings")).size(26),
            Space::new().height(12),
            categories
                .iter()
                .map(|(category, label, icon_name)| {
                    nav_button(
                        icon_name,
                        label.clone(),
                        self.settings_category == *category,
                        Message::SelectSettingsCategory(*category),
                    )
                })
                .fold(column![].spacing(6), |col, b| col.push(b)),
        ]
        .spacing(6)
        .padding(18)
        .width(200);

        let content = match self.settings_category {
            SettingsCategory::Speech => self.speech_settings_view(),
            SettingsCategory::Dictionary => self.dictionary_view(),
            SettingsCategory::Display => self.display_settings_view(),
            SettingsCategory::Access => self.access_settings_view(),
            SettingsCategory::Startup => self.startup_settings_view(),
            SettingsCategory::Privacy => self.privacy_settings_view(),
            SettingsCategory::Partner if self.partner.is_available() => {
                self.partner_settings_view()
            }
            SettingsCategory::Partner => self.speech_settings_view(),
        };

        row![
            container(sidebar).height(Fill),
            container(content).padding(24).width(Fill).height(Fill)
        ]
        .into()
    }

    fn speech_settings_view(&self) -> Element<'_, Message> {
        let current_voice = self
            .selected_voice_name
            .as_deref()
            .unwrap_or(self.settings.voice.as_str());
        let language_prefix = format!("{}-", self.settings.primary_language);
        let voice_matches_language = |voice: &&Voice| {
            voice.name.as_deref() == Some(current_voice)
                || voice.primary_language.as_deref()
                    == Some(self.settings.primary_language.as_str())
                || voice.supported_languages.as_ref().is_some_and(|languages| {
                    languages
                        .iter()
                        .any(|language| language == &self.settings.primary_language)
                })
                || voice
                    .name
                    .as_deref()
                    .is_some_and(|name| name.starts_with(&language_prefix))
        };
        let mut voice_names: Vec<String> = self
            .voices
            .iter()
            .filter(voice_matches_language)
            .filter_map(|voice| voice.name.clone())
            .collect();
        // Older/system voice providers may not publish locale metadata. Avoid
        // hiding their voices if no match can be established.
        if voice_names.is_empty() {
            voice_names = self
                .voices
                .iter()
                .filter_map(|voice| voice.name.clone())
                .collect();
        }
        voice_names.sort();
        voice_names.dedup();
        let active_voice = self
            .selected_voice_name
            .clone()
            .filter(|name| voice_names.contains(name))
            .or_else(|| {
                Some(self.settings.voice.clone()).filter(|name| voice_names.contains(name))
            });
        let selected_voice = self.preview_voice_name.clone().or(active_voice);
        let mut languages: Vec<String> = self
            .voices
            .iter()
            .flat_map(|voice| {
                voice
                    .primary_language
                    .iter()
                    .cloned()
                    .chain(voice.supported_languages.clone().unwrap_or_default())
                    .collect::<Vec<_>>()
            })
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
        let azure_credentials: Element<'_, Message> =
            if self.azure_credential_configured && !self.replacing_azure_credentials {
                column![
                    text(fl!("speech-azure-configured")),
                    labeled_icon_button(
                        "document-edit-symbolic",
                        fl!("speech-azure-replace"),
                        Message::ReplaceAzureCredentials
                    ),
                ]
                .spacing(6)
                .into()
            } else {
                column![
                    text_input(&fl!("speech-azure-endpoint"), &self.azure_endpoint)
                        .on_input(Message::AzureEndpointChanged)
                        .padding(12),
                    text_input(&fl!("speech-azure-key"), &self.azure_key)
                        .on_input(Message::AzureKeyChanged)
                        .secure(true)
                        .padding(12),
                    labeled_icon_button(
                        "document-save-symbolic",
                        fl!("speech-azure-save"),
                        Message::SaveAzureConfig
                    ),
                ]
                .spacing(6)
                .into()
            };

        scrollable(
            column![
                settings_row(
                    fl!("speech-voice"),
                    row![
                        pick_list(voice_names, selected_voice, Message::VoicePreviewSelected)
                            .width(Fill),
                        compact_icon_button(
                            "media-playback-start-symbolic",
                            fl!("voice-preview"),
                            Message::PreviewVoice
                        ),
                        compact_icon_button(
                            "object-select-symbolic",
                            fl!("voice-use"),
                            Message::ApplyPreviewVoice
                        ),
                    ]
                    .spacing(8)
                    .into()
                ),
                settings_row(
                    fl!("speech-engine"),
                    pick_list(
                        vec!["SYSTEM".to_string(), "AZURE_USER_RESOURCE".to_string()],
                        Some(self.settings.tts_engine.clone()),
                        Message::EngineChanged
                    )
                    .into(),
                ),
                settings_row(
                    fl!("speech-speed"),
                    slider(0.5..=2.0, self.settings.speech_rate, Message::RateChanged)
                        .step(0.1_f32)
                        .into()
                ),
                settings_row(
                    fl!("speech-primary-language"),
                    pick_list(
                        languages.clone(),
                        Some(self.settings.primary_language.clone()),
                        Message::PrimaryLanguageChanged
                    )
                    .into(),
                ),
                settings_row(
                    fl!("speech-secondary-language"),
                    pick_list(
                        secondary_languages,
                        Some(selected_secondary),
                        Message::SecondaryLanguageChanged
                    )
                    .into(),
                ),
                column![text(fl!("speech-azure-title")).size(15), azure_credentials,].spacing(6),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }

    fn display_settings_view(&self) -> Element<'_, Message> {
        let appearance = match self.settings.force_dark_theme {
            Some(false) => "Light",
            Some(true) => "Dark",
            None => "System",
        }
        .to_string();
        let label_position: Element<'_, Message> =
            if self.settings.show_labels && self.settings.show_symbols {
                checkbox(self.settings.label_at_top)
                    .label(fl!("display-labels-above"))
                    .on_toggle(|enabled| Message::SettingBool("labelAtTop", enabled))
                    .into()
            } else {
                Space::new().height(1).into()
            };

        scrollable(
            column![
                text(fl!("display-appearance-help")),
                settings_row(
                    "Appearance",
                    pick_list(
                        vec![
                            "System".to_string(),
                            "Light".to_string(),
                            "Dark".to_string()
                        ],
                        Some(appearance),
                        Message::AppearanceChanged,
                    )
                    .into(),
                ),
                checkbox(self.settings.show_labels)
                    .label(fl!("display-show-labels"))
                    .on_toggle(|enabled| Message::SettingBool("showLabels", enabled)),
                checkbox(self.settings.show_symbols)
                    .label(fl!("display-show-symbols"))
                    .on_toggle(|enabled| Message::SettingBool("showSymbols", enabled)),
                label_position,
                settings_row(
                    "Grid columns",
                    slider(
                        1..=12,
                        self.settings.grid_columns,
                        Message::GridColumnsChanged
                    )
                    .into(),
                ),
                settings_row(
                    fl!("display-font-scale"),
                    slider(0.75..=1.5, self.settings.font_size_scale, |value| {
                        Message::SettingFloat("fontSizeScale", value)
                    })
                    .step(0.05)
                    .into(),
                ),
                settings_row(
                    fl!("display-button-scale"),
                    slider(0.75..=1.5, self.settings.button_scale, |value| {
                        Message::SettingFloat("buttonScale", value)
                    })
                    .step(0.05)
                    .into(),
                ),
                settings_row(
                    fl!("display-input-scale"),
                    slider(0.75..=1.5, self.settings.input_field_scale, |value| {
                        Message::SettingFloat("inputFieldScale", value)
                    })
                    .step(0.05)
                    .into(),
                ),
                checkbox(self.settings.high_contrast_mode)
                    .label(fl!("display-high-contrast"))
                    .on_toggle(|enabled| Message::SettingBool("highContrastMode", enabled)),
                checkbox(self.settings.word_type_color_scheme == "Fitzgerald")
                    .label(fl!("display-word-type-colors"))
                    .on_toggle(|enabled| Message::SettingBool("wordTypeColorScheme", enabled)),
                checkbox(self.settings.board_show_message_bar)
                    .label(fl!("display-message-bar"))
                    .on_toggle(|enabled| Message::SettingBool("boardShowMessageBar", enabled)),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }

    fn access_settings_view(&self) -> Element<'_, Message> {
        let editing_access_controls: Element<'_, Message> = if !self.editing_access.supported {
            column![
                text(fl!("editing-access-title")).size(24),
                text(fl!("editing-access-unavailable")),
            ]
            .spacing(10)
            .into()
        } else if self.editing_access.enabled && !self.editing_access.unlocked {
            column![
                text(fl!("editing-access-title")).size(24),
                text(fl!("editing-access-locked-help")),
                text_input(&fl!("editing-access-code"), &self.editing_access_code)
                    .on_input(Message::EditingAccessCodeChanged)
                    .on_submit(Message::UnlockEditingAccess)
                    .secure(true)
                    .padding(12),
                labeled_icon_button(
                    "changes-allow-symbolic",
                    fl!("editing-access-unlock"),
                    Message::UnlockEditingAccess
                ),
                text(fl!(
                    "editing-access-failed-attempts",
                    attempts = self.editing_access.failed_attempts
                )),
            ]
            .spacing(10)
            .into()
        } else {
            let heading = if self.editing_access.enabled {
                fl!("editing-access-change-title")
            } else {
                fl!("editing-access-enable-title")
            };
            let mut controls = column![
                text(heading).size(24),
                text(fl!("editing-access-help")),
                text_input(
                    &fl!("editing-access-new-code"),
                    &self.editing_access_new_code
                )
                .on_input(Message::EditingAccessNewCodeChanged)
                .secure(true)
                .padding(12),
                text_input(
                    &fl!("editing-access-confirm-code"),
                    &self.editing_access_confirmation
                )
                .on_input(Message::EditingAccessConfirmationChanged)
                .on_submit(Message::ConfigureEditingAccess)
                .secure(true)
                .padding(12),
                labeled_icon_button(
                    "document-save-symbolic",
                    if self.editing_access.enabled {
                        fl!("editing-access-change")
                    } else {
                        fl!("editing-access-enable")
                    },
                    Message::ConfigureEditingAccess
                ),
            ]
            .spacing(10);
            if self.editing_access.enabled {
                controls = controls
                    .push(labeled_icon_button(
                        "changes-prevent-symbolic",
                        fl!("editing-access-lock-now"),
                        Message::LockEditingAccess,
                    ))
                    .push(
                        text_input(
                            &fl!("editing-access-current-code-disable"),
                            &self.editing_access_code,
                        )
                        .on_input(Message::EditingAccessCodeChanged)
                        .secure(true)
                        .padding(12),
                    )
                    .push(labeled_icon_button(
                        "edit-delete-symbolic",
                        fl!("editing-access-disable"),
                        Message::DisableEditingAccess,
                    ));
            }
            controls.into()
        };

        scrollable(
            column![
                text("Interaction").size(24),
                text("Choose how you point, select, and take a break."),
                settings_row(
                    "Select key",
                    pick_list(
                        vec!["Off".to_string(), "Space".to_string(), "Enter".to_string(), "F8".to_string(), "F9".to_string()],
                        Some(if self.settings.select_key_binding.is_empty() { "Off".to_string() } else { self.settings.select_key_binding.clone() }),
                        |value| Message::SettingString("selectKeyBinding", if value == "Off" { String::new() } else { value })
                    ).into(),
                ),
                settings_row(
                    "Rest mode key",
                    pick_list(
                        vec!["Off".to_string(), "Space".to_string(), "Enter".to_string(), "F8".to_string(), "F9".to_string()],
                        Some(if self.settings.rest_mode_key_binding.is_empty() { "Off".to_string() } else { self.settings.rest_mode_key_binding.clone() }),
                        |value| Message::SettingString("restModeKeyBinding", if value == "Off" { String::new() } else { value })
                    ).into(),
                ),
                settings_row(
                    "Pointer emphasis",
                    pick_list(
                        vec!["System".to_string(), "Ring".to_string(), "Outline".to_string()],
                        Some(self.settings.pointer_emphasis_style.clone()),
                        |value| Message::SettingString("pointerEmphasisStyle", value)
                    ).into(),
                ),
                row![
                    text(format!("Marker size: {:.1}×", self.settings.pointer_emphasis_scale)).width(Fill),
                    slider(1.0..=3.0, self.settings.pointer_emphasis_scale, |value| Message::SettingFloat("pointerEmphasisScale", value)).step(0.25).width(240)
                ].spacing(10),
                Space::new().height(8),
                text(fl!("access-selection-timing")).size(24),
                row![
                    text(format!(
                        "Hold to select: {} ms",
                        self.settings.hold_to_select_millis
                    ))
                    .width(Fill),
                    slider(
                        0..=3000,
                        self.settings.hold_to_select_millis as i32,
                        |value| Message::SettingMillis("holdToSelectMillis", value as i64)
                    )
                    .step(100)
                    .width(240)
                ]
                .spacing(10),
                row![
                    text(format!(
                        "Dwell to select: {} ms",
                        self.settings.dwell_to_select_millis
                    ))
                    .width(Fill),
                    slider(
                        0..=5000,
                        self.settings.dwell_to_select_millis as i32,
                        |value| Message::SettingMillis("dwellToSelectMillis", value as i64)
                    )
                    .step(100)
                    .width(240)
                ]
                .spacing(10),
                row![
                    text(format!(
                        "Ignore repeated selections: {} ms",
                        self.settings.selection_debounce_millis
                    ))
                    .width(Fill),
                    slider(
                        0..=2000,
                        self.settings.selection_debounce_millis as i32,
                        |value| Message::SettingMillis("selectionDebounceMillis", value as i64)
                    )
                    .step(50)
                    .width(240)
                ]
                .spacing(10),
                row![
                    text(format!(
                        "Selection highlight: {} ms",
                        self.settings.selection_highlight_millis
                    ))
                    .width(Fill),
                    slider(
                        0..=3000,
                        self.settings.selection_highlight_millis as i32,
                        |value| Message::SettingMillis("selectionHighlightMillis", value as i64)
                    )
                    .step(100)
                    .width(240)
                ]
                .spacing(10),
                row![
                    text(fl!("access-speech-policy")).width(Fill),
                    pick_list(
                        vec!["Immediate".to_string(), "SentenceOnly".to_string()],
                        Some(self.settings.speech_policy.clone()),
                        |value| Message::SettingString("speechPolicy", value),
                    )
                    .width(180),
                ]
                .spacing(10),
                checkbox(self.settings.selection_sound_enabled)
                    .label(fl!("access-selection-sound"))
                    .on_toggle(|value| Message::SettingBool("selectionSoundEnabled", value)),
                checkbox(self.settings.auditory_fishing_enabled)
                    .label(fl!("access-auditory-cue"))
                    .on_toggle(|value| Message::SettingBool("auditoryFishingEnabled", value)),
                Space::new().height(8),
                text(fl!("access-switch-scanning")).size(24),
                checkbox(self.settings.scanning_enabled)
                    .label(fl!("access-enable-scanning"))
                    .on_toggle(|value| Message::SettingBool("scanningEnabled", value)),
                checkbox(self.settings.scan_phrase_grid_enabled)
                    .label(fl!("access-scan-grid"))
                    .on_toggle(|value| Message::SettingBool("scanPhraseGridEnabled", value)),
                checkbox(self.settings.scan_category_items_enabled)
                    .label(fl!("access-scan-categories"))
                    .on_toggle(|value| Message::SettingBool("scanCategoryItemsEnabled", value)),
                row![
                    text(format!(
                        "Automatic advance: {:.1} seconds",
                        self.settings.scan_auto_advance_seconds
                    ))
                    .width(Fill),
                    slider(
                        0.2..=5.0,
                        self.settings.scan_auto_advance_seconds,
                        |value| Message::SettingFloat("scanAutoAdvanceSeconds", value)
                    )
                    .step(0.1_f32)
                    .width(240)
                ]
                .spacing(10),
                text(fl!("access-input-help")),
                Space::new().height(12),
                editing_access_controls,
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }

    fn startup_settings_view(&self) -> Element<'_, Message> {
        let startup_board_set_id = self
            .settings
            .startup_board_set_id
            .clone()
            .unwrap_or_default();

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
                        self.board_sets
                            .iter()
                            .map(|set| set.name.clone())
                            .collect::<Vec<_>>(),
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
                    .label(fl!("privacy-show-history"))
                    .on_toggle(|v| Message::SettingBool("historyVisible", v)),
                text(fl!("privacy-analytics-help")),
                Space::new().height(6),
                row![
                    labeled_icon_button(
                        "document-save-symbolic",
                        "Export speech history…",
                        Message::ExportHistory
                    ),
                    labeled_icon_button(
                        "document-open-symbolic",
                        "Import speech history…",
                        Message::ImportHistory
                    ),
                    labeled_icon_button(
                        "edit-delete-symbolic",
                        "Clear speech history",
                        Message::ClearHistory
                    ),
                ]
                .spacing(8)
                .wrap(),
                Space::new().height(6),
                text(fl!("privacy-backup-title")).size(16),
                row![
                    labeled_icon_button(
                        "document-save-symbolic",
                        "Export backup…",
                        Message::ExportBackup
                    ),
                    labeled_icon_button(
                        "document-open-symbolic",
                        "Import backup…",
                        Message::ImportBackup
                    ),
                ]
                .spacing(8)
                .wrap(),
                if !self.status.is_empty() {
                    let el: Element<'_, Message> = text(&self.status).into();
                    el
                } else {
                    let el: Element<'_, Message> = Space::new().into();
                    el
                },
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
                    .label(fl!("partner-mirror"))
                    .on_toggle(Message::PartnerEnabled),
                settings_row(
                    "Font size",
                    slider(
                        16..=34,
                        self.settings.partner_window_font_size,
                        Message::PartnerFontChanged
                    )
                    .into(),
                ),
                checkbox(self.settings.partner_window_idle_enabled)
                    .label(fl!("partner-idle-face"))
                    .on_toggle(Message::PartnerIdleChanged),
                Space::new().height(6),
                text(format!(
                    "Device: {} · Display: {}",
                    if connected {
                        "connected"
                    } else {
                        "not connected"
                    },
                    if active { "active" } else { "inactive" }
                )),
            ]
            .spacing(14),
        )
        .height(Fill)
        .into()
    }
}

fn settings_row<'a>(
    label: impl Into<Cow<'a, str>>,
    control: Element<'a, Message>,
) -> Element<'a, Message> {
    row![text(label.into()).width(210), container(control).width(360),]
        .align_y(cosmic::iced::alignment::Alignment::Center)
        .into()
}

fn board_template_options() -> Vec<String> {
    ["Blank", "Calculator"]
    .into_iter()
    .map(str::to_string)
    .collect()
}

fn uses_regular_board_grid(fields: &[BoardField], rows: usize, columns: usize) -> bool {
    fields.len() == rows * columns
        && fields
            .iter()
            .all(|field| field.row_span.max(1) == 1 && field.column_span.max(1) == 1)
}

fn board_template_key(template: &str) -> &'static str {
    match template {
        "Quick Core 24" => "quick-core-24",
        "Quick Core 40" => "quick-core-40",
        "Quick Core 60" => "quick-core-60",
        "Quick Core 84" => "quick-core-84",
        "Quick Core 112" => "quick-core-112",
        "Calculator" => "calculator",
        _ => "blank",
    }
}

fn header_navigation_button<'a>(
    icon_name: &'a str,
    label: String,
    selected: bool,
    message: Message,
) -> Element<'a, Message> {
    cosmic_button::icon(symbolic_icon(icon_name))
        .label(label.clone())
        .name(label.clone())
        .tooltip(label)
        .icon_size(22)
        .height(cosmic::iced::Length::Fixed(48.0))
        .padding([8, 12])
        .selected(selected)
        .on_press(message)
        .into()
}

fn touch_icon_button<'a>(
    icon_name: &'a str,
    label: impl Into<Cow<'a, str>>,
    message: Message,
) -> Element<'a, Message> {
    let label = label.into();
    cosmic_button::icon(symbolic_icon(icon_name))
        .name(label.clone())
        .description(label.clone())
        .tooltip(label)
        .medium()
        .on_press(message)
        .into()
}

fn compact_icon_button<'a>(
    icon_name: &'a str,
    label: impl Into<Cow<'a, str>>,
    message: Message,
) -> Element<'a, Message> {
    let label = label.into();
    cosmic_button::icon(symbolic_icon(icon_name))
        .name(label.clone())
        .description(label.clone())
        .tooltip(label)
        .icon_size(20)
        .width(cosmic::iced::Length::Fixed(48.0))
        .height(cosmic::iced::Length::Fixed(48.0))
        .on_press(message)
        .into()
}

fn aac_toolbar_button<'a>(
    icon_name: &'a str,
    label: impl Into<Cow<'a, str>>,
    message: Message,
) -> Element<'a, Message> {
    let label = label.into();
    cosmic_button::icon(symbolic_icon(icon_name))
        .name(label.clone())
        .description(label.clone())
        .tooltip(label)
        .icon_size(28)
        .width(cosmic::iced::Length::Fixed(68.0))
        .height(cosmic::iced::Length::Fixed(68.0))
        .on_press(message)
        .into()
}

fn labeled_icon_button<'a>(
    icon_name: &'a str,
    label: impl Into<Cow<'a, str>>,
    message: Message,
) -> Element<'a, Message> {
    let label = label.into();
    cosmic_button::standard(label)
        .leading_icon(symbolic_icon(icon_name))
        .icon_size(20)
        .height(cosmic::iced::Length::Fixed(48.0))
        .on_press(message)
        .into()
}

fn touch_text_button<'a>(label: impl Into<Cow<'a, str>>, message: Message) -> Element<'a, Message> {
    cosmic_button::standard(label.into())
        .height(cosmic::iced::Length::Fixed(48.0))
        .on_press(message)
        .into()
}

fn nav_button<'a>(
    icon_name: &'a str,
    label: String,
    selected: bool,
    message: Message,
) -> Element<'a, Message> {
    cosmic_button::icon(symbolic_icon(icon_name))
        .label(label.clone())
        .name(label)
        .icon_size(22)
        .height(cosmic::iced::Length::Fixed(52.0))
        .padding([10, 12])
        .selected(selected)
        .on_press(message)
        .width(Fill)
        .into()
}

fn symbolic_icon(name: &str) -> icon::Named {
    let aliases: Option<&[&'static str]> = match name {
        "accessories-dictionary-symbolic" => Some(&[
            "accessories-text-editor-symbolic",
            "accessories-text-editor",
            "help-contents-symbolic",
            "help-contents",
        ]),
        "changes-allow-symbolic" => Some(&["emblem-unlocked-symbolic", "emblem-unlocked"]),
        "changes-prevent-symbolic" => Some(&["emblem-locked-symbolic", "emblem-locked"]),
        "object-select-symbolic" => Some(&["edit-select-all-symbolic", "edit-select-all"]),
        "video-joined-displays-symbolic" => Some(&["video-display-symbolic", "video-display"]),
        _ => None,
    };

    let icon = icon::from_name(name);
    aliases.map_or(icon.clone(), |names| {
        icon.fallback(Some(icon::IconFallback::Names(
            names.iter().map(|name| Cow::Borrowed(*name)).collect(),
        )))
    })
}

#[derive(Clone)]
struct Api {
    base: String,
    client: Client,
    token: String,
}

impl Api {
    fn new() -> Self {
        Self {
            base: env::var("WINGMATE_API_URL").unwrap_or_else(|_| DEFAULT_API_URL.into()),
            client: Client::new(),
            token: current_bridge_token(),
        }
    }

    fn bootstrap(&self) -> Task<Message> {
        Task::batch(vec![
            self.load_phrases(),
            self.load_categories(),
            self.load_voices(),
            self.load_selected_voice(),
            self.load_settings(),
            self.load_history(),
            self.load_board_sets(),
            self.load_azure_config(),
            self.load_editing_access(),
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
    fn load_selected_voice(&self) -> Task<Message> {
        self.get("/api/voices/selected", Message::LoadedSelectedVoice)
    }
    fn load_settings(&self) -> Task<Message> {
        self.get("/api/settings", Message::LoadedSettings)
    }
    fn access_input(&self, event: &'static str, target_id: Option<String>, key: Option<String>) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_json(
                    Method::POST,
                    "/api/access-input",
                    Some(serde_json::json!({"event": event, "targetId": target_id, "key": key})),
                )
                .await
            },
            Message::AccessInputUpdated,
        )
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
    fn load_speech_state(&self) -> Task<Message> {
        self.get("/api/speak/status", Message::LoadedSpeechState)
    }
    fn load_editing_access(&self) -> Task<Message> {
        self.get("/api/editing-access", Message::LoadedEditingAccess)
    }
    fn load_board_sets(&self) -> Task<Message> {
        self.get("/api/boardsets", Message::LoadedBoardSets)
    }
    fn load_preset_progress(&self) -> Task<Message> {
        self.get(
            "/api/boardsets/preset-progress",
            Message::LoadedPresetProgress,
        )
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
            Message::SpeechStarted,
        )
    }

    fn preview_voice(&self, voice: String, text: String) -> Task<Message> {
        self.request(
            Method::POST,
            "/api/voices/preview",
            Some(serde_json::json!({"voice": voice, "text": text})),
            Message::SpeechStarted,
        )
    }

    fn configure_editing_access(&self, code: String) -> Task<Message> {
        self.editing_access_request(
            Method::PUT,
            "/api/editing-access/code",
            Some(serde_json::json!({"code": code})),
        )
    }

    fn unlock_editing_access(&self, code: String) -> Task<Message> {
        self.editing_access_request(
            Method::POST,
            "/api/editing-access/unlock",
            Some(serde_json::json!({"code": code})),
        )
    }

    fn lock_editing_access(&self) -> Task<Message> {
        self.editing_access_request(Method::POST, "/api/editing-access/lock", None)
    }

    fn disable_editing_access(&self, code: String) -> Task<Message> {
        self.editing_access_request(
            Method::POST,
            "/api/editing-access/disable",
            Some(serde_json::json!({"code": code})),
        )
    }

    fn editing_access_request(
        &self,
        method: Method,
        path: &'static str,
        body: Option<serde_json::Value>,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move { api.request_json(method, path, body).await },
            Message::LoadedEditingAccess,
        )
    }

    fn speech_action(&self, path: &'static str) -> Task<Message> {
        self.request(Method::POST, path, None, Message::SpeechControlFinished)
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

    fn update_phrase(
        &self,
        id: String,
        text: String,
        voice: String,
        image_url: Option<String>,
        parent_id: Option<String>,
        recording_path: Option<String>,
        is_hidden: bool,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/phrases/{}", encode_segment(&id));
                api.request_unit(
                    Method::PUT,
                    &path,
                    Some(serde_json::json!({
                        "text": text,
                        "name": voice,
                        "imageUrl": image_url,
                        "parentId": parent_id,
                        "recordingPath": recording_path,
                        "isHidden": is_hidden,
                    })),
                )
                .await?;
                tokio::time::sleep(Duration::from_millis(100)).await;
                api.request_json(Method::GET, "/api/phrases", None).await
            },
            Message::LoadedPhrases,
        )
    }

    fn move_phrase(&self, id: String, delta: i32) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!("/api/phrases/{}/move", encode_segment(&id));
                api.request_unit(
                    Method::PUT,
                    &path,
                    Some(serde_json::json!({"delta": delta})),
                )
                .await?;
                api.request_json(Method::GET, "/api/phrases", None).await
            },
            Message::LoadedPhrases,
        )
    }

    fn import_phrase_image(&self, path: PathBuf) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_json(
                    Method::POST,
                    "/api/images/import",
                    Some(serde_json::json!({"path": path})),
                )
                .await
            },
            Message::PhraseImageImported,
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

    fn category_mutation(
        &self,
        id: String,
        suffix: &'static str,
        body: serde_json::Value,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = if suffix.is_empty() {
                    format!("/api/categories/{}", encode_segment(&id))
                } else {
                    format!("/api/categories/{}/{}", encode_segment(&id), suffix)
                };
                api.request_unit(Method::PUT, &path, Some(body)).await?;
                api.request_json(Method::GET, "/api/categories", None).await
            },
            Message::LoadedCategories,
        )
    }

    fn rename_category(&self, id: String, name: String) -> Task<Message> {
        self.category_mutation(id, "", serde_json::json!({"name": name}))
    }

    fn move_category(&self, id: String, delta: i32) -> Task<Message> {
        self.category_mutation(id, "move", serde_json::json!({"delta": delta}))
    }

    fn put_json(&self, path: &'static str, body: serde_json::Value) -> Task<Message> {
        self.request(Method::PUT, path, Some(body), Message::ActionFinished)
    }

    fn patch_setting(&self, key: &'static str, value: serde_json::Value) -> Task<Message> {
        self.put_json("/api/settings", serde_json::json!({ key: value }))
    }

    fn patch_setting_and_reload_board(
        &self,
        key: &'static str,
        value: serde_json::Value,
        board_set_id: String,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::PUT,
                    "/api/settings",
                    Some(serde_json::json!({ key: value })),
                )
                .await?;
                let path = format!("/api/boardsets/{}", encode_segment(&board_set_id));
                api.request_json(Method::GET, &path, None).await
            },
            Message::LoadedBoardGraph,
        )
    }

    fn update_board_session(
        &self,
        board_id: String,
        operation: &'static str,
        button_id: Option<String>,
        tokens: Vec<String>,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_json(
                    Method::POST,
                    "/api/board-session",
                    Some(serde_json::json!({
                        "boardId": board_id,
                        "operation": operation,
                        "buttonId": button_id,
                        "tokens": tokens,
                    })),
                )
                .await
            },
            Message::BoardSessionUpdated,
        )
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
            Message::AzureConfigSaved,
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

    fn add_pronunciation(&self, word: String, phoneme: String, alphabet: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/pronunciation",
                    Some(
                        serde_json::json!({"word": word, "phoneme": phoneme, "alphabet": alphabet}),
                    ),
                )
                .await?;
                api.request_json(Method::GET, "/api/pronunciation", None)
                    .await
            },
            Message::LoadedDictionary,
        )
    }

    fn import_pronunciations(&self, path: PathBuf) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/pronunciation/import",
                    Some(serde_json::json!({"path": path})),
                )
                .await?;
                api.request_json(Method::GET, "/api/pronunciation", None)
                    .await
            },
            Message::LoadedDictionary,
        )
    }

    fn export_pronunciations(&self, path: PathBuf) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let response = api
                    .send_with_startup_retry(
                        Method::GET,
                        "/api/pronunciation/export?format=csv",
                        None,
                    )
                    .await?;
                let bytes = response
                    .bytes()
                    .await
                    .map_err(|error| format!("Could not read dictionary export: {error}"))?;
                std::fs::write(path, bytes)
                    .map_err(|error| format!("Could not save dictionary: {error}"))
            },
            Message::ActionFinished,
        )
    }

    fn fetch_images(&self, sources: Vec<String>) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let mut results = Vec::with_capacity(sources.len());
                for source in sources {
                    let result = if source.starts_with('/') {
                        std::fs::read(&source)
                            .map(|bytes| LoadedImageData {
                                content_type: local_image_content_type(&source).into(),
                                bytes,
                            })
                            .map_err(|error| format!("Could not read local symbol: {error}"))
                    } else {
                        api.request_json::<ImagePayload>(
                            Method::POST,
                            "/api/images/fetch",
                            Some(serde_json::json!({"url": source})),
                        )
                        .await
                        .and_then(decode_image_payload)
                    };
                    results.push((source, result));
                }
                results
            },
            Message::LoadedImages,
        )
    }

    fn import_image(&self, path: PathBuf) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_json(
                    Method::POST,
                    "/api/images/import",
                    Some(serde_json::json!({"path": path})),
                )
                .await
            },
            Message::CellLocalImageImported,
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
                    .header(TOKEN_HEADER, api.token)
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

    fn load_board_graph(&self, id: String, include_all: bool) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!(
                    "/api/boardsets/{}{}",
                    encode_segment(&id),
                    if include_all { "?all=true" } else { "" },
                );
                api.request_json(Method::GET, &path, None).await
            },
            Message::LoadedBoardGraph,
        )
    }

    fn load_board_page(&self, set_id: String, board_id: String) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = format!(
                    "/api/boardsets/{}?boardId={}",
                    encode_segment(&set_id),
                    encode_segment(&board_id),
                );
                api.request_json(Method::GET, &path, None).await
            },
            Message::LoadedBoardPage,
        )
    }

    fn create_board_set(
        &self,
        name: String,
        rows: i32,
        columns: i32,
        template: String,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                api.request_unit(
                    Method::POST,
                    "/api/boardsets",
                    Some(serde_json::json!({
                        "name": name, "rows": rows, "columns": columns,
                        "template": board_template_key(&template),
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

    fn board_mutation(
        &self,
        set_id: String,
        board_id: String,
        suffix: &'static str,
        method: Method,
        body: Option<serde_json::Value>,
    ) -> Task<Message> {
        let api = self.clone();
        Task::perform(
            async move {
                let path = if suffix.is_empty() {
                    format!(
                        "/api/boardsets/{}/boards/{}",
                        encode_segment(&set_id),
                        encode_segment(&board_id)
                    )
                } else {
                    format!(
                        "/api/boardsets/{}/boards/{}/{}",
                        encode_segment(&set_id),
                        encode_segment(&board_id),
                        suffix
                    )
                };
                api.request_unit(method, &path, body).await?;
                let graph_path = format!("/api/boardsets/{}", encode_segment(&set_id));
                api.request_json(Method::GET, &graph_path, None).await
            },
            Message::LoadedBoardGraph,
        )
    }

    fn rename_board(&self, set_id: String, board_id: String, name: String) -> Task<Message> {
        self.board_mutation(
            set_id,
            board_id,
            "name",
            Method::PUT,
            Some(serde_json::json!({"name": name})),
        )
    }

    fn resize_board(
        &self,
        set_id: String,
        board_id: String,
        rows: i32,
        columns: i32,
    ) -> Task<Message> {
        self.board_mutation(
            set_id,
            board_id,
            "size",
            Method::PUT,
            Some(serde_json::json!({"rows": rows, "columns": columns})),
        )
    }

    fn delete_board(&self, set_id: String, board_id: String) -> Task<Message> {
        self.board_mutation(set_id, board_id, "", Method::DELETE, None)
    }

    fn set_root_board(&self, set_id: String, board_id: String) -> Task<Message> {
        self.board_mutation(set_id, board_id, "root", Method::PUT, None)
    }

    fn update_page_behavior(
        &self,
        set_id: String,
        board_id: String,
        activation: Option<String>,
        return_behavior: Option<String>,
    ) -> Task<Message> {
        self.board_mutation(
            set_id,
            board_id,
            "settings",
            Method::PUT,
            Some(serde_json::json!({
                "activationBehavior": activation,
                "returnBehavior": return_behavior,
            })),
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
        image_url: Option<String>,
        background_color: String,
        word_type: String,
        hidden: bool,
        linked_board_id: Option<String>,
        actions: Vec<String>,
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
                    Some(serde_json::json!({
                        "label": label,
                        "vocalization": vocalization,
                        "imageUrl": image_url,
                        "backgroundColor": background_color,
                        "wordType": if word_type == "Automatic" { None } else { Some(word_type) },
                        "hidden": hidden,
                        "linkedBoardId": linked_board_id,
                        "actions": actions,
                    })),
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
            let mut request = self
                .client
                .request(method.clone(), &url)
                .header(TOKEN_HEADER, &self.token);
            if let Some(value) = body.clone() {
                request = request.json(&value);
            }
            match request.send().await {
                Ok(response) if response.status().is_success() => return Ok(response),
                Ok(response) if response.status().as_u16() == 503 => {
                    last_error = format!("Wingmate service is starting ({path})")
                }
                Ok(response) => {
                    let status = response.status();
                    let detail = response.text().await.unwrap_or_default();
                    return Err(if detail.is_empty() {
                        format!("Wingmate service returned {status} for {path}")
                    } else {
                        format!("Wingmate service returned {status} for {path}: {detail}")
                    });
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

fn parse_hex_color(value: &str) -> Option<cosmic::iced::Color> {
    let value = value.trim().trim_start_matches('#');
    if value.len() != 6 {
        return None;
    }
    let red = u8::from_str_radix(&value[0..2], 16).ok()?;
    let green = u8::from_str_radix(&value[2..4], 16).ok()?;
    let blue = u8::from_str_radix(&value[4..6], 16).ok()?;
    Some(cosmic::iced::Color::from_rgb8(red, green, blue))
}

fn status_is_error(status: &str) -> bool {
    let normalized = status.to_lowercase();
    [
        "error",
        "failed",
        "cannot",
        "could not",
        "unavailable",
        "unsupported",
        "incorrect",
        "mismatch",
        "fejl",
        "mislykk",
        "kan ikke",
        "utilgængelig",
        "forkert",
    ]
    .iter()
    .any(|needle| normalized.contains(needle))
}

fn colored_button_class(color: cosmic::iced::Color) -> cosmic::theme::iced::Button {
    cosmic::theme::iced::Button::Custom(Box::new(move |_theme, _status| {
        let foreground = contrasting_foreground(color);
        cosmic::iced::widget::button::Style {
            background: Some(color.into()),
            text_color: foreground,
            icon_color: Some(foreground),
            border_radius: 12.0.into(),
            ..Default::default()
        }
    }))
}

fn contrasting_foreground(color: cosmic::iced::Color) -> cosmic::iced::Color {
    let luminance = 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b;
    if luminance > 0.5 {
        cosmic::iced::Color::BLACK
    } else {
        cosmic::iced::Color::WHITE
    }
}

fn decode_image_payload(payload: ImagePayload) -> Result<LoadedImageData, String> {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD
        .decode(payload.data)
        .map(|bytes| LoadedImageData {
            bytes,
            content_type: payload.content_type,
        })
        .map_err(|error| format!("Could not decode image: {error}"))
}

fn local_image_content_type(path: &str) -> &'static str {
    let lowercase = path.to_ascii_lowercase();
    if lowercase.ends_with(".svg") {
        "image/svg+xml"
    } else if lowercase.ends_with(".jpg") || lowercase.ends_with(".jpeg") {
        "image/jpeg"
    } else if lowercase.ends_with(".webp") {
        "image/webp"
    } else {
        "image/png"
    }
}

fn play_audio_file(path: String) -> Task<Message> {
    Task::perform(
        async move {
            for player in ["pw-play", "paplay", "aplay"] {
                let available = Command::new("which")
                    .arg(player)
                    .output()
                    .is_ok_and(|output| output.status.success());
                if available {
                    Command::new(player)
                        .arg(&path)
                        .spawn()
                        .map_err(|error| format!("Could not play recording: {error}"))?;
                    return Ok(());
                }
            }
            Err("No audio player found (install PipeWire, PulseAudio, or ALSA tools)".into())
        },
        Message::ActionFinished,
    )
}

fn play_selection_sound() {
    if Command::new("which")
        .arg("canberra-gtk-play")
        .output()
        .is_ok_and(|output| output.status.success())
    {
        let _ = Command::new("canberra-gtk-play")
            .args(["--id", "button-pressed"])
            .spawn();
        return;
    }
    if Command::new("which")
        .arg("aplay")
        .output()
        .is_ok_and(|output| output.status.success())
    {
        if let Ok(mut child) = Command::new("aplay")
            .arg("-q")
            .stdin(Stdio::piped())
            .spawn()
        {
            if let Some(mut input) = child.stdin.take() {
                std::thread::spawn(move || {
                    let _ = input.write_all(&selection_beep_wav());
                });
            }
        }
    }
}

fn selection_beep_wav() -> Vec<u8> {
    const SAMPLE_RATE: u32 = 16_000;
    const SAMPLES: usize = 800;
    let data_size = (SAMPLES * 2) as u32;
    let mut bytes = Vec::with_capacity(44 + data_size as usize);
    bytes.extend_from_slice(b"RIFF");
    bytes.extend_from_slice(&(36 + data_size).to_le_bytes());
    bytes.extend_from_slice(b"WAVEfmt ");
    bytes.extend_from_slice(&16_u32.to_le_bytes());
    bytes.extend_from_slice(&1_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u16.to_le_bytes());
    bytes.extend_from_slice(&SAMPLE_RATE.to_le_bytes());
    bytes.extend_from_slice(&(SAMPLE_RATE * 2).to_le_bytes());
    bytes.extend_from_slice(&2_u16.to_le_bytes());
    bytes.extend_from_slice(&16_u16.to_le_bytes());
    bytes.extend_from_slice(b"data");
    bytes.extend_from_slice(&data_size.to_le_bytes());
    for index in 0..SAMPLES {
        let phase = index as f32 * 880.0 * std::f32::consts::TAU / SAMPLE_RATE as f32;
        let sample = (phase.sin() * 4_000.0) as i16;
        bytes.extend_from_slice(&sample.to_le_bytes());
    }
    bytes
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

const TOKEN_HEADER: &str = "x-wingmate-token";

fn state_home() -> PathBuf {
    if let Ok(state) = env::var("XDG_STATE_HOME") {
        if !state.trim().is_empty() {
            return PathBuf::from(state);
        }
    }
    PathBuf::from(
        env::var("HOME").unwrap_or_else(|_| ".".into()),
    )
    .join(".local/state")
}

fn bridge_token_file() -> PathBuf {
    state_home().join("wingmate").join("bridge-token")
}

/// Per-process bridge capability token, shared between the Rust client and the
/// Kotlin bridge it spawns. Preference order matches the Kotlin side:
/// 1. token injected into the child environment when we spawn the bridge,
/// 2. token the already-running bridge persisted for the reuse case,
/// 3. a freshly generated random token (also persisted and passed on spawn).
fn current_bridge_token() -> String {
    static TOKEN: OnceLock<String> = OnceLock::new();
    TOKEN.get_or_init(|| {
        if let Some(token) = env::var("WINGMATE_BRIDGE_TOKEN").ok() {
            let trimmed = token.trim().to_string();
            if trimmed.len() >= 16 {
                return trimmed;
            }
        }
        if let Ok(token) = std::fs::read_to_string(bridge_token_file()) {
            let trimmed = token.trim().to_string();
            if trimmed.len() >= 16 {
                return trimmed;
            }
        }
        let token = generate_bridge_token();
        persist_bridge_token(&token);
        token
    })
    .clone()
}

fn generate_bridge_token() -> String {
    use std::io::Read;
    let mut bytes = [0u8; 32];
    if let Ok(mut from) = std::fs::File::open("/dev/urandom") {
        let _ = from.read_exact(&mut bytes);
    } else {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0) as u64;
        bytes[..8].copy_from_slice(&now.to_le_bytes());
        bytes[8..16].copy_from_slice(&(std::process::id().to_le_bytes()));
    }
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn persist_bridge_token(token: &str) {
    let path = bridge_token_file();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let _ = std::fs::write(&path, format!("{token}\n"));
}

fn bridge_already_running() -> bool {
    let addr = "127.0.0.1:8765";
    std::net::TcpStream::connect_timeout(
        &addr.parse().expect("valid bridge address"),
        Duration::from_millis(300),
    )
    .is_ok()
}

fn start_bridge_server() -> Option<Child> {
    if env::var_os("WINGMATE_API_URL").is_some() {
        return None;
    }
    if bridge_already_running() {
        eprintln!(
            "Wingmate backend already running on {DEFAULT_API_URL}; reusing existing backend"
        );
        return None;
    }
    let jar = find_fat_jar();
    match Command::new("java")
        .arg("-jar")
        .arg(&jar)
        .arg("--no-partner-window")
        .env("WINGMATE_BRIDGE_TOKEN", current_bridge_token())
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn board_image_resolve_payload_includes_required_obf_id() {
        let image = BoardImage {
            id: "image-1".into(),
            data: None,
            data_url: None,
            path: None,
            url: Some("https://example.com/symbol.png".into()),
            content_type: None,
            symbol: None,
        };

        assert_eq!(image.resolve_payload()["id"], "image-1");
    }

    #[test]
    fn bridge_token_header_matches_the_kotlin_bridge_contract() {
        assert_eq!(TOKEN_HEADER, "x-wingmate-token");
    }

    #[test]
    fn editing_access_gate_covers_content_mutations_but_not_communication() {
        assert!(Message::ToggleBoardEdit.requires_editing_access());
        assert!(Message::AddPhrase.requires_editing_access());
        assert!(Message::DeleteBoardSet("set".into()).requires_editing_access());
        assert!(!Message::Speak("hello".into()).requires_editing_access());
        assert!(!Message::BoardNavigateHome.requires_editing_access());
        assert!(!Message::BoardNavigateBack.requires_editing_access());
        assert!(!Message::UnlockEditingAccess.requires_editing_access());
    }

    #[test]
    fn status_classifier_distinguishes_errors_from_progress() {
        assert!(status_is_error("Speech failed: device unavailable"));
        assert!(status_is_error("Talen mislykkedes"));
        assert!(!status_is_error("Speaking…"));
    }

    #[test]
    fn everyday_localization_keys_resolve_from_the_fallback_catalog() {
        assert_eq!(fl!("nav-keyboard"), "Keyboard");
        assert_eq!(fl!("voice-preview"), "Preview voice");
        assert_eq!(fl!("editing-access-title"), "Editing access code");
        assert_eq!(fl!("screens-library-title"), "Communication boards");
        assert_eq!(fl!("board-home"), "Home board");
    }

    #[test]
    fn quick_core_presets_stay_hidden_but_keep_stable_bridge_keys() {
        assert_eq!(board_template_options(), ["Blank", "Calculator"]);
        assert_eq!(board_template_key("Quick Core 24"), "quick-core-24");
        assert_eq!(board_template_key("Quick Core 112"), "quick-core-112");
        assert_eq!(board_template_key("Calculator"), "calculator");
        assert_eq!(board_template_key("unexpected"), "blank");
    }

    #[test]
    fn regular_boards_avoid_the_layered_span_renderer() {
        let fields = (0..8)
            .flat_map(|row| {
                (0..14).map(move |column| BoardField {
                    row,
                    column,
                    row_span: 1,
                    column_span: 1,
                    button_id: Some(format!("{row}-{column}")),
                })
            })
            .collect::<Vec<_>>();
        assert!(uses_regular_board_grid(&fields, 8, 14));

        let mut spanning = fields;
        spanning[0].column_span = 2;
        assert!(!uses_regular_board_grid(&spanning, 8, 14));
    }

    #[test]
    fn interface_scaling_is_bounded_and_handles_invalid_preferences() {
        assert_eq!(scaled_px(20.0, 1.25, 10.0, 96.0), 25.0);
        assert_eq!(scaled_px(20.0, 10.0, 10.0, 96.0), 40.0);
        assert_eq!(scaled_px(20.0, f32::NAN, 10.0, 96.0), 20.0);
        assert_eq!(scaled_px(8.0, 0.5, 10.0, 96.0), 10.0);
    }
}
