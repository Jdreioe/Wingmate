import Foundation
import Shared
import AVFoundation
import Network

@MainActor
final class IosViewModel: ObservableObject {
    var store: Shared.PhraseListStore?
    private var phraseSubscription: Shared.NativeSubscription?

    @Published var state: Shared.PhraseListStoreState = Shared.PhraseListStoreState(phrases: [], categories: [], selectedCategoryId: nil, isLoading: true, error: nil, lastDeletedUndo: nil)

    // Shared feature facades, resolved from the iOS composition root after start()
    lazy var backupFacade = IosDiBridge().backupFacade()
    lazy var speechFacade = IosDiBridge().speechFacade()
    lazy var settingsFacade = IosDiBridge().settingsFacade()
    lazy var boardsFacade = IosDiBridge().boardsFacade()
    lazy var communicationFacade = IosDiBridge().communicationFacade()
    lazy var session = IosDiBridge().communicationSessionFacade()
    lazy var typingScreens = IosDiBridge().typingScreenFacade()
    lazy var predictionFacade = IosDiBridge().predictionFacade()
    lazy var pronunciationFacade = IosDiBridge().pronunciationFacade()
    lazy var symbolSearchFacade = IosDiBridge().symbolSearchFacade()
    // Shared access and highlight policies; Swift only supplies events and the clock.
    let accessInput = AccessInputController()
    let selectionHighlight = SelectionHighlight()
    let selectionDebouncer = SelectionDebouncer()
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
    @Published var primaryLanguage: String = "en-US"
    @Published var secondaryLanguage: String = "en-US"
    @Published var secondaryLanguageRanges: [NSRange] = []
    @Published var selectedVoice: Shared.Voice? = nil
    @Published var availableLanguages: [String] = []
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
        pendingPhraseUndoTask?.cancel()
        #if DEBUG
        predictionJob?.cancel()
        predictionSubscription?.cancel()
        #endif
    }

    // History items exposed as phrases for UI rendering
    var historyLoadGeneration = 0
    @Published var likelyNextPhrases: [Shared.Phrase] = []
    @Published var likelyNextPageEnabled = false
    @Published var selectOnTouchDown = false
    @Published var selectOnTouchExit = false
    @Published var swipeBetweenPages = false
    @Published var boardBackgroundImageUrl: String? = nil
    @Published var boardBackgroundImageFit = "Fill"
    @Published var historyPhrases: [Shared.Phrase] = []

    // Speech engine preference. The session falls back to the device voice by itself.
    @Published var useSystemTts: Bool = UserDefaults.standard.bool(forKey: "use_system_tts")
    @Published var ttsEngine: String = UserDefaults.standard.string(forKey: "tts_engine") ?? "SYSTEM"
    // Accessibility scanning configuration (persisted in shared Settings)
    @Published var scanningEnabled: Bool = false
    @Published var scanPlaybackAreaEnabled: Bool = true
    @Published var scanInputFieldEnabled: Bool = true
    @Published var scanPhraseGridEnabled: Bool = true
    @Published var scanCategoryItemsEnabled: Bool = true
    @Published var scanTopBarEnabled: Bool = true
    @Published var scanPhraseGridOrder: String = "row-major"
    @Published var scanDwellTimeSeconds: Double = 1.0
    @Published var scanAutoAdvanceSeconds: Double = 1.2
    // Cross-platform settings mirrored from Android's settings surface.
    @Published var showButtonLabels: Bool = true
    @Published var showButtonSymbols: Bool = true
    @Published var labelAtTop: Bool = false
    @Published var preferredGridColumns: Int = 3
    @Published var highContrastMode: Bool = false
    @Published var wordTypeColorScheme: String = "None"
    @Published var holdToSelectMillis: Double = 0
    @Published var dwellToSelectMillis: Double = 0
    @Published var dwellRearmDelayMillis: Double = Double(ModelKt.DEFAULT_DWELL_REARM_DELAY_MILLIS)
    @Published var selectionDebounceMillis: Double = 0
    @Published var selectionSoundEnabled: Bool = false
    @Published var auditoryFishingEnabled: Bool = false
    // #119: legacy immediate speech per selection, or sentence-only composition.
    @Published var speechPolicy: String = "Immediate"
    @Published var selectKeyBinding: String = ""
    @Published var restModeKeyBinding: String = ""
    @Published var pointerEmphasisStyle: String = "System"
    @Published var pointerEmphasisScale: Double = 1.5
    @Published var inputIsPaused: Bool = false
    @Published var accessTargetId: String? = nil
    @Published var accessDwellProgress: Double = 0
    var accessActions: [String: (handlesHaptics: Bool, action: () -> Void)] = [:]
    @Published var usageLoggingEnabled: Bool = false
    @Published var featureUsageReportingEnabled: Bool = false
    @Published var historyVisible: Bool = true
    @Published var startupUsesScreens: Bool = false
    @Published var startupBoardSetId: String? = nil

    // Pronunciation Dictionary
    @Published var pronunciations: [Shared.PronunciationEntry] = []
    @Published var pronunciationError: String? = nil

    // Azure availability (subscription configured)
    @Published var azureConfigured: Bool = false
    @Published var googleConfigured: Bool = false
    @Published var elevenLabsConfigured: Bool = false

    // Symbol-first boardset mode
    @Published var boardModeEnabled: Bool = false
    @Published var isCreatingBoardSet: Bool = false
    @Published var boardSets: [BoardSetInfo] = []
    @Published var selectedBoardSetId: String? = nil
    @Published var selectedBoardId: String? = nil
    @Published var selectedBoard: Shared.ObfBoard? = nil
    @Published var loadedBoardCellsPageId: String? = nil
    @Published var boardCellsLoadFailedPageId: String? = nil
    @Published var boardCells: [BoardCellInfo] = []
    @Published var boardFieldItems: [BoardFieldItem] = []
    @Published var selectedBoardKeyboardLayout: String? = nil
    @Published var selectedBoardUsesSpellingMode: Bool = false
    @Published var boardPredictionsByButtonId: [String: String] = [:]
    @Published var boardNamesById: [String: String] = [:]
    @Published var boardStatusMessage: String? = nil
    @Published var editingAccessEnabled: Bool = false
    @Published var editingAccessUnlocked: Bool = true
    @Published var editingAccessSupported: Bool = true
    @Published var editingAccessRetryAt: TimeInterval = 0
    @Published var selectionHighlightMillis: Int64 = 0 {
        didSet {
            if selectionHighlightMillis != oldValue {
                selectionHighlightGeneration += 1
                selectionHighlight.clear()
                highlightedButtonId = nil
            }
        }
    }
    @Published var highlightedButtonId: String? = nil
    var selectionHighlightGeneration: Int64 = 0
    @Published var boardShowMessageBar: Bool = true
    @Published var boardShowSpeakButton: Bool = true
    @Published var resolvedBoardShowLabels: Bool? = nil
    @Published var resolvedBoardShowSymbols: Bool? = nil
    @Published var resolvedBoardLabelAtTop: Bool? = nil
    @Published var resolvedBoardShowMessageBar: Bool? = nil
    @Published var resolvedBoardShowSpeakButton: Bool? = nil
    @Published var resolvedBoardMessageBarActions: [String]? = nil
    @Published var resolvedBoardSpeechPolicy: String? = nil
    @Published var resolvedBoardActivationBehavior: String? = nil
    @Published var resolvedBoardReturnBehavior: String? = nil
    @Published var resolvedBoardCellGapDp: Int? = nil
    @Published var resolvedBoardOuterMarginDp: Int? = nil
    @Published var boardStack: [String] = []

    var boardShowLabels: Bool { resolvedBoardShowLabels ?? showButtonLabels }
    var boardShowSymbols: Bool { resolvedBoardShowSymbols ?? showButtonSymbols }
    var boardLabelAtTop: Bool { resolvedBoardLabelAtTop ?? labelAtTop }
    var boardMessageBarVisible: Bool { resolvedBoardShowMessageBar ?? boardShowMessageBar }
    var boardSpeakButtonVisible: Bool { resolvedBoardShowSpeakButton ?? boardShowSpeakButton }
    /// The Screen's message bar buttons in order (`Speak`, `Hold`, `DeleteLast`, `Clear`).
    var boardMessageBarActions: [String] {
        resolvedBoardMessageBarActions ?? (boardShowSpeakButton ? ["Speak"] : [])
    }
    var boardActivationBehavior: String { resolvedBoardActivationBehavior ?? "SpeakAndAdd" }
    var boardReturnBehavior: String { resolvedBoardReturnBehavior ?? "Stay" }

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
    @Published var pendingPhraseUndo: PendingPhraseUndo? = nil
    var pendingPhraseUndoTask: Task<Void, Never>? = nil
    var dismissedPhraseUndoIds: Set<Int64> = []

    init() {
        accessInput.rearmDelayMillis = Int64(dwellRearmDelayMillis)
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
        sessionSubscription?.cancel()
        sessionSubscription = session.observe { [weak self] state in
            self?.applySessionState(state)
        }
        self.store = communicationFacade.phraseListStore()
        phraseSubscription?.cancel()
        phraseSubscription = communicationFacade.observePhrases { [weak self] state in
            self?.state = state
            self?.syncPhraseUndo(from: state)
        }
    // Load selected voice and languages for welcome gating and UI
    refreshVoiceAndLanguages()

        await refreshLanguagePreferences()
        await refreshScanningSettings()
        await refreshParitySettings()
        // The startup workspace applies at launch only. Settings also refresh
        // these values, and must not switch the workspace the user is in.
        boardModeEnabled = startupUsesScreens

        // Determine if Azure is configured (endpoint + key)
        await refreshAzureConfiguration()
        await refreshGoogleConfiguration()
        await refreshElevenLabsConfiguration()

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
    // Load pronunciations
    await loadPronunciations()
    // Load boardsets and selected board for symbol mode
    await loadBoardSets()
    _ = try? await boardsFacade.cacheAllBoardSetFields()
    }

    func retryPhraseLoad() {
        communicationFacade.refreshPhrases()
    }

    func deletePhrase(id: String) {
        store?.accept(intent: Shared.PhraseListStoreIntent.DeletePhrase(phraseId: id))
    }

    /// Selects the Category that Phrases added from the tray go into.
    func selectCategory(id: String?) {
        store?.accept(intent: Shared.PhraseListStoreIntent.SelectCategory(categoryId: id))
    }
}

struct PendingPhraseUndo: Equatable {
    let id: Int64
    let includesSubItems: Bool
    var isRestoring: Bool = false
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
