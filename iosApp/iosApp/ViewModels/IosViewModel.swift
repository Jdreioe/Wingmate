import Foundation
import Combine
import Shared
import AVFoundation
import Network

@MainActor
final class IosViewModel: ObservableObject {
    private var childModelCancellables: Set<AnyCancellable> = []

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
                screenId: self.selectedBoardSetId ?? "",
                pageId: self.selectedBoardId,
                spellingMode: self.selectedBoardUsesSpellingMode,
                currentCells: self.boardCells.map { ($0.buttonId, $0.label, $0.vocalization, $0.imageUrl) },
                cacheWholeSentences: { [weak self] id in
                    self?.boardSets.first(where: { $0.id == id })?.cacheWholeSentences ?? true
                }
            )
        }
    )

    // Remaining Screens facades (migrating to ScreensModel next)
    lazy var boardsFacade = IosDiBridge().boardsFacade()
    lazy var predictionFacade = IosDiBridge().predictionFacade()

    init() {
        // Forward child observable changes while transitional screens still observe IosViewModel.
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
    }

    var boardPredictionSubscription: Shared.NativeSubscription?

    deinit {
        boardPredictionSubscription?.cancel()
    }

    // Symbol-first boardset mode
    @Published var boardModeEnabled: Bool = false
    @Published var isCreatingBoardSet: Bool = false
    @Published var boardSets: [BoardSetInfo] = []
    @Published var selectedBoardSetId: String? = nil
    @Published var selectedBoardId: String? = nil
    @Published var selectedBoard: Shared.ObfBoard? = nil
    @Published var boardCells: [BoardCellInfo] = []
    @Published var boardFieldItems: [BoardFieldItem] = []
    @Published var selectedBoardKeyboardLayout: String? = nil
    @Published var selectedBoardUsesSpellingMode: Bool = false
    @Published var boardPredictionsByButtonId: [String: String] = [:]
    @Published var boardNamesById: [String: String] = [:]
    @Published var boardStatusMessage: String? = nil
    @Published var boardBackgroundImageUrl: String? = nil
    @Published var boardBackgroundImageFit = "Fill"
    @Published var resolvedBoardShowLabels: Bool? = nil
    @Published var resolvedBoardShowSymbols: Bool? = nil
    @Published var resolvedBoardLabelAtTop: Bool? = nil
    @Published var resolvedBoardShowMessageBar: Bool? = nil
    @Published var resolvedBoardShowSpeakButton: Bool? = nil
    @Published var resolvedBoardMessageBarActions: [String]? = nil
    @Published var resolvedBoardSpeechPolicy: String? = nil
    @Published var resolvedBoardActivationBehavior: String? = nil
    @Published var resolvedBoardReturnBehavior: String? = nil
    @Published var boardStack: [String] = []

    var boardShowLabels: Bool { resolvedBoardShowLabels ?? settings.showButtonLabels }
    var boardShowSymbols: Bool { resolvedBoardShowSymbols ?? settings.showButtonSymbols }
    var boardLabelAtTop: Bool { resolvedBoardLabelAtTop ?? settings.labelAtTop }
    var boardMessageBarVisible: Bool { resolvedBoardShowMessageBar ?? settings.boardShowMessageBar }
    var boardSpeakButtonVisible: Bool { resolvedBoardShowSpeakButton ?? settings.boardShowSpeakButton }
    /// The Screen's message bar buttons in order (`Speak`, `Hold`, `DeleteLast`, `Clear`).
    var boardMessageBarActions: [String] {
        resolvedBoardMessageBarActions ?? (settings.boardShowSpeakButton ? ["Speak"] : [])
    }
    var boardActivationBehavior: String { resolvedBoardActivationBehavior ?? "SpeakAndAdd" }
    var boardReturnBehavior: String { resolvedBoardReturnBehavior ?? "Stay" }
    var shouldSpeakSelectionImmediately: Bool {
        settings.shouldSpeakSelectionImmediately(
            resolvedBoardSpeechPolicy: resolvedBoardSpeechPolicy,
            activationBehavior: boardActivationBehavior
        )
    }

    var selectedBoardSet: BoardSetInfo? {
        guard let id = selectedBoardSetId else { return nil }
        return boardSets.first(where: { $0.id == id })
    }

    var selectedBoardSetLocked: Bool {
        selectedBoardSet?.isLocked ?? false
    }

    var canEditSelectedBoardSet: Bool {
        !selectedBoardSetLocked
    }

    /// Starts the shared Koin graph. Later calls do nothing. The app calls this at launch,
    /// before any view exists, because views such as the welcome flow resolve facades in
    /// their first `.task`, which can run before `ContentView.onAppear` calls `start()`.
    static func startDependencies() {
        IosDiBridge().start(
            deviceSpeech: SystemTtsManager.shared,
            openSymbolsProxyUrl: resolveOpenSymbolsProxyUrl(),
            aptabaseAppKey: Bundle.main.object(forInfoDictionaryKey: "APTABASE_APP_KEY") as? String
        )
    }

    func start() async {
        Self.startDependencies()
        communication.startObserving()

        // Load selected voice and languages for welcome gating and UI
        speech.refreshVoiceAndLanguages()
        await speech.refreshLanguagePreferences()
        await refreshSettings()
        // The startup workspace applies at launch only. Settings also refresh
        // these values, and must not switch the workspace the user is in.
        boardModeEnabled = settings.startupUsesScreens

        // Determine if cloud TTS providers are configured
        await speech.refreshAllProviderConfigurations()

        // Start connectivity monitoring
        ConnectivityMonitor.shared.onChange { [weak self] online in
            guard let self = self else { return }
            self.boardsFacade.updateBoardSetSpeechCacheOnline(online: online)
            if online {
                Task {
                    try? await self.boardsFacade.cacheAllBoardSetFields()
                    try? await self.boardsFacade.retryBoardSetSpeechCaching()
                }
            }
        }
        await communication.loadTypingTray()
        // Load boardsets and selected board for symbol mode
        await loadBoardSets()
        _ = try? await boardsFacade.cacheAllBoardSetFields()
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
        await loadBoardSets()
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
