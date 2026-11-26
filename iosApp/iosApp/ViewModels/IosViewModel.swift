import Foundation
import Combine
import Shared
import AVFoundation
import Network

@MainActor
final class IosViewModel: ObservableObject {
    var store: Shared.PhraseListStore?
    private var phraseSubscription: Shared.NativeSubscription?
    private var childModelCancellables: Set<AnyCancellable> = []

    @Published var state: Shared.PhraseListStoreState = Shared.PhraseListStoreState(phrases: [], categories: [], selectedCategoryId: nil, isLoading: true, error: nil)

    // Feature-scoped observable models (#187)
    let pronunciationDictionary = PronunciationDictionaryModel()
    let editingAccess = EditingAccessModel()
    let backup = BackupModel()
    let speech = SpeechSettingsModel()
    let settings = AppSettingsModel()

    // Shared feature facades, resolved from the iOS composition root after start()
    lazy var boardsFacade = IosDiBridge().boardsFacade()
    lazy var communicationFacade = IosDiBridge().communicationFacade()
    lazy var session = IosDiBridge().communicationSessionFacade()
    lazy var typingScreens = IosDiBridge().typingScreenFacade()
    lazy var predictionFacade = IosDiBridge().predictionFacade()
    lazy var symbolSearchFacade = IosDiBridge().symbolSearchFacade()

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
    }
    var sessionSubscription: Shared.NativeSubscription?

    // The shared Message (#306). Kotlin's Communication session owns it; these mirror its
    // state, and every edit goes through the session so Typing and Screens see one Message.
    @Published var input: String = ""
    var inputSelectionRange: NSRange = NSRange(location: 0, length: 0)
    @Published var messageParts: [Shared.MessagePart] = []
    @Published var heldMessageText: String? = nil
    @Published var playback: Shared.SessionPlayback = .idle
    @Published var sessionNotice: Shared.SessionNotice? = nil
    @Published var sessionIsSaving: Bool = false
    /// False until the saved Message has loaded; edits wait so they cannot replace it.
    @Published var messageLoaded: Bool = false
    var hasHeldThought: Bool { heldMessageText != nil }
    /// How a Screen Button looks in the message bar, by "pageId|buttonId". The Message
    /// keeps what a Button says; its label and symbol come from the Page it was on.
    @Published var screenButtonLooks: [String: (label: String, imageUrl: String?)] = [:]
    /// The Typing Screen template that lays out the Typing tray; nil until loaded.
    @Published var typingTray: Shared.TypingTray? = nil
    @Published var typingTrayFailed: Bool = false
    @Published var secondaryLanguageRanges: [NSRange] = []
    // Predictions
    #if DEBUG
    @Published var predictions: Shared.PredictionResult = Shared.PredictionResult(words: [], letters: [])
    var predictionJob: Task<Void, Never>? = nil
    var predictionSubscription: Shared.NativeSubscription?
    #endif
    var boardPredictionSubscription: Shared.NativeSubscription?

    deinit {
        phraseSubscription?.cancel()
        sessionSubscription?.cancel()
        boardPredictionSubscription?.cancel()
        #if DEBUG
        predictionJob?.cancel()
        predictionSubscription?.cancel()
        #endif
    }

    // History items exposed as phrases for UI rendering
    var historyLoadGeneration = 0
    @Published var likelyNextPhrases: [Shared.Phrase] = []
    @Published var boardBackgroundImageUrl: String? = nil
    @Published var boardBackgroundImageFit = "Fill"
    @Published var historyPhrases: [Shared.Phrase] = []

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

    // Relocated stored state shared by extensions
    var lookedUpPageIds: Set<String> = []
    var lastActivationAtMillis: [String: Int64] = [:]

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
        sessionSubscription?.cancel()
        sessionSubscription = session.observe { [weak self] state in
            self?.applySessionState(state)
        }
        self.store = communicationFacade.phraseListStore()
        phraseSubscription?.cancel()
        phraseSubscription = communicationFacade.observePhrases { [weak self] state in
            self?.state = state
        }
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
    await loadTypingTray()
    // Load boardsets and selected board for symbol mode
    await loadBoardSets()
    _ = try? await boardsFacade.cacheAllBoardSetFields()
    }

    func refreshSettings() async {
        _ = await settings.refresh(fallbackUseSystemTts: speech.useSystemTts) { [weak self] useSystemTts, ttsEngine in
            self?.speech.applyEngineFromSettings(useSystemTts: useSystemTts, ttsEngine: ttsEngine)
        }
        await loadHistory()
    }

    func reloadAfterBackupRestore() async throws {
        communicationFacade.refreshPhrases()
        try await session.reloadAfterRestore()
        await loadTypingTray()
        await loadHistory()
        await refreshSettings()
        await speech.refreshAllProviderConfigurations()
        _ = await speech.refreshVoiceAndLanguages().value
        await loadBoardSets()
    }

    func retryPhraseLoad() {
        communicationFacade.refreshPhrases()
    }

    func deletePhrase(id: String) {
        if let path = recordingPath(for: id) {
            try? FileManager.default.removeItem(atPath: path)
        }
        store?.accept(intent: Shared.PhraseListStoreIntent.DeletePhrase(phraseId: id))
    }

    /// Selects the Category that Phrases added from the tray go into.
    func selectCategory(id: String?) {
        store?.accept(intent: Shared.PhraseListStoreIntent.SelectCategory(categoryId: id))
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
