mod access;
mod bridge;
mod editor;
mod editor_update;
// Transport for native TD-I13 gaze (#129). The runner consumes it in the
// next milestone; see docs/GAZE_TD_I13.md.
#[allow(dead_code)]
mod gaze;
mod message_bar;
mod models;
mod screens;
mod settings;
mod speech;

use bridge::{Core, NativeCore};
use iced::widget::{button, column, container, row, text};
use iced::{Element, Fill, Task, Theme};
use models::{Activation, BoardSet, BoardView, Pronunciation, Settings, ThemeChoice};

fn main() -> iced::Result {
    // A local check that the gaze daemon streams the sample layout this build
    // pins; see docs/GAZE_TD_I13.md. Off the normal path and asked for by name.
    #[cfg(unix)]
    if std::env::args().nth(1).as_deref() == Some("--gaze-probe") {
        gaze::probe::run();
        return Ok(());
    }
    iced::application(App::boot, App::update, App::view)
        .title("Wingmate")
        .theme(App::theme)
        .subscription(|app| {
            iced::Subscription::batch([
                iced::system::theme_changes().map(Message::SystemTheme),
                iced::keyboard::listen().map(Message::Keyboard),
                if app.route == Route::Runner
                    && !app.access.is_paused
                    && app.settings.dwell_to_select_millis > 0
                    && app.access.current_target_id.is_some()
                {
                    iced::time::every(std::time::Duration::from_millis(16))
                        .map(|_| Message::Access(access::Event::Tick))
                } else {
                    iced::Subscription::none()
                },
                iced::event::listen_with(|event, _, _| match event {
                    iced::Event::Window(iced::window::Event::Unfocused)
                    | iced::Event::Mouse(iced::mouse::Event::CursorLeft) => {
                        Some(Message::Access(access::Event::Clear))
                    }
                    _ => None,
                }),
                iced::window::close_requests().map(|_| Message::CloseRequested),
            ])
        })
        .exit_on_close_request(false)
        .window_size((1100.0, 760.0))
        .run()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Route {
    Library,
    Runner,
    Settings,
    Editor,
}

struct App {
    access: access::State,
    access_clock: std::time::Instant,
    editor: Option<editor::Editor>,
    close_after_editor: bool,
    core: Box<dyn Core>,
    route: Route,
    previous_route: Route,
    library: Vec<BoardSet>,
    recents: Vec<String>,
    board: Option<BoardView>,
    settings: Settings,
    pronunciations: Vec<Pronunciation>,
    settings_section: settings::Section,
    pronunciation_word: String,
    pronunciation_replacement: String,
    error: Option<String>,
    system_theme: Theme,
}

#[derive(Debug, Clone)]
enum Message {
    Access(access::Event),
    DwellChanged(u32),
    RearmChanged(u32),
    SelectKeyChanged(String),
    RestKeyChanged(String),
    CloseRequested,
    Keyboard(iced::keyboard::Event),
    Editor(editor::Event),
    ShowLibrary,
    ChooseBoardFile,
    ImportFile(String),
    OpenBoardSet(String),
    Activate(String),
    Back,
    Clear,
    Hold,
    Speak,
    OpenSettings,
    CloseSettings,
    SelectSettingsSection(settings::Section),
    ThemeChanged(ThemeChoice),
    VoiceChanged(String),
    RateChanged(f32),
    SystemTheme(iced::theme::Mode),
    SpeechFinished(Result<(), String>),
    PronunciationWordChanged(String),
    PronunciationReplacementChanged(String),
    AddPronunciation,
    DeletePronunciation(String),
    ExportBackup,
    RestoreBackup,
    DismissError,
}

impl App {
    fn boot() -> (Self, Task<Message>) {
        let data = data_directory().to_string_lossy().into_owned();
        let core: Box<dyn Core> =
            Box::new(NativeCore::new(&data).expect("could not initialize the Kotlin core"));
        let app = Self::with_core(core);
        (app, iced::system::theme().map(Message::SystemTheme))
    }

    fn with_core(core: Box<dyn Core>) -> Self {
        let mut app = Self {
            access: access::State::default(),
            access_clock: std::time::Instant::now(),
            editor: None,
            close_after_editor: false,
            library: core.library().unwrap_or_default(),
            recents: core.recents().unwrap_or_default(),
            settings: core.settings().unwrap_or(Settings {
                theme: ThemeChoice::System,
                prefers_dark: None,
                voice: "default".into(),
                speech_rate: 1.0,
                hold_to_select_millis: 0,
                dwell_to_select_millis: 0,
                dwell_rearm_delay_millis: 120,
                select_key_binding: String::new(),
                rest_mode_key_binding: String::new(),
            }),
            pronunciations: core.pronunciations().unwrap_or_default(),
            core,
            route: Route::Library,
            previous_route: Route::Library,
            board: None,
            settings_section: settings::Section::default(),
            pronunciation_word: String::new(),
            pronunciation_replacement: String::new(),
            error: None,
            system_theme: Theme::Light,
        };
        app.refresh_library();
        app
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        // Leaving communication or manually selecting cancels pending dwell. A
        // stationary pointer must move again before it can arm another selection.
        if matches!(
            &message,
            Message::ShowLibrary
                | Message::OpenSettings
                | Message::Editor(_)
                | Message::CloseRequested
                | Message::OpenBoardSet(_)
                | Message::Activate(_)
                | Message::Back
                | Message::Clear
                | Message::Hold
                | Message::Speak
        ) {
            self.clear_access();
        }
        let mut task = Task::none();
        match message {
            Message::Access(event) => return self.update_access(event),
            Message::DwellChanged(value) => {
                self.settings.dwell_to_select_millis = value.into();
                self.save_settings();
            }
            Message::RearmChanged(value) => {
                self.settings.dwell_rearm_delay_millis = value.into();
                self.save_settings();
            }
            Message::SelectKeyChanged(value) => {
                self.settings.select_key_binding = value;
                self.save_settings();
            }
            Message::RestKeyChanged(value) => {
                self.settings.rest_mode_key_binding = value;
                self.save_settings();
            }
            Message::CloseRequested => {
                if self.editor.is_some() {
                    self.close_after_editor = true;
                    return self.update_editor(editor::Event::Discard);
                }
                return iced::exit();
            }
            Message::Keyboard(iced::keyboard::Event::KeyPressed {
                key: iced::keyboard::Key::Named(iced::keyboard::key::Named::Tab),
                modifiers,
                ..
            }) => {
                return if modifiers.shift() {
                    iced::widget::operation::focus_previous()
                } else {
                    iced::widget::operation::focus_next()
                };
            }
            Message::Keyboard(event) => {
                if self.route == Route::Runner {
                    let event = match event {
                        iced::keyboard::Event::KeyPressed { key, .. } => {
                            Some(access::Event::KeyDown(key_token(key)))
                        }
                        iced::keyboard::Event::KeyReleased { key, .. } => {
                            Some(access::Event::KeyUp(key_token(key)))
                        }
                        _ => None,
                    };
                    if let Some(event) = event {
                        return self.update_access(event);
                    }
                }
            }
            Message::Editor(event) => return self.update_editor(event),
            Message::ShowLibrary => {
                self.route = Route::Library;
                self.refresh_library();
            }
            Message::ChooseBoardFile => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("Open Board Format", &["obf", "obz", "json"])
                    .pick_file()
                {
                    self.import(path.to_string_lossy().as_ref());
                }
            }
            Message::ImportFile(path) => self.import(&path),
            Message::OpenBoardSet(id) => task = self.apply(self.core.open(&id)),
            Message::Activate(id) => task = self.apply(self.core.activate(&id)),
            Message::Back => task = self.apply(self.core.back()),
            Message::Clear => task = self.apply(self.core.clear()),
            Message::Hold => task = self.apply(self.core.hold()),
            Message::Speak => task = self.apply(self.core.speak()),
            Message::OpenSettings => {
                self.previous_route = self.route;
                self.route = Route::Settings;
            }
            Message::CloseSettings => self.route = self.previous_route,
            Message::SelectSettingsSection(section) => self.settings_section = section,
            Message::ThemeChanged(value) => {
                self.settings.prefers_dark = value.prefers_dark();
                self.settings.theme = value;
                self.save_settings();
            }
            Message::VoiceChanged(value) => {
                self.settings.voice = value;
                self.save_settings();
            }
            Message::RateChanged(value) => {
                self.settings.speech_rate = value;
                self.save_settings();
            }
            Message::SystemTheme(mode) => {
                self.system_theme = <Theme as iced::theme::Base>::default(mode);
            }
            Message::SpeechFinished(Err(error)) => self.error = Some(error),
            Message::SpeechFinished(Ok(())) => {}
            Message::PronunciationWordChanged(value) => self.pronunciation_word = value,
            Message::PronunciationReplacementChanged(value) => {
                self.pronunciation_replacement = value
            }
            Message::AddPronunciation => {
                let entry = Pronunciation {
                    word: self.pronunciation_word.trim().into(),
                    phoneme: self.pronunciation_replacement.trim().into(),
                    alphabet: "text".into(),
                };
                if !entry.word.is_empty() && !entry.phoneme.is_empty() {
                    match self.core.add_pronunciation(&entry) {
                        Ok(value) => {
                            self.pronunciations = value;
                            self.pronunciation_word.clear();
                            self.pronunciation_replacement.clear();
                        }
                        Err(error) => self.error = Some(error),
                    }
                }
            }
            Message::DeletePronunciation(word) => match self.core.delete_pronunciation(&word) {
                Ok(value) => self.pronunciations = value,
                Err(error) => self.error = Some(error),
            },
            Message::ExportBackup => {
                let result = rfd::FileDialog::new()
                    .set_file_name("wingmate-backup.wingmate-backup")
                    .save_file()
                    .map(|path| self.core.export_backup(path.to_string_lossy().as_ref()));
                if let Some(Err(error)) = result {
                    self.error = Some(error);
                }
            }
            Message::RestoreBackup => {
                if let Some(path) = rfd::FileDialog::new()
                    .add_filter("Wingmate backup", &["wingmate-backup"])
                    .pick_file()
                {
                    match self.core.restore_backup(path.to_string_lossy().as_ref()) {
                        Ok(()) => {
                            // The restored snapshot replaces the Screens, so the
                            // open one is gone. Start again from the library.
                            self.board = None;
                            self.route = Route::Library;
                            self.refresh_library();
                            if let Ok(value) = self.core.settings() {
                                self.settings = value;
                            }
                        }
                        Err(error) => self.error = Some(error),
                    }
                }
            }
            Message::DismissError => self.error = None,
        }
        task
    }

    fn clear_access(&mut self) {
        match self.core.access(
            &access::Event::Clear,
            self.access_clock.elapsed().as_millis() as i64,
        ) {
            Ok(state) => self.access = state,
            Err(error) => {
                self.access = access::State::default();
                self.error = Some(error);
            }
        }
    }

    fn update_access(&mut self, event: access::Event) -> Task<Message> {
        if self.route != Route::Runner && !matches!(event, access::Event::Clear) {
            return Task::none();
        }
        let now = self.access_clock.elapsed().as_millis() as i64;
        match self.core.access(&event, now) {
            Ok(mut state) => {
                let effect = state.effect.take();
                self.access = state;
                match effect {
                    Some(access::Effect::Activate { target_id }) => {
                        if let Ok(target) = serde_json::from_str::<access::Target>(&target_id) {
                            // Reject an old Page's queued target after navigation.
                            let valid = match &target {
                                access::Target::Cell {
                                    board_set,
                                    page,
                                    button,
                                } => self.board.as_ref().is_some_and(|view| {
                                    &view.board_set_id == board_set
                                        && &view.board_id == page
                                        && view.cells.iter().any(|cell| &cell.id == button)
                                }),
                                _ => true,
                            };
                            if valid {
                                let result = match target {
                                    access::Target::Cell { button, .. } => {
                                        self.core.activate(&button)
                                    }
                                    access::Target::Back => self.core.back(),
                                    access::Target::Clear => self.core.clear(),
                                    access::Target::Hold => self.core.hold(),
                                    access::Target::Speak => self.core.speak(),
                                };
                                return self.apply(result);
                            }
                        }
                        self.clear_access();
                    }
                    Some(access::Effect::PauseChanged { is_paused }) => {
                        self.access.is_paused = is_paused;
                    }
                    None => {}
                }
            }
            Err(error) => {
                self.clear_access();
                self.error = Some(error);
            }
        }
        Task::none()
    }

    fn import(&mut self, path: &str) {
        let _ = self.apply(self.core.import_file(path));
        self.refresh_library();
    }

    fn apply(&mut self, result: Result<Activation, String>) -> Task<Message> {
        match result {
            Ok(activation) => {
                let speech = activation.speech.map(|value| {
                    Task::perform(
                        speech::speak(
                            value,
                            self.settings.voice.clone(),
                            self.settings.speech_rate,
                        ),
                        Message::SpeechFinished,
                    )
                });
                if self.board.as_ref().is_none_or(|previous| {
                    previous.board_set_id != activation.view.board_set_id
                        || previous.board_id != activation.view.board_id
                }) {
                    self.clear_access();
                }
                self.board = Some(activation.view);
                self.route = Route::Runner;
                self.error = None;
                speech.unwrap_or_else(Task::none)
            }
            Err(error) => {
                self.error = Some(error);
                Task::none()
            }
        }
    }

    fn save_settings(&mut self) {
        self.clear_access();
        match self.core.update_settings(&self.settings) {
            Ok(settings) => self.settings = settings,
            Err(error) => self.error = Some(error),
        }
    }
    fn refresh_library(&mut self) {
        match self.core.library() {
            Ok(value) => self.library = value,
            Err(error) => self.error = Some(error),
        }
        if let Ok(value) = self.core.recents() {
            self.recents = value;
        }
    }

    fn view(&self) -> Element<'_, Message> {
        let body = match self.route {
            Route::Editor => self
                .editor
                .as_ref()
                .map(editor::Editor::view)
                .unwrap_or_else(|| text("No draft open").into()),
            Route::Library => screens::library(&self.library),
            Route::Runner => self
                .board
                .as_ref()
                .map(|board| screens::runner(board, &self.access))
                .unwrap_or_else(|| text("No Screen open").into()),
            Route::Settings => settings::view(
                self.settings_section,
                &self.settings,
                &self.pronunciations,
                &self.pronunciation_word,
                &self.pronunciation_replacement,
                &self.recents,
            ),
        };
        // The Settings screen carries its own navigation, so the header button
        // that opens it would only be a no-op while that screen is open.
        let open_settings: Element<'_, Message> =
            if self.route == Route::Settings || self.route == Route::Editor {
                iced::widget::Space::new().into()
            } else {
                button("Settings").on_press(Message::OpenSettings).into()
            };
        let mut layout = column![
            row![
                text("Wingmate").size(22),
                iced::widget::Space::new().width(Fill),
                open_settings,
            ]
            .padding(10),
            body,
        ];
        if let Some(error) = &self.error {
            layout = layout.push(
                container(row![
                    text(error).width(Fill),
                    button("Dismiss").on_press(Message::DismissError)
                ])
                .padding(12)
                .width(Fill),
            );
        }
        container(layout).width(Fill).height(Fill).into()
    }

    fn theme(&self) -> Theme {
        self.settings.theme.resolve(&self.system_theme)
    }
}

