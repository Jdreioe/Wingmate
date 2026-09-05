mod bridge;
mod editor;
mod editor_update;
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
    iced::application(App::boot, App::update, App::view)
        .title("Wingmate")
        .theme(App::theme)
        .subscription(|_| {
            iced::Subscription::batch([
                iced::system::theme_changes().map(Message::SystemTheme),
                iced::keyboard::listen().map(Message::Keyboard),
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
        let mut app = Self {
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
        (app, iced::system::theme().map(Message::SystemTheme))
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        let mut task = Task::none();
        match message {
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
            Message::Keyboard(_) => {}
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
        if let Err(error) = self.core.update_settings(&self.settings) {
            self.error = Some(error);
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
                .map(screens::runner)
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
