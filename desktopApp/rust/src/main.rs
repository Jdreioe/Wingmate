mod access;
mod bridge;
mod editor;
mod editor_update;
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
                if cfg!(target_os = "linux") {
                    iced::time::every(std::time::Duration::from_secs(1))
                        .map(|_| Message::GazeSetupPoll)
                } else {
                    iced::Subscription::none()
                },
                app.gaze_setup
                    .diagnostics
                    .as_ref()
                    .map(|source| source.subscription().map(|_| Message::GazeSetupPoll))
                    .unwrap_or_else(iced::Subscription::none),
                iced::keyboard::listen().map(Message::Keyboard),
                if app.route == Route::Runner
                    && !app.gaze_owns_input()
                    && !app.access.is_paused
                    && app.settings.dwell_to_select_millis > 0
                    && app.access.current_target_id.is_some()
                {
                    iced::time::every(std::time::Duration::from_millis(16))
                        .map(|_| Message::Access(access::Event::Tick))
                } else {
                    iced::Subscription::none()
                },
                if app.gaze.enabled() && !app.gaze_starting {
                    iced::time::every(std::time::Duration::from_millis(16))
                        .map(|_| Message::GazePoll)
                } else {
                    iced::Subscription::none()
                },
                app.gaze
                    .source
                    .as_ref()
                    .filter(|_| !app.gaze_starting)
                    .map(|source| source.subscription().map(|_| Message::GazePoll))
                    .unwrap_or_else(iced::Subscription::none),
                iced::event::listen_with(|event, _, _| match event {
                    iced::Event::Window(iced::window::Event::Unfocused) => {
                        Some(Message::WindowUnfocused)
                    }
                    iced::Event::Window(iced::window::Event::Resized(_)) => {
                        Some(Message::GazeLayoutChanged)
                    }
                    iced::Event::Mouse(iced::mouse::Event::CursorLeft) => {
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
    gaze: gaze::runner::Session,
    gaze_starting: bool,
    gaze_setup: gaze::setup::Setup,
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
    ToggleGaze,
    WebcamEnabled(bool),
    WebcamCamera(gaze::webcam::Camera),
    WebcamContinue,
    WebcamRetry,
    GazeSetupPoll,
    GazeAutostart(bool),
    GazeDiagnostics(bool),
    GazeRetry,
    GazePoll,
    GazeLayoutChanged,
    WindowUnfocused,
    GazeWindow(u64, iced::window::Mode, iced::Size),
    GazeHit(u64, u64, std::time::Instant, Option<access::Target>),
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
        let mut app = Self::with_core(core);
        app.gaze_setup.load();
        (app, iced::system::theme().map(Message::SystemTheme))
    }

    fn with_core(core: Box<dyn Core>) -> Self {
        let mut app = Self {
            gaze: Default::default(),
            gaze_starting: false,
            gaze_setup: Default::default(),
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
        if matches!(
            &message,
            Message::Keyboard(iced::keyboard::Event::KeyPressed {
                key: iced::keyboard::Key::Named(iced::keyboard::key::Named::Escape),
                ..
            })
        ) && self.gaze.enabled()
        {
            return self.update(Message::ToggleGaze);
        }
        if matches!(
            &message,
            Message::Keyboard(iced::keyboard::Event::KeyPressed {
                key: iced::keyboard::Key::Named(iced::keyboard::key::Named::Enter),
                ..
            })
        ) && self.gaze.source.as_ref().is_some_and(|source| {
            matches!(
                source.snapshot().progress,
                Some(gaze::webcam::Progress::Validated)
            )
        }) {
            return self.update(Message::WebcamContinue);
        }
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
        if matches!(
            &message,
            Message::ShowLibrary
                | Message::OpenSettings
                | Message::Editor(_)
                | Message::CloseRequested
                | Message::WindowUnfocused
        ) {
            self.gaze.stop();
            self.gaze_starting = false;
            self.clear_access();
        }
        if matches!(
            &message,
            Message::CloseSettings
                | Message::WindowUnfocused
                | Message::CloseRequested
                | Message::ShowLibrary
                | Message::Editor(_)
        ) {
            self.gaze_setup.diagnostics = None;
        }
        let mut task = Task::none();
        match message {
            Message::WebcamRetry => {
                if !self.can_retry_webcam() {
                    return Task::none();
                }
                self.gaze.stop();
                return self.update(Message::ToggleGaze);
            }
            Message::WebcamEnabled(enabled) => {
                self.gaze_setup.use_webcam = enabled;
                self.gaze_setup.diagnostics = None;
            }
            Message::WebcamCamera(camera) => self.gaze_setup.camera = Some(camera),
            Message::WebcamContinue => {
                if let Some(source) = &self.gaze.source
                    && matches!(
                        source.snapshot().progress,
                        Some(gaze::webcam::Progress::Validated)
                    )
                    && let Some(control) = &source.webcam
                {
                    control
                        .presented
                        .store(14, std::sync::atomic::Ordering::Release);
                }
            }
            Message::GazeSetupPoll => self.gaze_setup.refresh(),
            Message::GazeAutostart(enabled) => self.gaze_setup.set_autostart(enabled),
            Message::GazeRetry => self.gaze_setup.start(),
            Message::GazeDiagnostics(enabled) => {
                self.gaze_setup.diagnostics = enabled.then(gaze::runner::Source::new);
            }
            Message::WindowUnfocused => {}
            Message::GazeLayoutChanged => {
                if self.gaze.enabled() && !self.gaze_starting {
                    self.clear_access();
                }
            }
            Message::ToggleGaze => {
                self.clear_access();
                if self.gaze.enabled() {
                    self.gaze.stop();
                    self.gaze_starting = false;
                    return iced::window::oldest().then(|id| {
                        id.map(|id| iced::window::set_mode(id, iced::window::Mode::Windowed))
                            .unwrap_or_else(Task::none)
                    });
                }
                if self.route == Route::Settings && self.previous_route == Route::Runner {
                    self.gaze_setup.diagnostics = None;
                    self.route = Route::Runner;
                }
                if !cfg!(target_os = "linux") || self.route != Route::Runner {
                    return Task::none();
                }
                if self.settings.dwell_to_select_millis == 0 {
                    self.error = Some(
                        "Set a dwell duration in Settings > Access before starting gaze.".into(),
                    );
                    return Task::none();
                }
                if self.gaze_setup.autostart && !self.gaze_setup.use_webcam {
                    self.gaze_setup.start();
                }
                if self.gaze_setup.use_webcam && self.gaze_setup.camera.is_none() {
                    self.error = Some("Choose a webcam in Settings > Access first.".into());
                    return Task::none();
                }
                self.gaze.start();
                if self.gaze_setup.use_webcam {
                    self.gaze.source = self
                        .gaze_setup
                        .camera
                        .clone()
                        .map(gaze::runner::Source::camera);
                }
                self.gaze_starting = true;
                let epoch = self.gaze.epoch;
                return iced::window::oldest().then(move |id| match id {
                    Some(id) => {
                        iced::window::set_mode::<Message>(id, iced::window::Mode::Fullscreen)
                            .chain(query_gaze_window(id, epoch))
                    }
                    None => Task::none(),
                });
            }
            Message::GazePoll => {
                if !self.gaze.enabled() || self.gaze_starting {
                    return Task::none();
                }
                let epoch = self.gaze.epoch;
                return iced::window::oldest().then(move |id| {
                    id.map(|id| query_gaze_window(id, epoch))
                        .unwrap_or_else(Task::none)
                });
            }
            Message::GazeWindow(epoch, mode, size) => {
                if !self.gaze.enabled() || epoch != self.gaze.epoch || self.route != Route::Runner {
                    return Task::none();
                }
                self.gaze_starting = false;
                if mode != iced::window::Mode::Fullscreen {
                    self.gaze.stop();
                    self.clear_access();
                    self.error =
                        Some("Native gaze stopped because Wingmate is not fullscreen.".into());
                    return Task::none();
                }
                if self
                    .gaze
                    .source
                    .as_ref()
                    .is_some_and(|source| source.webcam.is_some())
                {
                    if self
                        .gaze
                        .camera_size
                        .is_some_and(|previous| previous != size)
                    {
                        self.gaze.stop();
                        self.clear_access();
                        self.error = Some(
                            "Display size changed. Start camera gaze again to recalibrate.".into(),
                        );
                        return Task::none();
                    }
                    self.gaze.camera_size = Some(size);
                }
                let snapshot = self.gaze.source.as_ref().unwrap().snapshot();
                let point = snapshot.point(std::time::Instant::now());
                let status = if snapshot.status == gaze::Status::Connected && point.is_none() {
                    gaze::Status::GazeLost
                } else {
                    snapshot.status
                };
                let will_own_input = !matches!(
                    status,
                    gaze::Status::DaemonUnavailable
                        | gaze::Status::IncompatibleProtocol
                        | gaze::Status::CameraUnavailable
                        | gaze::Status::WebcamRuntimeMissing
                        | gaze::Status::CalibrationFailed(_)
                );
                if (self.gaze_owns_input() || will_own_input)
                    && (self.gaze.status != status || self.gaze.losses != snapshot.losses)
                {
                    self.clear_access();
                }
                self.gaze.losses = snapshot.losses;
                self.gaze.status = status;
                if self.access.is_paused || point.is_none() {
                    return Task::none();
                }
                if let Some(point) = point.and_then(|point| gaze::targets::map(point, size))
                    && let Some(board) = &self.board
                {
                    let epoch = self.gaze.epoch;
                    return iced::advanced::widget::operate(gaze::targets::HitTest::new(
                        point, board,
                    ))
                    .map(move |target| {
                        Message::GazeHit(epoch, snapshot.losses, snapshot.received, target)
                    });
                }
                self.clear_access();
            }
            Message::GazeHit(epoch, losses, received, target) => {
                if epoch != self.gaze.epoch
                    || !self.gaze.enabled()
                    || self.route != Route::Runner
                    || self.access.is_paused
                {
                    return Task::none();
                }
                let latest = self.gaze.source.as_ref().unwrap().snapshot();
                if losses != latest.losses
                    || latest.point(std::time::Instant::now()).is_none()
                    || received.elapsed() > gaze::runner::STALE_AFTER
                {
                    self.clear_access();
                    return Task::none();
                }
                // A newer sample needs its own hit-test. Do not advance dwell
                // using a point superseded while the widget operation ran.
                if latest.received != received {
                    return Task::none();
                }
                let Some(target) = target else {
                    self.clear_access();
                    return Task::none();
                };
                if self.access.current_target_id.as_deref() != Some(target.id().as_str()) {
                    let _ = self.update_access(access::Event::Enter(target));
                }
                return self.update_access(access::Event::Tick);
            }
            Message::Access(event) => {
                if self.gaze_owns_input()
                    && matches!(
                        event,
                        access::Event::Enter(_)
                            | access::Event::Exit(_)
                            | access::Event::Clear
                            | access::Event::Tick
                    )
                {
                    return Task::none();
                }
                return self.update_access(event);
            }
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
                self.gaze_setup.stop();
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
            Message::SelectSettingsSection(section) => {
                self.gaze_setup.diagnostics = None;
                self.settings_section = section;
            }
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

    fn gaze_owns_input(&self) -> bool {
        self.gaze.enabled()
            && !matches!(
                self.gaze.status,
                gaze::Status::DaemonUnavailable
                    | gaze::Status::IncompatibleProtocol
                    | gaze::Status::CameraUnavailable
                    | gaze::Status::WebcamRuntimeMissing
                    | gaze::Status::CalibrationFailed(_)
            )
    }

    fn clear_access(&mut self) {
        self.gaze.epoch = self.gaze.epoch.wrapping_add(1);
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
        if self.gaze_owns_input()
            && matches!(event, access::Event::KeyDown(_))
            && self.gaze.source.as_ref().is_some_and(|source| {
                let snapshot = source.snapshot();
                snapshot.losses != self.gaze.losses
                    || snapshot.point(std::time::Instant::now()).is_none()
            })
        {
            self.clear_access();
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
                        self.gaze.epoch = self.gaze.epoch.wrapping_add(1);
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

    fn can_retry_webcam(&self) -> bool {
        self.route == Route::Runner
            && self
                .gaze
                .source
                .as_ref()
                .is_some_and(|source| source.webcam.is_some())
            && matches!(
                self.gaze.status,
                gaze::Status::CameraUnavailable
                    | gaze::Status::WebcamRuntimeMissing
                    | gaze::Status::CalibrationFailed(_)
            )
    }

    fn view(&self) -> Element<'_, Message> {
        if let Some(source) = &self.gaze.source
            && let Some(calibration) = gaze::webcam::calibration_view(source)
        {
            return calibration;
        }
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
                (&self.gaze_setup, self.previous_route == Route::Runner),
            ),
        };
        let gaze_control: Element<'_, Message> =
            if cfg!(target_os = "linux") && self.route == Route::Runner {
                button(if self.gaze.enabled() {
                    "Stop gaze"
                } else {
                    "Start gaze (fullscreen)"
                })
                .height(48)
                .on_press(Message::ToggleGaze)
                .into()
            } else {
                iced::widget::Space::new().into()
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
                gaze_control,
                open_settings,
            ]
            .padding(10),
            body,
        ];
        if self.gaze.enabled() {
            layout = layout.push(text(self.gaze.label()));
            if self.can_retry_webcam() {
                layout = layout.push(
                    button("Retry camera calibration")
                        .height(48)
                        .on_press(Message::WebcamRetry),
                );
            }
        }
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

fn query_gaze_window(id: iced::window::Id, epoch: u64) -> Task<Message> {
    iced::window::mode(id).then(move |mode| {
        iced::window::size(id).map(move |size| Message::GazeWindow(epoch, mode, size))
    })
}

#[cfg(test)]
mod native_gaze_tests {
    use super::*;
    use gaze::protocol::{Point, Sample};
    use std::time::{Duration, Instant};

    fn fixture() -> (tempfile::TempDir, App, access::Target) {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        core.editor(&serde_json::json!({"operation":"new", "name":"Gaze test"}))
            .unwrap();
        core.editor(&serde_json::json!({"operation":"button", "label":"Hello"}))
            .unwrap();
        let saved = core
            .editor(&serde_json::json!({"operation":"save"}))
            .unwrap();
        let mut app = App::with_core(Box::new(core));
        app.settings.dwell_to_select_millis = 100;
        app.settings.dwell_rearm_delay_millis = 0;
        app.save_settings();
        let _ = app.update(Message::OpenBoardSet(saved["id"].as_str().unwrap().into()));
        let board = app.board.as_ref().unwrap();
        let target = access::Target::Cell {
            board_set: board.board_set_id.clone(),
            page: board.board_id.clone(),
            button: board.cells[0].id.clone(),
        };
        // Simulate slow setup so dwell tests cannot reset time behind target entry.
        app.access_clock -= Duration::from_millis(250);
        app.gaze.start();
        app.gaze.status = gaze::Status::Connected;
        (directory, app, target)
    }
    #[test]
    fn webcam_retry_discards_old_selection_and_restarts_calibration() {
        let (_directory, mut app, target) = fixture();
        let camera = gaze::webcam::Camera {
            path: "/dev/unused".into(),
            name: "Test".into(),
        };
        app.gaze_setup.use_webcam = true;
        app.gaze_setup.camera = Some(camera.clone());
        app.gaze.source = Some(gaze::runner::Source::camera(camera.clone()));
        let old_epoch = app.gaze.epoch;
        feed(&app, 1, true);
        hit(&mut app, target.clone());
        let received = app.gaze.source.as_ref().unwrap().snapshot().received;
        app.gaze.status = gaze::Status::CalibrationFailed(gaze::webcam::Failure::Accuracy);
        let _ = app.update(Message::WebcamRetry);
        assert!(app.gaze.epoch > old_epoch);
        assert!(app.gaze_starting);
        assert!(app.access.current_target_id.is_none());
        let source = app.gaze.source.as_ref().unwrap();
        assert_eq!(source.webcam.as_ref().unwrap().camera, camera);
        assert_eq!(source.snapshot().status, gaze::Status::Connecting);
        assert!(source.snapshot().sample.is_none());
        let _ = app.update(Message::GazeHit(old_epoch, 0, received, Some(target)));
        assert!(app.access.current_target_id.is_none());
        assert_eq!(app.board.as_ref().unwrap().message, "");
        let retry_epoch = app.gaze.epoch;
        let _ = app.update(Message::WebcamRetry);
        assert_eq!(
            app.gaze.epoch, retry_epoch,
            "duplicate retries must not restart setup"
        );
    }

    #[test]
    fn webcam_failure_focus_and_display_changes_cancel_selection() {
        for failure in [
            gaze::Status::CameraUnavailable,
            gaze::Status::CalibrationFailed(gaze::webcam::Failure::Accuracy),
            gaze::Status::WebcamRuntimeMissing,
        ] {
            let (_directory, mut app, target) = fixture();
            app.gaze.source = Some(gaze::runner::Source::camera(gaze::webcam::Camera {
                path: "/dev/unused".into(),
                name: "Test".into(),
            }));
            feed(&app, 1, true);
            hit(&mut app, target.clone());
            app.gaze.source.as_ref().unwrap().unavailable(failure);
            let _ = app.update(Message::GazeWindow(
                app.gaze.epoch,
                iced::window::Mode::Fullscreen,
                iced::Size::new(1100.0, 760.0),
            ));
            assert!(app.access.current_target_id.is_none());
            assert!(!app.gaze_owns_input());
            assert_eq!(app.board.as_ref().unwrap().message, "");
            let _ = app.update(Message::Access(access::Event::Enter(target)));
            assert!(app.access.current_target_id.is_some());
            let _ = app.update(Message::WindowUnfocused);
            assert!(app.gaze.source.is_none());
        }
        let (_directory, mut app, _) = fixture();
        app.gaze.source = Some(gaze::runner::Source::camera(gaze::webcam::Camera {
            path: "/dev/unused".into(),
            name: "Test".into(),
        }));
        app.gaze.camera_size = Some(iced::Size::new(1100.0, 760.0));
        let _ = app.update(Message::GazeWindow(
            app.gaze.epoch,
            iced::window::Mode::Fullscreen,
            iced::Size::new(1920.0, 1080.0),
        ));
        assert!(app.gaze.source.is_none());
        assert!(app.error.as_ref().unwrap().contains("recalibrate"));
    }

    #[test]
    fn diagnostics_cannot_select_and_close_on_focus_or_settings_exit() {
        let (_directory, mut app, _) = fixture();
        let _ = app.update(Message::OpenSettings);
        assert!(!app.gaze.enabled());
        let _ = app.update(Message::GazeDiagnostics(true));
        assert!(app.gaze_setup.diagnostics.is_some());
        assert!(!app.gaze.enabled());
        let _ = app.update(Message::WindowUnfocused);
        assert!(app.gaze_setup.diagnostics.is_none());
        let _ = app.update(Message::GazeDiagnostics(true));
        let _ = app.update(Message::CloseSettings);
        assert!(app.gaze_setup.diagnostics.is_none());
        assert!(!app.gaze.enabled());
    }
    fn feed(app: &App, frame: u32, valid: bool) {
        app.gaze.source.as_ref().unwrap().sample(
            Sample {
                frame_counter: frame,
                timestamp_us: frame as i64 * 1000,
                point: valid.then_some(Point { x: 0.25, y: 0.25 }),
            },
            Instant::now(),
        );
    }
    fn hit(app: &mut App, target: access::Target) {
        let snapshot = app.gaze.source.as_ref().unwrap().snapshot();
        let _ = app.update(Message::GazeHit(
            app.gaze.epoch,
            snapshot.losses,
            snapshot.received,
            Some(target),
        ));
    }

    #[test]
    fn gaze_uses_shared_activation_once_and_pointer_hover_cannot_steal_target() {
        let (_directory, mut app, target) = fixture();
        feed(&app, 1, true);
        hit(&mut app, target.clone());
        let _ = app.update(Message::Access(access::Event::Enter(access::Target::Clear)));
        assert_eq!(app.access.current_target_id, Some(target.id()));
        app.access_clock -= Duration::from_millis(200);
        feed(&app, 2, true);
        hit(&mut app, target.clone());
        assert_eq!(app.board.as_ref().unwrap().message, "Hello");
        feed(&app, 3, true);
        hit(&mut app, target);
        assert_eq!(app.board.as_ref().unwrap().message, "Hello");
    }

    #[test]
    fn failed_reconnects_do_not_interrupt_pointer_dwell() {
        let (_directory, mut app, target) = fixture();
        app.gaze
            .source
            .as_ref()
            .unwrap()
            .unavailable(gaze::Status::DaemonUnavailable);
        let _ = app.update(Message::GazeWindow(
            app.gaze.epoch,
            iced::window::Mode::Fullscreen,
            iced::Size::new(1100.0, 760.0),
        ));
        let _ = app.update(Message::Access(access::Event::Enter(target.clone())));
        app.gaze
            .source
            .as_ref()
            .unwrap()
            .unavailable(gaze::Status::DaemonUnavailable);
        let _ = app.update(Message::GazeWindow(
            app.gaze.epoch,
            iced::window::Mode::Fullscreen,
            iced::Size::new(1100.0, 760.0),
        ));
        assert_eq!(app.access.current_target_id, Some(target.id()));
        app.access_clock -= Duration::from_millis(200);
        let _ = app.update(Message::Access(access::Event::Tick));
        assert_eq!(app.board.as_ref().unwrap().message, "Hello");
    }

    #[test]
    fn brief_loss_stale_hits_pause_and_window_exit_cannot_finish_dwell() {
        let (_directory, mut app, target) = fixture();
        feed(&app, 1, true);
        hit(&mut app, target.clone());
        let old = app.gaze.source.as_ref().unwrap().snapshot();
        app.access_clock -= Duration::from_millis(200);
        feed(&app, 2, false);
        feed(&app, 3, true);
        let _ = app.update(Message::GazeHit(
            app.gaze.epoch,
            old.losses,
            old.received,
            Some(target.clone()),
        ));
        assert!(app.access.current_target_id.is_none());
        hit(&mut app, target.clone());
        assert!(app.board.as_ref().unwrap().message.is_empty());
        let _ = app.update(Message::Access(access::Event::SetPaused(true)));
        app.access_clock -= Duration::from_millis(400);
        feed(&app, 4, true);
        hit(&mut app, target.clone());
        assert!(app.board.as_ref().unwrap().message.is_empty());
        let _ = app.update(Message::Access(access::Event::SetPaused(false)));
        hit(&mut app, target.clone());
        assert!(app.board.as_ref().unwrap().message.is_empty());
        let snapshot = app.gaze.source.as_ref().unwrap().snapshot();
        let _ = app.update(Message::GazeHit(
            app.gaze.epoch,
            snapshot.losses,
            Instant::now() - Duration::from_secs(1),
            Some(target),
        ));
        assert!(app.access.current_target_id.is_none());
        let _ = app.update(Message::GazeWindow(
            app.gaze.epoch,
            iced::window::Mode::Windowed,
            iced::Size::new(1100.0, 760.0),
        ));
        assert!(!app.gaze.enabled());
        assert!(app.board.as_ref().unwrap().message.is_empty());
    }
}