fn data_directory() -> std::path::PathBuf {
    #[cfg(target_os = "windows")]
    let root = std::env::var_os("APPDATA").map(std::path::PathBuf::from);
    #[cfg(target_os = "macos")]
    let root = std::env::var_os("HOME")
        .map(std::path::PathBuf::from)
        .map(|home| home.join("Library").join("Application Support"));
    #[cfg(target_os = "linux")]
    let root = std::env::var_os("XDG_DATA_HOME")
        .map(std::path::PathBuf::from)
        .or_else(|| {
            std::env::var_os("HOME")
                .map(std::path::PathBuf::from)
                .map(|home| home.join(".local").join("share"))
        });

    #[cfg(target_os = "linux")]
    let app_directory = "wingmate";
    #[cfg(any(target_os = "macos", target_os = "windows"))]
    let app_directory = "Wingmate";

    root.expect("desktop data directory is unavailable")
        .join(app_directory)
}

fn key_token(key: iced::keyboard::Key) -> String {
    match key {
        iced::keyboard::Key::Character(value) => value.to_string(),
        iced::keyboard::Key::Named(value) => format!("{value:?}"),
        _ => String::new(),
    }
}

#[cfg(test)]
mod access_runner_tests {
    use super::*;
    use access::{Event, Target};

    #[test]
    fn select_key_uses_existing_activation_and_leaving_runner_cancels_input() {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        core.editor(&serde_json::json!({"operation":"new", "name":"Test"}))
            .unwrap();
        core.editor(&serde_json::json!({"operation":"button", "label":"Hello"}))
            .unwrap();
        let saved = core
            .editor(&serde_json::json!({"operation":"save"}))
            .unwrap();
        let mut app = App::with_core(Box::new(core));
        app.settings.select_key_binding = "F8".into();
        app.save_settings();
        let _ = app.update(Message::OpenBoardSet(saved["id"].as_str().unwrap().into()));
        let view = app.board.as_ref().unwrap();
        let target = Target::Cell {
            board_set: view.board_set_id.clone(),
            page: view.board_id.clone(),
            button: view.cells[0].id.clone(),
        };
        let _ = app.update(Message::Access(Event::Enter(target.clone())));
        let _ = app.update(Message::Access(Event::KeyDown("F8".into())));
        assert_eq!(app.board.as_ref().unwrap().message, "Hello");
        let _ = app.update(Message::Access(Event::KeyDown("F8".into())));
        assert_eq!(app.board.as_ref().unwrap().message, "Hello");
        let _ = app.update(Message::OpenSettings);
        assert!(app.access.current_target_id.is_none());
        let _ = app.update(Message::Access(Event::Enter(target)));
        assert!(app.access.current_target_id.is_none());
        let _ = app.update(Message::CloseSettings);
        let _ = app.update(Message::Access(Event::Tick));
        assert_eq!(app.board.as_ref().unwrap().message, "Hello");
    }
}
