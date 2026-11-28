import Foundation
import Combine
import Shared

/// Root composition container for the iOS app (#187).
/// Owns only app-wide workspace navigation (`boardModeEnabled`) and composes
/// feature-scoped observable models. Feature state, task lifetimes, and error
/// handling live inside each feature model.
@MainActor
final class IosViewModel: ObservableObject {
    private var childModelCancellables: Set<AnyCancellable> = []

    /// App-wide workspace switch (`false` = Typing workspace, `true` = Screens workspace).
    @Published var boardModeEnabled: Bool = false

    // Feature-scoped observable models (#187)
    let pronunciationDictionary = PronunciationDictionaryModel()
    let editingAccess = EditingAccessModel()
    let backup = BackupModel()
    let speech = SpeechSettingsModel()
    let settings = AppSettingsModel()

    private(set) lazy var communication: CommunicationModel = CommunicationModel(
        settings: settings,
        speech: speech,
        isInScreensMode: { [weak self] in self?.boardModeEnabled ?? false },
        activeScreenContext: { [weak self] in
            guard let self else { return ("", nil, false, [], { _ in true }) }
            return (
                screenId: self.screens.selectedBoardSetId ?? "",
                pageId: self.screens.selectedBoardId,
                spellingMode: self.screens.selectedBoardUsesSpellingMode,
                currentCells: self.screens.boardCells.map { ($0.buttonId, $0.label, $0.vocalization, $0.imageUrl) },
                cacheWholeSentences: { [weak self] id in
                    self?.screens.boardSets.first(where: { $0.id == id })?.cacheWholeSentences ?? true
                }
            )
        }
    )

    private(set) lazy var screens: ScreensModel = ScreensModel(
        settings: settings,
        editingAccess: editingAccess,
        onSpeak: { [weak self] text in
            self?.communication.speak(text)
        },
        onRememberButtonLooks: { [weak self] pageId, cells in
            self?.communication.rememberScreenButtonLooks(pageId: pageId, cells: cells)
        }
    )

    init() {
        // Forward child observable changes for top-level views that observe IosViewModel.
        speech.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &childModelCancellables)
        editingAccess.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &childModelCancellables)
        settings.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &childModelCancellables)
        communication.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &childModelCancellables)
        screens.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &childModelCancellables)
    }

    /// Starts the shared Koin graph. Later calls do nothing. The app calls this at launch,
    /// before any view exists, because views such as the welcome flow resolve facades in
    /// their first `.task`, which can run before `ContentView.onAppear` calls `start()`.
    static func startDependencies() {
        IosDiBridge().start(
            deviceSpeech: SystemTtsManager.shared,
            audioRouter: IosAudioSessionRouter.shared,
            openSymbolsProxyUrl: resolveOpenSymbolsProxyUrl(),
            aptabaseAppKey: Bundle.main.object(forInfoDictionaryKey: "APTABASE_APP_KEY") as? String
        )
        CloudDriveWebAuthentication.register()
    }

    func start() async {
        Self.startDependencies()
        communication.startObserving()

        // Load selected voice and languages for welcome gating and UI.
        speech.refreshVoiceAndLanguages()
        await speech.refreshLanguagePreferences()
        await refreshSettings()
        // The startup workspace applies at launch only. Settings also refresh
        // these values, and must not switch the workspace the user is in.
        boardModeEnabled = settings.startupUsesScreens

        // Determine if cloud TTS providers are configured.
        await speech.refreshAllProviderConfigurations()

        screens.startSpeechCacheConnectivityMonitoring()
        await communication.loadTypingTray()
        await screens.loadBoardSets()
        await screens.warmSpeechCache()
    }

    func refreshSettings() async {
        _ = await settings.refresh(fallbackUseSystemTts: speech.useSystemTts) { [weak self] useSystemTts, ttsEngine in
            self?.speech.applyEngineFromSettings(useSystemTts: useSystemTts, ttsEngine: ttsEngine)
        }
        await communication.loadHistory()
    }

    func reloadAfterBackupRestore() async throws {
        try await communication.reloadAfterBackupRestore()
        await refreshSettings()
        await speech.refreshAllProviderConfigurations()
        _ = await speech.refreshVoiceAndLanguages().value
        await screens.loadBoardSets()
    }
}

/// The OpenSymbols proxy from Info.plist, or the environment in development builds.
private func resolveOpenSymbolsProxyUrl() -> String? {
    let fromInfoRaw = Bundle.main.object(forInfoDictionaryKey: "OPEN_SYMBOLS_PROXY_URL") as? String
    let fromInfo = fromInfoRaw?.trimmingCharacters(in: .whitespacesAndNewlines)
    if let fromInfo, !fromInfo.isEmpty { return fromInfo }

    let fromEnvRaw = ProcessInfo.processInfo.environment["OPENSYMBOLS_PROXY_URL"]
    let fromEnv = fromEnvRaw?.trimmingCharacters(in: .whitespacesAndNewlines)
    if let fromEnv, !fromEnv.isEmpty { return fromEnv }

    return nil
}
