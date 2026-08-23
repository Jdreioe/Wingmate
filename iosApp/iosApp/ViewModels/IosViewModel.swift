import Foundation
import Shared
import AVFoundation
import Network

struct BoardSetInfo: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var rootBoardId: String
    var boardIds: [String]
    var isLocked: Bool
    var cacheWholeSentences: Bool
    var updatedAt: TimeInterval
}

struct BoardCellInfo: Identifiable, Equatable {
    var row: Int
    var col: Int
    var buttonId: String
    var label: String?
    var vocalization: String?
    var backgroundColor: String?
    var resolvedBackgroundColor: String?
    var wordType: String?
    var borderColor: String?
    var linkedBoardId: String?
    var imageId: String?
    var imageUrl: String?
    var hidden: Bool
    var actions: [String]
    var soundId: String? = nil
    var soundDataUrl: String? = nil
    var shape: String

    var id: String { "\(row):\(col)" }
}

struct BoardFieldItem: Identifiable, Equatable {
    var row: Int
    var column: Int
    var rowSpan: Int
    var columnSpan: Int
    var buttonId: String?

    var id: String { "\(row):\(column)" }
}

struct SentencePhraseToken: Identifiable, Equatable {    var id: String = UUID().uuidString
    var phraseId: String
    var text: String
    var title: String
    var imageUrl: String?
}

@MainActor
final class IosViewModel: ObservableObject {
    private enum PendingSpeechRetry {
        case text(String)
        case boardSentence(String, String)
    }
    private final class StoreObserver: NSObject, Shared.RxObserver {
        private let onNextState: (Shared.PhraseListStoreState) -> Void
        private let onCompleteState: () -> Void
        init(onNext: @escaping (Shared.PhraseListStoreState) -> Void) {
            self.onNextState = onNext
            self.onCompleteState = {}
        }
        init(onNext: @escaping (Shared.PhraseListStoreState) -> Void, onComplete: @escaping () -> Void) {
            self.onNextState = onNext
            self.onCompleteState = onComplete
        }
        func onComplete() { onCompleteState() }
        func onNext(value: Any?) {
            if let s = value as? Shared.PhraseListStoreState {
                onNextState(s)
            }
        }
    }
    private var store: Shared.PhraseListStore?
    private var disposable: Shared.RxDisposable?
    private let hybrid = HybridSpeechPlayer()
    private lazy var azureSequencer: AzureHybridSequencer = {
        AzureHybridSequencer(
            speak: { [weak self] text in
                guard let self = self else { return }
                do {
                    try await self.speechFacade.speak(text: text)
                } catch {
                    self.setSpeechFailure(retry: .text(text))
                }
            },
            pause: { [weak self] in
                guard let self = self else { return }
                do {
                    try await self.speechFacade.pause()
                } catch {
                    self.setSpeechFailure()
                }
            },
            stop: { [weak self] in
                guard let self = self else { return }
                do {
                    try await self.speechFacade.stop()
                } catch {
                    self.setSpeechFailure()
                }
            }
        )
    }()

    @Published var state: Shared.PhraseListStoreState = Shared.PhraseListStoreState(phrases: [], categories: [], selectedCategoryId: nil, isLoading: true, error: nil)

    // Bridge to shared KMP use-cases
    private let bridge = KoinBridge()
    private lazy var backupFacade = IosDiBridge().backupFacade()
    private lazy var speechFacade = IosDiBridge().speechFacade()
    private lazy var settingsFacade = IosDiBridge().settingsFacade()
    private lazy var boardsFacade = IosDiBridge().boardsFacade()
    private lazy var communicationFacade = IosDiBridge().communicationFacade()

    // UI state
    @Published var input: String = ""
    var inputSelectionRange: NSRange = NSRange(location: 0, length: 0)
    @Published private(set) var hasHeldThought: Bool = false
    @Published var primaryLanguage: String = "en-US"
    @Published var secondaryLanguage: String = "en-US"
    @Published var secondaryLanguageRanges: [NSRange] = []
    @Published var selectedVoice: Shared.Voice? = nil
    @Published var availableLanguages: [String] = []
    // Predictions
    #if DEBUG
    @Published var predictions: Shared.PredictionResult = Shared.PredictionResult(words: [], letters: [])
    private var predictionJob: Task<Void, Never>? = nil
    #endif
    // History items exposed as phrases for UI rendering
    @Published var historyPhrases: [Shared.Phrase] = []
    // Special selection for History view
    let historyCategoryId = "__history__"

    // Offline handling and System TTS fallback
    @Published var showOfflineInfoOnce: Bool = false
    // System TTS preference 
    @Published var useSystemTts: Bool = UserDefaults.standard.bool(forKey: "use_system_tts")
    @Published var ttsEngine: String = UserDefaults.standard.string(forKey: "tts_engine") ?? "SYSTEM"
    @Published var useSystemTtsWhenOffline: Bool = UserDefaults.standard.bool(forKey: "use_system_tts_when_offline")
    // Mix recorded phrases inside sentences
    @Published var mixRecordedPhrasesInSentences: Bool = UserDefaults.standard.bool(forKey: "mix_recorded_phrases")
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
    @Published var selectionDebounceMillis: Double = 0
    @Published var selectionSoundEnabled: Bool = false
    @Published var auditoryFishingEnabled: Bool = false
    // #119: legacy immediate speech per selection, or sentence-only composition.
    @Published var speechPolicy: String = "Immediate"
    @Published var selectKeyBinding: String = ""
    @Published var restModeKeyBinding: String = ""
    @Published var pointerEmphasisStyle: String = "System"
    @Published var pointerEmphasisScale: Double = 1.5
    @Published private(set) var inputIsPaused: Bool = false
    @Published private(set) var accessTargetId: String? = nil
    @Published private(set) var accessDwellProgress: Double = 0
    private var accessActions: [String: () -> Void] = [:]
    @Published var usageLoggingEnabled: Bool = false
    @Published var featureUsageReportingEnabled: Bool = false
    @Published var historyVisible: Bool = true
    @Published private(set) var speechErrorMessage: String? = nil
    private var pendingSpeechRetry: PendingSpeechRetry? = nil
    var canRetryFailedSpeech: Bool { pendingSpeechRetry != nil }
    @Published var startupUsesScreens: Bool = false
    @Published var startupBoardSetId: String? = nil
    private var hasShownOfflineBanner: Bool = UserDefaults.standard.bool(forKey: "offline_banner_shown")
    private var isOnline: Bool = true

    // Pronunciation Dictionary
    @Published var pronunciations: [Shared.PronunciationEntry] = []

    // Debug helpers
    @Published var debugRepoName: String = ""
    @Published var debugPersistedVoiceName: String = ""
    // Azure availability (subscription configured)
    @Published var azureConfigured: Bool = false
    @Published var googleConfigured: Bool = false
    var cloudConfigured: Bool { ttsEngine == "GOOGLE_CLOUD" ? googleConfigured : azureConfigured }

    // Symbol-first boardset mode
    @Published var boardModeEnabled: Bool = false
    @Published var quickCoreDownloadProgress: Double? = nil
    @Published var isImportingQuickCore: Bool = false
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
    @Published var sentencePhrases: [SentencePhraseToken] = []
    @Published var editingAccessEnabled: Bool = false
    @Published var editingAccessUnlocked: Bool = true
    @Published var editingAccessSupported: Bool = true
    @Published var selectionHighlightMillis: Int64 = 0
    @Published var highlightedButtonId: String? = nil
    private var selectionHighlightGeneration: Int64 = 0
    @Published var boardShowMessageBar: Bool = true
    @Published var resolvedBoardShowLabels: Bool? = nil
    @Published var resolvedBoardShowSymbols: Bool? = nil
    @Published var resolvedBoardLabelAtTop: Bool? = nil
    @Published var resolvedBoardShowMessageBar: Bool? = nil
    @Published var resolvedBoardActivationBehavior: String? = nil
    @Published var resolvedBoardReturnBehavior: String? = nil
    @Published private(set) var boardStack: [String] = []

    var boardShowLabels: Bool { resolvedBoardShowLabels ?? showButtonLabels }
    var boardShowSymbols: Bool { resolvedBoardShowSymbols ?? showButtonSymbols }
    var boardLabelAtTop: Bool { resolvedBoardLabelAtTop ?? labelAtTop }
    var boardMessageBarVisible: Bool { resolvedBoardShowMessageBar ?? boardShowMessageBar }
    var boardActivationBehavior: String { resolvedBoardActivationBehavior ?? "SpeakAndAdd" }
    var boardReturnBehavior: String { resolvedBoardReturnBehavior ?? "Stay" }

    private var isApplyingSentencePhraseInput: Bool = false

    private struct InputSnapshot {
        let text: String
        let selection: NSRange
        let secondaryRanges: [NSRange]
    }

    private var heldThoughtSnapshot: InputSnapshot? = nil

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

    func refreshEditingAccess() async {
        guard let state = try? await settingsFacade.editingAccessState() else { return }
        editingAccessEnabled = state.enabled
        editingAccessUnlocked = state.unlocked
        editingAccessSupported = state.supported
    }

    func unlockEditingAccess(_ code: String) async -> Bool {
        let success = (try? await settingsFacade.unlockEditing(code: code))?.boolValue ?? false
        await refreshEditingAccess()
        return success
    }

    func configureEditingAccess(_ code: String) async -> Bool {
        do {
            try await settingsFacade.configureEditingAccess(code: code)
            await refreshEditingAccess()
            return true
        } catch {
            return false
        }
    }

    func disableEditingAccess(_ code: String) async -> Bool {
        let success = (try? await settingsFacade.disableEditingAccess(code: code))?.boolValue ?? false
        await refreshEditingAccess()
        return success
    }

    func recoverEditingAccess() async {
        try? await settingsFacade.recoverEditingAccess()
        await refreshEditingAccess()
    }

    func lockEditingAccess() {
        settingsFacade.lockEditingAccess()
        editingAccessUnlocked = !editingAccessEnabled
    }

    func editingIsAuthorized() async -> Bool {
        await refreshEditingAccess()
        return !editingAccessEnabled || editingAccessUnlocked
    }

    func shareCompleteBackup() async throws -> Shared.BackupOperationResult {
        try await backupFacade.shareBackup()
    }

    func restoreCompleteBackup(path: String) async throws -> Shared.BackupOperationResult {
        let result = try await backupFacade.restoreBackup(path: path)
        if result.isSuccess {
            communicationFacade.refreshPhrases()
        }
        return result
    }

    func boardDisplayName(id: String) -> String {
        if let selectedBoard, selectedBoard.id == id {
            let selectedName = selectedBoard.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !selectedName.isEmpty {
                return selectedName
            }
        }
        if let cachedName = boardNamesById[id], !cachedName.isEmpty {
            return cachedName
        }
        return id
    }

    func cellAt(row: Int, col: Int) -> BoardCellInfo? {
        boardCells.first(where: { $0.row == row && $0.col == col })
    }

    var isKeyboardBoard: Bool {
        selectedBoardKeyboardLayout != nil
    }

    func availableFieldSpanOptions(row: Int, col: Int) async -> [GridFieldSpanInfo] {
        guard let boardId = selectedBoardId else { return [] }
        let spans = (try? await boardsFacade.availableFieldSpans(
            boardId: boardId,
            row: Int32(row),
            col: Int32(col)
        )) ?? []
        return spans.map { GridFieldSpanInfo(rows: Int($0.rows), columns: Int($0.columns)) }
    }

    func resizeSelectedBoardField(row: Int, col: Int, rows: Int, columns: Int) async {
        guard let boardId = selectedBoardId else { return }
        let ok = (try? await boardsFacade.resizeBoardField(
            boardId: boardId,
            row: Int32(row),
            col: Int32(col),
            rowSpan: Int32(rows),
            columnSpan: Int32(columns)
        )) ?? false
        if ok.boolValue {
            await refreshBoardCells()
        }
    }

    func effectiveLanguage(for v: Shared.Voice) -> String {
        func nonEmpty(_ s: String?) -> String? {
            let t = (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        if let s = nonEmpty(v.selectedLanguage) { return s }
        if let p = nonEmpty(v.primaryLanguage) { return p }
        return self.primaryLanguage
    }

    var canChangeVoiceLanguage: Bool {
        let languages = availableLanguages
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return Set(languages).count > 1
    }

    func start() async {
        await MainActor.run { IosDiBridge().startKoinWithOverridesBridge() }
        let repoNameBefore = KoinBridge().debugVoiceRepositoryName()
        print("DEBUG: After startKoinWithOverrides: Bound VoiceRepository = \(repoNameBefore)")
        self.store = communicationFacade.phraseListStore()
        let observer = StoreObserver(onNext: { [weak self] newState in self?.state = newState }, onComplete: { [weak self] in
            self?.disposable = nil
            self?.store = nil
        })
        self.disposable = store?.states(observer: observer)
    // Load selected voice and languages for welcome gating and UI
    refreshVoiceAndLanguages()

        await refreshLanguagePreferences()
        await refreshScanningSettings()
        await refreshParitySettings()

        // Determine if Azure is configured (endpoint + key)
        await refreshAzureConfiguration()
        await refreshGoogleConfiguration()

        // Start connectivity monitoring
        ConnectivityMonitor.shared.onChange { [weak self] online in
            guard let self = self else { return }
            self.isOnline = online
            self.boardsFacade.updateBoardSetSpeechCacheOnline(online: online)
            if online {
                Task {
                    try? await self.boardsFacade.cacheAllBoardSetFields()
                    try? await self.boardsFacade.retryBoardSetSpeechCaching()
                }
            }
            if !online && !self.hasShownOfflineBanner {
                self.showOfflineInfoOnce = true
                self.hasShownOfflineBanner = true
                UserDefaults.standard.set(true, forKey: "offline_banner_shown")
            }
        }
    // Preload history once Koin is up
    await loadHistory()
    #if DEBUG
    // Predictions are a development-only feature and are excluded from production behavior.
    _ = try? await bridge.trainPredictionModel()
    #endif
    // Load pronunciations
    await loadPronunciations()
    // Load boardsets and selected board for symbol mode
    await loadBoardSets()
    _ = try? await boardsFacade.cacheAllBoardSetFields()
    }

    func retryPhraseLoad() {
        communicationFacade.refreshPhrases()
    }

    func refreshAzureConfiguration() async {
        do {
            let cfg = try await speechFacade.getSpeechConfig()
            let ep = cfg.endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            azureConfigured = !ep.isEmpty && cfg.credentialConfigured
        } catch {
            azureConfigured = false
        }
    }

    func refreshGoogleConfiguration() async {
        do {
            let status = try await speechFacade.getGoogleSpeechConfig()
            googleConfigured = status.credentialConfigured
        } catch {
            googleConfigured = false
        }
    }

    func saveGoogleApiKey(_ apiKey: String) async throws {
        _ = try await speechFacade.saveValidatedGoogleSpeechConfig(apiKey: apiKey)
        await refreshGoogleConfiguration()
        setTtsEngine("GOOGLE_CLOUD")
    }

    func clearGoogleApiKey() async throws {
        try await speechFacade.clearGoogleSpeechConfig()
        await refreshGoogleConfiguration()
    }

    func refreshLanguagePreferences() async {
        do {
            let settings = try await settingsFacade.getSettings()
            await MainActor.run {
                self.primaryLanguage = settings.primaryLanguage
                self.secondaryLanguage = settings.secondaryLanguage
            }
        } catch {
            await MainActor.run {
                self.primaryLanguage = self.primaryLanguage
                self.secondaryLanguage = self.secondaryLanguage
            }
        }
    }

    func refreshScanningSettings() async {
        do {
            let settings = try await settingsFacade.getSettings()
            await MainActor.run {
                self.scanningEnabled = settings.scanningEnabled
                self.scanPlaybackAreaEnabled = settings.scanPlaybackAreaEnabled
                self.scanInputFieldEnabled = settings.scanInputFieldEnabled
                self.scanPhraseGridEnabled = settings.scanPhraseGridEnabled
                self.scanCategoryItemsEnabled = settings.scanCategoryItemsEnabled
                self.scanTopBarEnabled = settings.scanTopBarEnabled
                self.scanPhraseGridOrder = self.normalizedScanGridOrder(settings.scanPhraseGridOrder)
                self.scanDwellTimeSeconds = Double(self.clampedDwellSeconds(settings.scanDwellTimeSeconds))
                self.scanAutoAdvanceSeconds = Double(self.clampedAutoAdvanceSeconds(settings.scanAutoAdvanceSeconds))
            }
        } catch {
            await MainActor.run {
                self.scanPhraseGridOrder = self.normalizedScanGridOrder(self.scanPhraseGridOrder)
                self.scanDwellTimeSeconds = Double(self.clampedDwellSeconds(Float(self.scanDwellTimeSeconds)))
                self.scanAutoAdvanceSeconds = Double(self.clampedAutoAdvanceSeconds(Float(self.scanAutoAdvanceSeconds)))
            }
        }
    }

    func refreshParitySettings() async {
        do {
            let settings = try await settingsFacade.getSettings()
            let flags = try? await settingsFacade.iosSettingsFlags()
            let systemTts = flags?.usesSystemTts ?? useSystemTts
            let engine = flags?.ttsEngine ?? (systemTts ? "SYSTEM" : "AZURE_USER_RESOURCE")
            let opensScreens = flags?.startupUsesScreens ?? false
            await MainActor.run {
                self.useSystemTts = systemTts
                self.ttsEngine = engine
                UserDefaults.standard.set(systemTts, forKey: "use_system_tts")
                UserDefaults.standard.set(engine, forKey: "tts_engine")
                self.showButtonLabels = settings.showLabels
                self.showButtonSymbols = settings.showSymbols
                self.labelAtTop = settings.labelAtTop
                self.preferredGridColumns = min(max(Int(settings.gridColumns), 1), 6)
                self.highContrastMode = settings.highContrastMode
                self.wordTypeColorScheme = settings.wordTypeColorScheme.name
                self.holdToSelectMillis = Double(settings.holdToSelectMillis)
                self.dwellToSelectMillis = Double(settings.dwellToSelectMillis)
                self.selectionDebounceMillis = Double(settings.selectionDebounceMillis)
                self.selectionSoundEnabled = settings.selectionSoundEnabled
                self.auditoryFishingEnabled = settings.auditoryFishingEnabled
                self.speechPolicy = settings.speechPolicy.name
                self.selectKeyBinding = settings.selectKeyBinding
                self.restModeKeyBinding = settings.restModeKeyBinding
                self.pointerEmphasisStyle = settings.pointerEmphasisStyle.name
                self.pointerEmphasisScale = Double(settings.pointerEmphasisScale)
                self.selectionHighlightMillis = settings.selectionHighlightMillis
                self.boardShowMessageBar = settings.boardShowMessageBar
                self.usageLoggingEnabled = settings.usageLoggingEnabled
                self.featureUsageReportingEnabled = settings.featureUsageReportingEnabled
                self.historyVisible = settings.historyVisible
                self.startupUsesScreens = opensScreens
                self.startupBoardSetId = settings.startupBoardSetId
                self.boardModeEnabled = opensScreens
            }
        } catch {
            // Keep the native defaults when shared settings are unavailable.
        }
    }

    func deletePhrase(id: String) {
        if let path = recordingPath(for: id) {
            try? FileManager.default.removeItem(atPath: path)
        }
        store?.accept(intent: Shared.PhraseListStoreIntent.DeletePhrase(phraseId: id))
    }

    func selectCategory(id: String?) {
        // Toggle history mode if the special ID is selected
        if id == historyCategoryId {
            // Keep the store's selectedCategoryId nil to avoid filtering real phrases
            store?.accept(intent: Shared.PhraseListStoreIntent.SelectCategory(categoryId: nil))
        } else {
            store?.accept(intent: Shared.PhraseListStoreIntent.SelectCategory(categoryId: id))
        }
    }

    var filteredPhrases: [Shared.Phrase] {
        guard let sel = state.selectedCategoryId, !sel.isEmpty else { return state.phrases }
        return state.phrases.filter { $0.parentId == sel }
    }

    var isHistorySelected: Bool {
        // We consider history selected when selectedCategoryId is nil but a shadow selection equals history
        // The MainContentView will drive this by selecting our sentinel explicitly.
        return false // The view controls selection via the chip; we keep store selection separate.
    }

    func insertPhraseText(_ phrase: Shared.Phrase) {
        // #118: ignore rapid repeated activations of the same target.
        guard acceptActivation(targetId: phrase.id) else { return }
        let t = phrase.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        let nsInput = input as NSString
        let inputLength = nsInput.length

        var range = inputSelectionRange
        if range.location == NSNotFound {
            range = NSRange(location: inputLength, length: 0)
        }

        let safeLocation = min(max(0, range.location), inputLength)
        let safeLength = min(max(0, range.length), max(0, inputLength - safeLocation))
        let safeRange = NSRange(location: safeLocation, length: safeLength)

        var prefix = ""
        if safeLocation > 0 {
            let previousChar = nsInput.substring(with: NSRange(location: safeLocation - 1, length: 1))
            if previousChar.rangeOfCharacter(from: .whitespacesAndNewlines) == nil {
                prefix = " "
            }
        }

        let insertion = prefix + t + " "

        input = nsInput.replacingCharacters(in: safeRange, with: insertion)
        let insertionLength = (insertion as NSString).length
        inputSelectionRange = NSRange(location: safeLocation + insertionLength, length: 0)

        let title = (phrase.name?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 } ?? t
        let imageUrl = phrase.imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        sentencePhrases.append(
            SentencePhraseToken(
                phraseId: phrase.id,
                text: t,
                title: title,
                imageUrl: (imageUrl?.isEmpty == false) ? imageUrl : nil
            )
        )

        isApplyingSentencePhraseInput = true
        onInputChanged(input)
        isApplyingSentencePhraseInput = false
        // #119: immediate speech policy speaks each inserted phrase as it is composed.
        if speechPolicy == "Immediate" {
            speak(title)
        }
        #if DEBUG
        // Incremental learning for development builds with predictions enabled.
        Task { _ = try? await bridge.learnPhrase(text: t) }
        #endif
    }

    func removeSentencePhrase(at index: Int) {
        guard sentencePhrases.indices.contains(index) else { return }
        sentencePhrases.remove(at: index)

        let rebuilt = sentencePhrases.map { $0.text }.joined(separator: " ")
        input = rebuilt.isEmpty ? "" : rebuilt + " "
        inputSelectionRange = NSRange(location: (input as NSString).length, length: 0)
        secondaryLanguageRanges = []

        isApplyingSentencePhraseInput = true
        onInputChanged(input)
        isApplyingSentencePhraseInput = false
    }

    func deleteText() {
        input = ""
        inputSelectionRange = NSRange(location: 0, length: 0)
        sentencePhrases = []
        onInputChanged(input)
    }

    func toggleHoldThatThought() {
        let current = makeCurrentInputSnapshot()

        if let held = heldThoughtSnapshot {
            heldThoughtSnapshot = current
            applyInputSnapshot(held)
        } else {
            heldThoughtSnapshot = current
            input = ""
            inputSelectionRange = NSRange(location: 0, length: 0)
            secondaryLanguageRanges = []
            onInputChanged(input)
        }

        hasHeldThought = heldThoughtSnapshot != nil
    }
    func speak(_ text: String) {
        let plain = text
        guard !plain.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        AudioSessionHelper.activatePlayback()

        // Only convert hidden ranges for the live input text and Azure path.
        let t = (text == input) ? textWithSecondaryLanguageMarkup(from: plain) : plain

        // Hybrid mixing: splice recorded phrase audio into sentence
        if mixRecordedPhrasesInSentences {
            let segments = buildHybridSegments(for: plain)
            if !segments.isEmpty {
                // Decide engine for TTS segments: Azure vs local
                let shouldUseAzure = cloudConfigured && isOnline || (cloudConfigured && !useSystemTtsWhenOffline)
                if !useSystemTts && shouldUseAzure {
                    let azSegments: [AzureHybridSequencer.Segment] = segments.map { seg in
                        switch seg {
                        case .audio(let url): return .audio(url)
                        case .tts(let s): return .tts(s)
                        }
                    }
                    azureSequencer.play(segments: azSegments)
                } else {
                    hybrid.play(segments: segments, language: primaryLanguage)
                }
                return
            }
        }

        let isInputText = (text == input)

        // If user prefers system TTS, use it directly
        if useSystemTts {
            speakSystemText(plain, isInputText: isInputText)
            return
        }
        
        // If the selected cloud provider is not configured, keep communication working on-device.
        if !cloudConfigured {
            speakSystemText(plain, isInputText: isInputText)
            return
        }
        // Otherwise, allow offline fallback when enabled
        if !isOnline && useSystemTtsWhenOffline {
            speakSystemText(plain, isInputText: isInputText)
            return
        }
        Task {
            do {
                try await speechFacade.speak(text: t)
                clearSpeechFailure()
            } catch {
                guard !Task.isCancelled else { return }
                speakSystemText(plain, isInputText: isInputText)
                clearSpeechFailure()
            }
        }
    }

    /// Speak on-device, honoring shorthand SSML (pauses + language tags) via shared
    /// SpeechTextProcessor when no secondary-language splitting is required.
    private func speakSystemText(_ text: String, isInputText: Bool) {
        if !isInputText || secondaryLanguageRanges.isEmpty {
            let segments = speechFacade.processSpeechText(text: text)
            SystemTtsManager.shared.speak(segments: segments, language: primaryLanguage)
        } else {
            SystemTtsManager.shared.speak(
                text,
                language: primaryLanguage,
                secondaryLanguage: secondaryLanguage,
                secondaryLanguageRanges: secondaryLanguageRanges
            )
        }
    }

    func speakBoardSentence(_ text: String, boardSetId: String) {
        let cacheAudio = boardSets.first(where: { $0.id == boardSetId })?.cacheWholeSentences ?? true
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        AudioSessionHelper.activatePlayback()
        if useSystemTts || !cloudConfigured || (!isOnline && useSystemTtsWhenOffline) {
            let segments = speechFacade.processSpeechText(text: text)
            SystemTtsManager.shared.speak(segments: segments, language: primaryLanguage)
            return
        }
        Task {
            do {
                try await speechFacade.speakBoardSentence(text: text, cacheAudio: cacheAudio)
                clearSpeechFailure()
            } catch {
                guard !Task.isCancelled else { return }
                let segments = speechFacade.processSpeechText(text: text)
                SystemTtsManager.shared.speak(segments: segments, language: primaryLanguage)
                clearSpeechFailure()
            }
        }
    }

    private func setSpeechFailure(retry: PendingSpeechRetry? = nil) {
        pendingSpeechRetry = retry
        speechErrorMessage = NSLocalizedString("speech.playback_failed", comment: "")
    }

    private func clearSpeechFailure() {
        speechErrorMessage = nil
        pendingSpeechRetry = nil
    }

    func retryFailedSpeech() {
        guard let retry = pendingSpeechRetry else { return }
        clearSpeechFailure()
        switch retry {
        case .text(let text):
            speak(text)
        case .boardSentence(let text, let boardSetId):
            speakBoardSentence(text, boardSetId: boardSetId)
        }
    }

    func playBoardButtonSound(_ dataUrl: String) {
        guard !dataUrl.isEmpty else { return }
        AudioSessionHelper.activatePlayback()
        if let url = playableURL(from: dataUrl) {
            hybrid.play(segments: [.audio(url)], language: primaryLanguage)
        }
    }

    private func playableURL(from dataUrl: String) -> URL? {
        if let base64Range = dataUrl.range(of: "base64,") {
            let encoded = String(dataUrl[base64Range.upperBound...])
            guard let data = Data(base64Encoded: encoded) else { return nil }
            let ext = dataUrl.hasPrefix("data:audio/mpeg") ? "mp3" : "caf"
            let temp = FileManager.default.temporaryDirectory
                .appendingPathComponent("wingmate-button-sound-\(UUID().uuidString).\(ext)")
            do {
                try data.write(to: temp)
                return temp
            } catch {
                return nil
            }
        }
        return URL(string: dataUrl)
    }

    private func textWithSecondaryLanguageMarkup(from plainText: String) -> String {
        let locale = secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !locale.isEmpty,
              locale != primaryLanguage,
              !secondaryLanguageRanges.isEmpty else {
            return plainText
        }

        let ns = plainText as NSString
        let validRanges = secondaryLanguageRanges
            .filter { $0.location != NSNotFound && $0.length > 0 && $0.location + $0.length <= ns.length }
            .sorted { $0.location < $1.location }

        guard !validRanges.isEmpty else { return plainText }

        var cursor = 0
        var out = ""
        for range in validRanges {
            if range.location > cursor {
                out += ns.substring(with: NSRange(location: cursor, length: range.location - cursor))
            }
            let selected = ns.substring(with: range)
            out += "<lang xml:lang=\"\(locale)\">\(selected)</lang>"
            cursor = range.location + range.length
        }
        if cursor < ns.length {
            out += ns.substring(from: cursor)
        }
        return out
    }

    // MARK: - History
    func loadHistory() async {
        guard historyVisible else {
            historyPhrases = []
            return
        }
        do {
            let items = try await communicationFacade.listHistoryAsPhrases()
            await MainActor.run { self.historyPhrases = items.reversed() }
        } catch {
            await MainActor.run { self.historyPhrases = [] }
        }
    }
    // Build mixed segments: recorded audio when a phrase name/text matches; TTS for the rest
    private func buildHybridSegments(for text: String) -> [HybridSpeechPlayer.Segment] {
        // Prepare lookup: map name/text -> recording path if exists
        let phrases = filteredPhrases // use currently visible scope; could also use state.phrases
        var dictionary: [(pattern: NSRegularExpression, id: String, path: String)] = []
        for p in phrases {
            let key = (p.name?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 } ?? p.text
            guard !key.isEmpty, let path = recordingPath(for: p.id), !path.isEmpty else { continue }
            // Word-boundary, case-insensitive
            let pattern = "\\b" + NSRegularExpression.escapedPattern(for: key) + "\\b"
            if let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) {
                dictionary.append((re, p.id, path))
            }
        }
        guard !dictionary.isEmpty else { return [] }

        // Find non-overlapping matches preferring longer keys first
        let ns = text as NSString
        var matches: [(range: NSRange, path: String)] = []
        for (re, _, path) in dictionary.sorted(by: { $0.pattern.pattern.count > $1.pattern.pattern.count }) {
            let found = re.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length))
            for m in found {
                // Skip overlaps
                if matches.contains(where: { NSIntersectionRange($0.range, m.range).length > 0 }) { continue }
                matches.append((m.range, path))
            }
        }
        guard !matches.isEmpty else { return [] }

        // Sort by location and build segments
        matches.sort { $0.range.location < $1.range.location }
        var segs: [HybridSpeechPlayer.Segment] = []
        var cursor = 0
        for m in matches {
            if m.range.location > cursor {
                let start = cursor
                let end = m.range.location
                let chunk = ns.substring(with: NSRange(location: start, length: end - start))
                if !chunk.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    segs.append(.tts(chunk))
                }
            }
            segs.append(.audio(URL(fileURLWithPath: m.path)))
            cursor = m.range.location + m.range.length
        }
        if cursor < ns.length {
            let chunk = ns.substring(from: cursor)
            if !chunk.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                segs.append(.tts(chunk))
            }
        }
        return segs
    }

    func pauseTts() {
        if azureSequencer.isRunning {
            azureSequencer.pause()
            return
        }
        if hybrid.isPlaying {
            hybrid.pause()
            return
        }
        if useSystemTts || !cloudConfigured {
            SystemTtsManager.shared.pause()
        } else if !isOnline && useSystemTtsWhenOffline {
            SystemTtsManager.shared.pause()
        } else {
            Task {
                do {
                    try await speechFacade.pause()
                } catch {
                    setSpeechFailure()
                }
            }
        }
    }

    func stopTts() {
        if azureSequencer.isRunning {
            azureSequencer.stop()
            return
        }
        if hybrid.isPlaying {
            hybrid.stop()
            return
        }
        if useSystemTts || !cloudConfigured {
            SystemTtsManager.shared.stop()
        } else if !isOnline && useSystemTtsWhenOffline {
            SystemTtsManager.shared.stop()
        } else {
            Task {
                do {
                    try await speechFacade.stop()
                } catch {
                    setSpeechFailure()
                }
            }
        }
    }

    func setUseSystemTts(_ enabled: Bool) {
        self.useSystemTts = enabled
        UserDefaults.standard.set(enabled, forKey: "use_system_tts")
        Task { _ = try? await speechFacade.updateUseSystemTts(enabled: enabled) }
    }

    func setTtsEngine(_ engine: String) {
        ttsEngine = engine
        useSystemTts = engine == "SYSTEM"
        UserDefaults.standard.set(engine, forKey: "tts_engine")
        UserDefaults.standard.set(useSystemTts, forKey: "use_system_tts")
        Task { try? await speechFacade.updateTtsEngineNamed(engine: engine) }
    }

    func setUseSystemTtsWhenOffline(_ enabled: Bool) {
        self.useSystemTtsWhenOffline = enabled
        UserDefaults.standard.set(enabled, forKey: "use_system_tts_when_offline")
    }

    func setMixRecordedPhrases(_ enabled: Bool) {
        self.mixRecordedPhrasesInSentences = enabled
        UserDefaults.standard.set(enabled, forKey: "mix_recorded_phrases")
    }

    func setScanningEnabled(_ enabled: Bool) {
        self.scanningEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanningEnabled(enabled: enabled) }
    }

    func setScanPlaybackAreaEnabled(_ enabled: Bool) {
        self.scanPlaybackAreaEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanPlaybackAreaEnabled(enabled: enabled) }
    }

    func setScanInputFieldEnabled(_ enabled: Bool) {
        self.scanInputFieldEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanInputFieldEnabled(enabled: enabled) }
    }

    func setScanPhraseGridEnabled(_ enabled: Bool) {
        self.scanPhraseGridEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanPhraseGridEnabled(enabled: enabled) }
    }

    func setScanCategoryItemsEnabled(_ enabled: Bool) {
        self.scanCategoryItemsEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanCategoryItemsEnabled(enabled: enabled) }
    }

    func setScanTopBarEnabled(_ enabled: Bool) {
        self.scanTopBarEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanTopBarEnabled(enabled: enabled) }
    }

    func setScanPhraseGridOrder(_ order: String) {
        let normalized = normalizedScanGridOrder(order)
        self.scanPhraseGridOrder = normalized
        Task { _ = try? await settingsFacade.updateScanPhraseGridOrder(order: normalized) }
    }

    func setScanDwellTimeSeconds(_ value: Double) {
        let clamped = Double(clampedDwellSeconds(Float(value)))
        self.scanDwellTimeSeconds = clamped
        Task { _ = try? await settingsFacade.updateScanDwellTimeSeconds(seconds: Float(clamped)) }
    }

    func setScanAutoAdvanceSeconds(_ value: Double) {
        let clamped = Double(clampedAutoAdvanceSeconds(Float(value)))
        self.scanAutoAdvanceSeconds = clamped
        Task { _ = try? await settingsFacade.updateScanAutoAdvanceSeconds(seconds: Float(clamped)) }
    }

    func setShowButtonLabels(_ enabled: Bool) {
        showButtonLabels = enabled
        Task { _ = try? await settingsFacade.updateShowLabels(enabled: enabled) }
    }

    func setShowButtonSymbols(_ enabled: Bool) {
        showButtonSymbols = enabled
        Task { _ = try? await settingsFacade.updateShowSymbols(enabled: enabled) }
    }

    func setLabelAtTop(_ enabled: Bool) {
        labelAtTop = enabled
        Task { _ = try? await settingsFacade.updateLabelAtTop(enabled: enabled) }
    }

    func setPreferredGridColumns(_ columns: Int) {
        preferredGridColumns = min(max(columns, 1), 6)
        Task { _ = try? await settingsFacade.updateGridColumns(columns: Int32(preferredGridColumns)) }
    }

    func setHighContrastMode(_ enabled: Bool) {
        highContrastMode = enabled
        Task { _ = try? await settingsFacade.updateHighContrastMode(enabled: enabled) }
    }

    func setWordTypeColorsEnabled(_ enabled: Bool) {
        wordTypeColorScheme = enabled ? "Fitzgerald" : "None"
        Task {
            _ = try? await settingsFacade.updateWordTypeColorScheme(scheme: wordTypeColorScheme)
            await refreshBoardCells()
        }
    }

    func setHoldToSelectMillis(_ value: Double) {
        holdToSelectMillis = min(max(value, 0), 2_000)
        Task { _ = try? await settingsFacade.updateHoldToSelectMillis(millis: Int64(holdToSelectMillis)) }
    }

    func setDwellToSelectMillis(_ value: Double) {
        dwellToSelectMillis = min(max(value, 0), 5_000)
        Task { _ = try? await settingsFacade.updateDwellToSelectMillis(millis: Int64(dwellToSelectMillis)) }
    }

    func setSelectKeyBinding(_ value: String) {
        selectKeyBinding = value
        Task { _ = try? await settingsFacade.updateSelectKeyBinding(binding: value) }
    }

    func setRestModeKeyBinding(_ value: String) {
        restModeKeyBinding = value
        Task { _ = try? await settingsFacade.updateRestModeKeyBinding(binding: value) }
    }

    func setPointerEmphasis(style: String? = nil, scale: Double? = nil) {
        if let style { pointerEmphasisStyle = style }
        if let scale { pointerEmphasisScale = min(max(scale, 1), 3) }
        Task { _ = try? await settingsFacade.updatePointerEmphasis(style: pointerEmphasisStyle, scale: Float(pointerEmphasisScale)) }
    }

    func registerAccessTarget(_ targetId: String, action: @escaping () -> Void) {
        accessActions[targetId] = action
    }

    func unregisterAccessTarget(_ targetId: String) {
        accessActions.removeValue(forKey: targetId)
        applyAccessResult(bridge.accessInputExit(targetId: targetId))
        applyAccessResult(bridge.accessInputBlur(targetId: targetId))
    }

    func accessEnter(_ targetId: String) { applyAccessResult(bridge.accessInputEnter(targetId: targetId)) }
    func accessExit(_ targetId: String) { applyAccessResult(bridge.accessInputExit(targetId: targetId)) }
    func accessFocus(_ targetId: String) { applyAccessResult(bridge.accessInputFocus(targetId: targetId)) }
    func accessBlur(_ targetId: String) { applyAccessResult(bridge.accessInputBlur(targetId: targetId)) }

    func accessKey(_ key: String, isDown: Bool) -> Bool {
        let normalized = key == " " ? "Space" : key
        guard normalized.caseInsensitiveCompare(selectKeyBinding) == .orderedSame ||
                normalized.caseInsensitiveCompare(restModeKeyBinding) == .orderedSame else { return false }
        let result = isDown
            ? bridge.accessInputKeyDown(key: normalized, selectBinding: selectKeyBinding, restBinding: restModeKeyBinding)
            : bridge.accessInputKeyUp(key: normalized)
        applyAccessResult(result)
        return true
    }

    func tickAccessInput() { applyAccessResult(bridge.accessInputTick(dwellMillis: Int64(dwellToSelectMillis))) }
    func toggleInputPause() { applyAccessResult(bridge.accessInputTogglePause()) }

    private func applyAccessResult(_ result: IosAccessInputResult) {
        if inputIsPaused != result.isPaused { inputIsPaused = result.isPaused }
        if accessTargetId != result.currentTargetId { accessTargetId = result.currentTargetId }
        let newProgress = Double(result.dwellProgress)
        if abs(accessDwellProgress - newProgress) > 0.001 { accessDwellProgress = newProgress }
        if let target = result.activationTargetId { accessActions[target]?() }
    }

    func setSelectionDebounceMillis(_ value: Double) {
        selectionDebounceMillis = min(max(value, 0), 1_000)
        Task { _ = try? await settingsFacade.updateSelectionDebounceMillis(millis: Int64(selectionDebounceMillis)) }
    }

    func setSelectionSoundEnabled(_ enabled: Bool) {
        selectionSoundEnabled = enabled
        Task { _ = try? await settingsFacade.updateSelectionSoundEnabled(enabled: enabled) }
    }

    func setAuditoryFishingEnabled(_ enabled: Bool) {
        auditoryFishingEnabled = enabled
        Task { _ = try? await settingsFacade.updateAuditoryFishingEnabled(enabled: enabled) }
    }

    func setSpeechPolicy(_ policy: String) {
        guard policy == "Immediate" || policy == "SentenceOnly" else { return }
        speechPolicy = policy
        Task { _ = try? await settingsFacade.updateSpeechPolicy(policy: policy) }
    }

    /// Whether a single board/button selection speaks immediately, honoring the
    /// global speech policy and the resolved board activation behavior.
    var shouldSpeakSelectionImmediately: Bool {
        settingsFacade.speechPolicySpeaksSelection(policy: speechPolicy, behavior: boardActivationBehavior)
    }

    func setBoardShowMessageBar(_ enabled: Bool) {
        boardShowMessageBar = enabled
        Task { _ = try? await settingsFacade.updateBoardShowMessageBar(enabled: enabled) }
    }

    func setUsageLoggingEnabled(_ enabled: Bool) {
        usageLoggingEnabled = enabled
        Task { _ = try? await settingsFacade.updateUsageLoggingEnabled(enabled: enabled) }
    }

    func setHistoryVisible(_ visible: Bool) {
        historyVisible = visible
        if visible {
            Task { await loadHistory() }
        } else {
            historyPhrases = []
        }
        Task { _ = try? await settingsFacade.updateHistoryVisible(visible: visible) }
    }

    func setFeatureUsageReportingEnabled(_ enabled: Bool) {
        featureUsageReportingEnabled = enabled
        Task { _ = try? await settingsFacade.updateFeatureUsageReportingEnabled(enabled: enabled) }
    }

    func setStartupUsesScreens(_ enabled: Bool) {
        startupUsesScreens = enabled
        Task { _ = try? await settingsFacade.updateStartupUsesScreens(enabled: enabled) }
    }

    func setStartupBoardSetId(_ id: String?) {
        startupBoardSetId = id
        Task { _ = try? await settingsFacade.updateStartupBoardSetId(id: id) }
    }

    private func normalizedScanGridOrder(_ value: String) -> String {
        switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "column-major":
            return "column-major"
        case "linear":
            return "linear"
        default:
            return "row-major"
        }
    }

    private func clampedDwellSeconds(_ value: Float) -> Float {
        value.clamped(to: 0.3...2.0)
    }

    private func clampedAutoAdvanceSeconds(_ value: Float) -> Float {
        value.clamped(to: 0.5...3.0)
    }

    // MARK: - Recording path (persisted in shared Phrase)
    func recordingPath(for phraseId: String) -> String? {
        state.phrases.first(where: { $0.id == phraseId })?.recordingPath
    }
    func setRecordingPath(_ path: String?, for phraseId: String) {
        communicationFacade.updatePhraseRecording(phraseId: phraseId, recordingPath: path)
    }

    func chooseVoice(_ v: Shared.Voice) async {
        do {
            try await speechFacade.selectVoiceAndMaybeUpdatePrimary(voice: v)
            await MainActor.run {
                self.selectedVoice = v
                if let langs = v.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
                self.primaryLanguage = effectiveLanguage(for: v)
            }
            let persisted = try? await speechFacade.selectedVoice()
            if let pv = persisted {
                self.selectedVoice = pv
                if let langs = pv.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
                self.primaryLanguage = effectiveLanguage(for: pv)
                #if DEBUG
                let name = (pv.displayName ?? pv.name) ?? "—"
                let lang = effectiveLanguage(for: pv)
                print("DEBUG: speechFacade.selectedVoice() => \(name) [\(lang)]")
                #endif
            } else {
                #if DEBUG
                print("DEBUG: speechFacade.selectedVoice() => (none)")
                #endif
            }
        } catch {
            // swallow for now
        }
    }

    func updateLanguage(_ lang: String) {
        Task {
            _ = try? await speechFacade.updateSelectedVoiceLanguage(lang: lang)
            self.primaryLanguage = lang
            if lang == self.secondaryLanguage {
                self.secondaryLanguageRanges = []
            }
            refreshVoiceAndLanguages()
        }
    }

    func updateSecondaryLanguage(_ lang: String) {
        Task {
            _ = try? await settingsFacade.updateSecondaryLanguage(lang: lang)
            self.secondaryLanguage = lang
            if lang == self.primaryLanguage {
                self.secondaryLanguageRanges = []
            }
        }
    }

    func markSelectionAsSecondaryLanguage(range: NSRange) {
        let locale = secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !locale.isEmpty, locale != primaryLanguage else { return }

        let currentText = input as NSString
        guard range.location != NSNotFound,
              range.length > 0,
              range.location + range.length <= currentText.length else { return }

        secondaryLanguageRanges = ranges(from: bridge.addTextSpan(
            spans: textSpans(from: secondaryLanguageRanges),
            span: Shared.TextSpan(start: Int32(range.location), endExclusive: Int32(range.location + range.length)),
            textLength: Int32(currentText.length)
        ))
    }

    func adjustSecondaryLanguageRangesAfterEdit(range: NSRange, replacementText: String) {
        guard !secondaryLanguageRanges.isEmpty else { return }
        let currentLength = (input as NSString).length
        let replacementLength = (replacementText as NSString).length
        secondaryLanguageRanges = ranges(from: bridge.adjustTextSpansForReplacement(
            textLength: Int32(currentLength),
            edit: Shared.TextSpan(start: Int32(range.location), endExclusive: Int32(range.location + range.length)),
            replacementLength: Int32(replacementLength),
            spans: textSpans(from: secondaryLanguageRanges)
        ))
    }

    private func mergeRanges(_ inputRanges: [NSRange], maxLength: Int) -> [NSRange] {
        ranges(from: bridge.mergeTextSpans(
            spans: textSpans(from: inputRanges),
            textLength: Int32(maxLength)
        ))
    }

    private func textSpans(from ranges: [NSRange]) -> [Shared.TextSpan] {
        ranges.compactMap { range in
            guard range.location != NSNotFound else { return nil }
            return Shared.TextSpan(
                start: Int32(range.location),
                endExclusive: Int32(range.location + range.length)
            )
        }
    }

    private func ranges(from spans: [Shared.TextSpan]) -> [NSRange] {
        spans.map { span in
            let start = Int(span.start)
            return NSRange(location: start, length: max(0, Int(span.endExclusive) - start))
        }
    }

    private func makeCurrentInputSnapshot() -> InputSnapshot {
        let length = (input as NSString).length
        let selection = clampedSelectionRange(inputSelectionRange, maxLength: length)
        let secondary = mergeRanges(secondaryLanguageRanges, maxLength: length)
        return InputSnapshot(text: input, selection: selection, secondaryRanges: secondary)
    }

    private func applyInputSnapshot(_ snapshot: InputSnapshot) {
        input = snapshot.text
        onInputChanged(snapshot.text)

        let length = (snapshot.text as NSString).length
        inputSelectionRange = clampedSelectionRange(snapshot.selection, maxLength: length)
        secondaryLanguageRanges = mergeRanges(snapshot.secondaryRanges, maxLength: length)
    }

    private func clampedSelectionRange(_ range: NSRange, maxLength: Int) -> NSRange {
        if range.location == NSNotFound {
            return NSRange(location: maxLength, length: 0)
        }
        let safeLocation = min(max(0, range.location), maxLength)
        let safeLength = min(max(0, range.length), max(0, maxLength - safeLocation))
        return NSRange(location: safeLocation, length: safeLength)
    }

    func refreshVoiceAndLanguages() {
        Task {
            let v = try? await speechFacade.selectedVoice()
            self.selectedVoice = v
            if let langs = v?.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
            if let v = v { self.primaryLanguage = effectiveLanguage(for: v) }
        }
    }

    func deleteCategory(id: String) {
        store?.accept(intent: Shared.PhraseListStoreIntent.DeleteCategory(categoryId: id))
    }

    func updatePhrase(id: String, text: String?, name: String?, imageUrl: String? = nil) {
        let normalizedImageUrl = imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        store?.accept(intent: Shared.PhraseListStoreIntent.UpdatePhrase(id: id, text: text, name: name, imageUrl: normalizedImageUrl))
    }

    func movePhrase(from: Int, to: Int) {
        store?.accept(intent: Shared.PhraseListStoreIntent.MovePhrase(fromIndex: Int32(from), toIndex: Int32(to)))
    }

    // MARK: - Add category / phrase
    func addCategory(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store?.accept(intent: Shared.PhraseListStoreIntent.AddCategory(name: trimmed))
    }

    func addPhrase(text: String, alternativeText: String? = nil, imageUrl: String? = nil, recordingPath: String? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let normalizedAlternative = alternativeText?.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalAlternative = (normalizedAlternative?.isEmpty == false) ? normalizedAlternative : nil
        let normalizedImageUrl = imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalImageUrl = (normalizedImageUrl?.isEmpty == false) ? normalizedImageUrl : nil
        let normalizedRecordingPath = recordingPath?.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalRecordingPath = (normalizedRecordingPath?.isEmpty == false) ? normalizedRecordingPath : nil
        store?.accept(intent: Shared.PhraseListStoreIntent.AddPhrase(text: trimmed, name: finalAlternative, imageUrl: finalImageUrl, recordingPath: finalRecordingPath))
        #if DEBUG
        // Incremental learning for development builds with predictions enabled.
        Task { _ = try? await bridge.learnPhrase(text: trimmed) }
        #endif
    }
    
    // MARK: - Prediction
    func onInputChanged(_ newValue: String, preserveSecondaryRanges: Bool = false) {
        input = newValue

        if !isApplyingSentencePhraseInput && !sentencePhrases.isEmpty {
            let normalizedInput = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            let normalizedSentence = sentencePhrases
                .map { $0.text }
                .joined(separator: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if normalizedInput != normalizedSentence {
                sentencePhrases = []
            }
        }

        #if DEBUG
        predictionJob?.cancel()
        predictionJob = Task {
            // Keep native text entry responsive. The prediction bridge can be
            // relatively expensive, so only query after the user pauses typing.
            try? await Task.sleep(nanoseconds: 250_000_000)
            if Task.isCancelled { return }

            // Skip inference on short/blank tokens to avoid clearing or churning
            // the bar while typing.
            let lastTokenLength = (newValue.split(separator: " ").last?.count) ?? 0
            let shouldPredict = !newValue.isEmpty &&
                (newValue.last == " " || lastTokenLength >= 2)
            let res: Shared.PredictionResult
            if newValue.isEmpty {
                res = Shared.PredictionResult(words: [], letters: [])
            } else if shouldPredict,
                      let bridgePrediction = (try? await bridge.predict(context: newValue, maxWords: 5, maxLetters: 5)) {
                res = bridgePrediction
            } else {
                res = self.predictions
            }
            await MainActor.run { self.predictions = res }
        }
        #endif
    }

    #if DEBUG
    func applyWordPrediction(_ word: String) {
        let result = bridge.completePredictedWord(
            text: input,
            cursor: Int32(inputSelectionRange.location),
            suggestion: word
        )
        input = result.text
        inputSelectionRange = NSRange(location: Int(result.cursor), length: 0)
        onInputChanged(input)
    }
    
    func applyLetterPrediction(_ char: String) {
        let result = bridge.insertPredictedText(
            text: input,
            cursor: Int32(inputSelectionRange.location),
            value: char
        )
        input = result.text
        inputSelectionRange = NSRange(location: Int(result.cursor), length: 0)
        onInputChanged(input)
    }
    #endif

    private func hasAudioPath(_ path: String?) -> Bool {
        guard let path = path else { return false }
        return !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func resolveLastAudioPath() -> String? {
        let normalizedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)

        // 1) Prefer an exact match to current input if available.
        if !normalizedInput.isEmpty,
           let byText = historyPhrases.first(where: {
               let t = ($0.text).trimmingCharacters(in: .whitespacesAndNewlines)
               let n = ($0.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
               return (t == normalizedInput || n == normalizedInput) && hasAudioPath($0.recordingPath)
           }),
           let path = byText.recordingPath {
            return path
        }

        // 2) Fall back to the most recent history entry with audio.
        if let fromHistory = historyPhrases.first(where: { hasAudioPath($0.recordingPath) }),
           let path = fromHistory.recordingPath {
            return path
        }

        // 3) Last fallback: any stored phrase recording.
        if let fromPhrases = state.phrases.first(where: { hasAudioPath($0.recordingPath) }),
           let path = fromPhrases.recordingPath {
            return path
        }

        return nil
    }

    var hasShareableAudio: Bool {
        resolveLastAudioPath() != nil
    }
    
    // MARK: - Sharing
    func shareLastAudio() {
        guard let path = resolveLastAudioPath() else { return }
        bridge.shareAudio(path: path)
    }

    func copyLastAudio() {
        guard let path = resolveLastAudioPath() else { return }
        // Fallback to share to avoid framework symbol mismatch when copyAudio is not exported in current build.
        bridge.shareAudio(path: path)
    }
    
    // MARK: - Pronunciations
    func loadPronunciations() async {
        do {
            let items = try await bridge.listPronunciations()
            await MainActor.run { self.pronunciations = items }
        } catch {
            await MainActor.run { self.pronunciations = [] }
        }
    }
    
    func addPronunciation(word: String, phoneme: String, alphabet: String) {
        Task {
            try? await bridge.addPronunciation(word: word, phoneme: phoneme, alphabet: alphabet)
            await loadPronunciations()
        }
    }
    
    func deletePronunciation(word: String) {
        Task {
            try? await bridge.deletePronunciation(word: word)
            await loadPronunciations()
        }
    }

    // MARK: - Boardsets (Symbol-First)
    private func boardSetInfo(from set: Shared.ObfBoardSet) -> BoardSetInfo {
        BoardSetInfo(
            id: set.id,
            name: set.name,
            rootBoardId: set.rootBoardId,
            boardIds: set.boardIds,
            isLocked: set.isLocked,
            cacheWholeSentences: set.cacheWholeSentences,
            updatedAt: TimeInterval(set.updatedAt) / 1_000
        )
    }

    private func updateBoardSet(_ updated: BoardSetInfo) {
        guard let idx = boardSets.firstIndex(where: { $0.id == updated.id }) else { return }
        boardSets[idx] = updated
    }

    func setBoardSetSentenceCaching(id: String, enabled: Bool) {
        guard var set = boardSets.first(where: { $0.id == id }) else { return }
        set.cacheWholeSentences = enabled
        updateBoardSet(set)
        Task {
            if let updated = try? await boardsFacade.updateBoardSetSentenceCaching(id: id, enabled: enabled) {
                updateBoardSet(boardSetInfo(from: updated))
            }
        }
    }

    private func normalizedBoardsetName(_ input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? NSLocalizedString("boardset.default_name", comment: "") : trimmed
    }

    private func normalizedOptionalText(_ input: String?) -> String? {
        let trimmed = (input ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func touchSelectedBoardSet(statusKey: String) {
        if var set = selectedBoardSet {
            set.updatedAt = Date().timeIntervalSince1970
            updateBoardSet(set)
        }
        boardStatusMessage = NSLocalizedString(statusKey, comment: "")
    }

    private func refreshBoardNames(for set: BoardSetInfo?) async {
        guard let set else {
            boardNamesById = [:]
            return
        }

        var cache = boardNamesById
        for boardId in set.boardIds {
            if let selectedBoard, selectedBoard.id == boardId {
                let selectedName = selectedBoard.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !selectedName.isEmpty {
                    cache[boardId] = selectedName
                }
                continue
            }

            do {
                if let board = try await boardsFacade.getBoard(id: boardId) {
                    let name = board.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    if !name.isEmpty {
                        cache[boardId] = name
                    }
                }
            } catch {
                // Keep existing cache value if lookup fails.
            }
        }

        boardNamesById = cache
    }

    func loadBoardSets() async {
        do {
            let sharedSets = try await boardsFacade.listBoardSets()
            boardSets = sharedSets
                .map { boardSetInfo(from: $0) }
                .sorted { $0.updatedAt > $1.updatedAt }
        } catch {
            boardSets = []
            boardStatusMessage = NSLocalizedString("board_sets_load_error", comment: "")
        }

        if selectedBoardSetId == nil || !boardSets.contains(where: { $0.id == selectedBoardSetId }) {
            selectedBoardSetId = boardSets.first?.id
        }

        if let set = selectedBoardSet {
            if selectedBoardId == nil || !set.boardIds.contains(selectedBoardId ?? "") {
                selectedBoardId = set.rootBoardId
            }
            await loadSelectedBoard()
            await refreshBoardNames(for: set)
        } else {
            selectedBoardId = nil
            selectedBoard = nil
            boardCells = []
            boardFieldItems = []
            boardNamesById = [:]
        }
    }

    func createBoardSet(name: String, rows: Int, columns: Int) async {
        isCreatingBoardSet = true
        defer { isCreatingBoardSet = false }
        let boardsetName = normalizedBoardsetName(name)
        let safeRows = min(max(rows, 1), 12)
        let safeColumns = min(max(columns, 1), 12)

        do {
            let sharedSet = try await boardsFacade.createBoardSet(
                name: boardsetName,
                rows: Int32(safeRows),
                columns: Int32(safeColumns)
            )
            let set = boardSetInfo(from: sharedSet)
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = set.rootBoardId
            await loadSelectedBoard()
            boardStatusMessage = NSLocalizedString("boardset.status.created", comment: "")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
        }
    }

    func createKeyboardBoardSet(name: String, preset: String) async {
        isCreatingBoardSet = true
        defer { isCreatingBoardSet = false }
        let boardsetName = normalizedBoardsetName(name)
        do {
            let sharedSet = try await boardsFacade.createKeyboardBoardSet(name: boardsetName, preset: preset)
            let set = boardSetInfo(from: sharedSet)
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = set.rootBoardId
            await loadSelectedBoard()
            boardStatusMessage = NSLocalizedString("boardset.status.created", comment: "")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
        }
    }

    func importQuickCorePreset(name: String, slug: String) async {
        isCreatingBoardSet = true
        defer { isCreatingBoardSet = false }
        isImportingQuickCore = true
        quickCoreDownloadProgress = 0
        let monitor = Task { @MainActor in
            while !Task.isCancelled {
                let progress = boardsFacade.quickCoreProgress()
                quickCoreDownloadProgress = progress.fraction?.doubleValue
                try? await Task.sleep(nanoseconds: 150_000_000)
            }
        }
        defer {
            monitor.cancel()
            isImportingQuickCore = false
        }
        do {
            guard let sharedSet = try await boardsFacade.importQuickCorePreset(slug: slug, name: normalizedBoardsetName(name)) else {
                boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
                return
            }
            let set = boardSetInfo(from: sharedSet)
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = set.rootBoardId
            await loadSelectedBoard()
            quickCoreDownloadProgress = 1
            boardStatusMessage = NSLocalizedString("boardset.status.created", comment: "")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
        }
    }

    func addBoardToSelectedSet(name: String, rows: Int, columns: Int) async {
        guard let set = selectedBoardSet else { return }
        guard !set.isLocked else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }

        let boardName = normalizedBoardsetName(name)
        let safeRows = min(max(rows, 1), 12)
        let safeColumns = min(max(columns, 1), 12)

        do {
            guard let board = try await boardsFacade.createBoard(
                boardSetId: set.id,
                name: boardName,
                rows: Int32(safeRows),
                columns: Int32(safeColumns)
            ) else {
                boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
                return
            }

            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = board.id
            selectedBoard = board
            await refreshBoardCells()
            boardStatusMessage = NSLocalizedString("boardset.status.board_added", comment: "")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
        }
    }

    func addKeyboardBoardToSelectedSet(name: String, rows: Int, columns: Int, layout: String) async {
        guard let set = selectedBoardSet, !set.isLocked else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }
        do {
            guard let board = try await boardsFacade.createKeyboardBoard(
                boardSetId: set.id,
                name: normalizedBoardsetName(name),
                rows: Int32(min(max(rows, 1), 12)),
                columns: Int32(min(max(columns, 1), 12)),
                layout: layout
            ) else {
                boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
                return
            }
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = board.id
            selectedBoard = board
            await refreshSelectedBoardMetadata()
            await refreshBoardCells()
            boardStatusMessage = NSLocalizedString("boardset.status.board_added", comment: "")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
        }
    }

    func selectBoardSet(id: String) async {
        guard boardSets.contains(where: { $0.id == id }) else { return }
        selectedBoardSetId = id
        boardStack = []
        if let set = selectedBoardSet {
            if selectedBoardId == nil || !set.boardIds.contains(selectedBoardId ?? "") {
                selectedBoardId = set.rootBoardId
            }
            await loadSelectedBoard()
            await refreshBoardNames(for: set)
        }
    }

    func selectBoard(id: String) async {
        if let set = selectedBoardSet, !set.boardIds.contains(id) {
            return
        }
        selectedBoardId = id
        await loadSelectedBoard()
    }

    func pushBoardNavigationStack(_ boardId: String) {
        guard !boardStack.contains(boardId) else { return }
        boardStack.append(boardId)
    }

    func applyBoardReturnBehavior() async {
        let behavior = boardReturnBehavior
        let result = boardsFacade.boardReturnBehavior(
            behavior: behavior,
            currentBoardId: selectedBoardId,
            boardStack: boardStack,
            rootBoardId: selectedBoardSet?.rootBoardId ?? ""
        )
        let nextBoardId = result.boardId
        let nextStack = result.boardStack
        boardStack = nextStack
        guard let nextBoardId, nextBoardId != selectedBoardId else { return }
        selectedBoardId = nextBoardId
        await loadSelectedBoard()
    }

    func nGramPredictionInsertion(sentence: String, suggestion: String) -> String {
        boardsFacade.nGramPredictionInsertion(sentence: sentence, suggestion: suggestion)
    }

    func boardBackspaceSentence(texts: [String], spellingMode: Bool) -> [String] {
        boardsFacade.boardBackspaceSentence(texts: texts, spellingMode: spellingMode)
    }

    func boardButtonIsVisible(hidden: Bool, isEditMode: Bool, showHiddenButtons: Bool) -> Bool {
        boardsFacade.boardButtonIsVisible(hidden: hidden, isEditMode: isEditMode, showHiddenButtons: showHiddenButtons)
    }

    func boardFieldFontScale(rowSpan: Int, columnSpan: Int) -> CGFloat {
        CGFloat(boardsFacade.boardFieldFontScale(rowSpan: Int32(rowSpan), columnSpan: Int32(columnSpan)))
    }

    func boardJoinSentenceText(tokens: [String], spellingMode: Bool) -> String {
        boardsFacade.boardJoinSentenceText(tokens: tokens, spellingMode: spellingMode)
    }

    func loadSelectedBoard() async {
        guard let id = selectedBoardId else {
            selectedBoard = nil
            boardCells = []
            boardFieldItems = []
            selectedBoardKeyboardLayout = nil
            selectedBoardUsesSpellingMode = false
            boardPredictionsByButtonId = [:]
            resolvedBoardShowLabels = nil
            resolvedBoardShowSymbols = nil
            resolvedBoardLabelAtTop = nil
            resolvedBoardShowMessageBar = nil
            resolvedBoardActivationBehavior = nil
            resolvedBoardReturnBehavior = nil
            return
        }
        do {
            selectedBoard = try await boardsFacade.getBoard(id: id)
            await refreshSelectedBoardMetadata()
            let resolved = try? await boardsFacade.resolveBoardSettings(boardId: id)
            resolvedBoardShowLabels = resolved?.showLabels
            resolvedBoardShowSymbols = resolved?.showSymbols
            resolvedBoardLabelAtTop = resolved?.labelAtTop
            resolvedBoardShowMessageBar = resolved?.showMessageBar
            resolvedBoardActivationBehavior = resolved?.activationBehavior
            resolvedBoardReturnBehavior = resolved?.returnBehavior
            let boardName = selectedBoard?.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !boardName.isEmpty {
                boardNamesById[id] = boardName
            }
            await refreshBoardCells()
        } catch {
            selectedBoard = nil
            boardCells = []
            boardFieldItems = []
            selectedBoardKeyboardLayout = nil
            selectedBoardUsesSpellingMode = false
            boardPredictionsByButtonId = [:]
            resolvedBoardShowLabels = nil
            resolvedBoardShowSymbols = nil
            resolvedBoardLabelAtTop = nil
            resolvedBoardShowMessageBar = nil
            resolvedBoardActivationBehavior = nil
            resolvedBoardReturnBehavior = nil
        }
    }

    private func refreshSelectedBoardMetadata() async {
        guard let board = selectedBoard else {
            selectedBoardKeyboardLayout = nil
            selectedBoardUsesSpellingMode = false
            return
        }
        selectedBoardKeyboardLayout = boardsFacade.boardKeyboardLayout(board: board)
        selectedBoardUsesSpellingMode = boardsFacade.boardUsesSpellingMode(board: board)
        if selectedBoardKeyboardLayout != nil {
            _ = try? await bridge.trainPredictionModel()
        }
    }

    func refreshBoardCells() async {
        guard let boardId = selectedBoardId else {
            boardCells = []
            boardFieldItems = []
            return
        }

        do {
            let cells = try await boardsFacade.listBoardCells(boardId: boardId)
            let fields = try await boardsFacade.listBoardFieldItems(boardId: boardId)
            boardCells = cells.map { cell in
                BoardCellInfo(
                    row: Int(cell.row),
                    col: Int(cell.col),
                    buttonId: cell.buttonId,
                    label: cell.label,
                    vocalization: cell.vocalization,
                    backgroundColor: cell.backgroundColor,
                    resolvedBackgroundColor: cell.resolvedBackgroundColor,
                    wordType: cell.wordType,
                    borderColor: cell.borderColor,
                    linkedBoardId: cell.linkedBoardId,
                    imageId: cell.imageId,
                    imageUrl: cell.imageUrl,
                    hidden: cell.hidden,
                    actions: cell.actions,
                    soundId: cell.soundId,
                    soundDataUrl: cell.soundDataUrl,
                    shape: cell.shape
                )
            }
            boardFieldItems = fields.map { field in
                BoardFieldItem(
                    row: Int(field.row),
                    column: Int(field.column),
                    rowSpan: Int(field.rowSpan),
                    columnSpan: Int(field.columnSpan),
                    buttonId: field.buttonId
                )
            }
        } catch {
            boardCells = []
            boardFieldItems = []
        }
    }

    func upsertSelectedBoardCell(
        row: Int,
        col: Int,
        label: String?,
        vocalization: String?,
        backgroundColor: String?,
        borderColor: String?,
        linkedBoardId: String?,
        imageUrl: String?,
        clearImage: Bool,
        actions: [String],
        wordType: String?
    ) async {
        guard let boardId = selectedBoardId else {
            boardStatusMessage = NSLocalizedString("boardset.error.no_board", comment: "")
            return
        }
        guard canEditSelectedBoardSet else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }

        let normalizedLabel = normalizedOptionalText(label)
        let normalizedVocalization = normalizedOptionalText(vocalization)
        let normalizedBackground = normalizedOptionalText(backgroundColor)
        let normalizedBorder = normalizedOptionalText(borderColor)
        let normalizedLink = normalizedOptionalText(linkedBoardId)
        let normalizedImageUrl = normalizedOptionalText(imageUrl)

        do {
            guard let updatedBoard = try await boardsFacade.upsertBoardCellButton(
                boardId: boardId,
                row: Int32(row),
                col: Int32(col),
                label: normalizedLabel,
                vocalization: normalizedVocalization,
                backgroundColor: normalizedBackground,
                borderColor: normalizedBorder,
                linkedBoardId: normalizedLink,
                imageUrl: normalizedImageUrl,
                clearImage: clearImage,
                actions: actions,
                wordType: normalizedOptionalText(wordType)
            ) else {
                boardStatusMessage = NSLocalizedString("boardset.error.cell_update_failed", comment: "")
                return
            }

            selectedBoard = updatedBoard
            await refreshBoardCells()
            if let setId = selectedBoardSetId { _ = try? await boardsFacade.touchBoardSet(id: setId) }
            touchSelectedBoardSet(statusKey: "boardset.status.cell_saved")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.cell_update_failed", comment: "")
        }
    }

    func clearSelectedBoardCell(row: Int, col: Int) async {
        guard let boardId = selectedBoardId else {
            boardStatusMessage = NSLocalizedString("boardset.error.no_board", comment: "")
            return
        }
        guard canEditSelectedBoardSet else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }

        do {
            guard let updatedBoard = try await boardsFacade.clearBoardCellButton(
                boardId: boardId,
                row: Int32(row),
                col: Int32(col)
            ) else {
                boardStatusMessage = NSLocalizedString("boardset.error.cell_clear_failed", comment: "")
                return
            }

            selectedBoard = updatedBoard
            await refreshBoardCells()
            if let setId = selectedBoardSetId { _ = try? await boardsFacade.touchBoardSet(id: setId) }
            touchSelectedBoardSet(statusKey: "boardset.status.cell_cleared")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.cell_clear_failed", comment: "")
        }
    }

    func activateSelectedBoardCell(row: Int, col: Int) async {
        guard let cell = cellAt(row: row, col: col) else { return }

        if let linkedBoardId = normalizedOptionalText(cell.linkedBoardId),
           let set = selectedBoardSet,
           set.boardIds.contains(linkedBoardId) {
            await selectBoard(id: linkedBoardId)
            return
        }

        // #118: navigation is an explicit action; speech insertion is debounced per cell.
        if !acceptActivation(targetId: cell.buttonId) { return }

        if let textToSpeak = normalizedOptionalText(cell.vocalization) ?? normalizedOptionalText(cell.label) {
            speak(textToSpeak)
        }
    }

    func activateBoardSelectionHighlight(buttonId: String) async {
        guard selectionHighlightMillis > 0 else { return }
        bridge.selectionHighlightActivate(buttonId: buttonId)
        selectionHighlightGeneration += 1
        let generation = selectionHighlightGeneration
        highlightedButtonId = buttonId
        let duration = selectionHighlightMillis
        try? await Task.sleep(nanoseconds: UInt64(duration) * 1_000_000)
        guard generation == selectionHighlightGeneration else { return }
        let current = bridge.selectionHighlightButtonId(durationMillis: duration)
        highlightedButtonId = current
    }

    func clearBoardSelectionHighlight() {
        selectionHighlightGeneration += 1
        bridge.selectionHighlightClear()
        highlightedButtonId = nil
    }

    // #118: per-target activation debounce. A zero duration disables the guard entirely.
    private var lastActivationAtMillis: [String: Int64] = [:]
    private func acceptActivation(targetId: String) -> Bool {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let window = Int64(selectionDebounceMillis)
        if window <= 0 {
            lastActivationAtMillis[targetId] = nil
            return true
        }
        if let last = lastActivationAtMillis[targetId], now - last < window {
            return false
        }
        lastActivationAtMillis[targetId] = now
        return true
    }

    func refreshBoardPredictions(context: String) async {
        var seenIds = Set<String>()
        let predictorIds = boardCells
            .filter { cell in cell.actions.contains { $0.lowercased() == ":prediction" || $0.lowercased() == ":predictions" } }
            .map(\.buttonId)
            .filter { seenIds.insert($0).inserted }
        guard !predictorIds.isEmpty else {
            boardPredictionsByButtonId = [:]
            return
        }
        let result = (try? await bridge.predict(
            context: context,
            maxWords: Int32(predictorIds.count),
            maxLetters: 0
        )) ?? Shared.PredictionResult(words: [], letters: [])
        boardPredictionsByButtonId = Dictionary(
            uniqueKeysWithValues: zip(predictorIds, result.words).map { ($0.0, $0.1) }
        )
    }

    func boardPrediction(for buttonId: String) -> String? {
        boardPredictionsByButtonId[buttonId]
    }

    func saveSelectedBoardSet() async {
        guard let board = selectedBoard else {
            boardStatusMessage = NSLocalizedString("boardset.error.no_board", comment: "")
            return
        }
        guard !selectedBoardSetLocked else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }

        do {
            let saved = try await boardsFacade.saveBoard(board: board)
            if saved.boolValue {
                if let setId = selectedBoardSetId { _ = try? await boardsFacade.touchBoardSet(id: setId) }
                touchSelectedBoardSet(statusKey: "boardset.status.saved")
            } else {
                boardStatusMessage = NSLocalizedString("boardset.error.save_failed", comment: "")
            }
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.save_failed", comment: "")
        }
    }

    func renameSelectedBoardSet(_ name: String) async {
        guard let set = selectedBoardSet, canEditSelectedBoardSet else { return }
        let normalized = normalizedBoardsetName(name)
        do {
            guard let updated = try await boardsFacade.renameBoardSet(boardSetId: set.id, name: normalized) else { return }
            await loadBoardSets()
            selectedBoardSetId = updated.id
            boardStatusMessage = NSLocalizedString("boardset.status.saved", comment: "")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.save_failed", comment: "")
        }
    }

    func renameSelectedBoard(_ name: String) async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId, canEditSelectedBoardSet else { return }
        let normalized = normalizedBoardsetName(name)
        do {
            guard let board = try await boardsFacade.renameBoard(boardSetId: set.id, boardId: boardId, name: normalized) else { return }
            selectedBoard = board
            boardNamesById[boardId] = normalized
            await loadBoardSets()
            selectedBoardSetId = set.id
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.save_failed", comment: "")
        }
    }

    func resizeSelectedBoard(rows: Int, columns: Int) async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId, canEditSelectedBoardSet else { return }
        do {
            guard let board = try await boardsFacade.resizeBoard(
                boardSetId: set.id,
                boardId: boardId,
                rows: Int32(min(max(rows, 1), 12)),
                columns: Int32(min(max(columns, 1), 12))
            ) else { return }
            selectedBoard = board
            await refreshBoardCells()
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = boardId
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.save_failed", comment: "")
        }
    }

    func setSelectedBoardBackgroundColor(_ color: String?) async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId, canEditSelectedBoardSet else { return }
        do {
            guard let board = try await boardsFacade.setBoardBackgroundColor(
                boardSetId: set.id,
                boardId: boardId,
                backgroundColor: normalizedOptionalText(color)
            ) else { return }
            selectedBoard = board
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = boardId
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.save_failed", comment: "")
        }
    }

    func makeSelectedBoardRoot() async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId, boardId != set.rootBoardId else { return }
        do {
            guard let updated = try await boardsFacade.setRootBoard(boardSetId: set.id, boardId: boardId) else { return }
            await loadBoardSets()
            selectedBoardSetId = updated.id
            selectedBoardId = boardId
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.save_failed", comment: "")
        }
    }

    func deleteSelectedBoard() async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId,
              boardId != set.rootBoardId, set.boardIds.count > 1 else { return }
        do {
            guard let updated = try await boardsFacade.deleteBoard(boardSetId: set.id, boardId: boardId) else { return }
            await loadBoardSets()
            selectedBoardSetId = updated.id
            selectedBoardId = updated.rootBoardId
            await loadSelectedBoard()
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.delete_failed", comment: "")
        }
    }

    func setSelectedBoardSetLocked(_ locked: Bool) {
        guard let set = selectedBoardSet, set.isLocked != locked else { return }
        Task {
            do {
                _ = try await boardsFacade.toggleBoardSetLocked(id: set.id)
                await loadBoardSets()
                selectedBoardSetId = set.id
                boardStatusMessage = locked
                    ? NSLocalizedString("boardset.status.locked", comment: "")
                    : NSLocalizedString("boardset.status.unlocked", comment: "")
            } catch {
                boardStatusMessage = NSLocalizedString("board_sets_lock_error", comment: "")
            }
        }
    }

    func deleteBoardSet(id: String) async {
        do {
            try await boardsFacade.deleteBoardSet(id: id)
            await loadBoardSets()
            if selectedBoardSetId == id {
                selectedBoardSetId = boardSets.first?.id
                if let set = selectedBoardSet {
                    selectedBoardId = set.rootBoardId
                    await loadSelectedBoard()
                } else {
                    selectedBoardId = nil
                    selectedBoard = nil
                    boardCells = []
                    boardFieldItems = []
                }
            }
            boardStatusMessage = NSLocalizedString("boardset.status.deleted", comment: "")
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.delete_failed", comment: "")
        }
    }

    func duplicateBoardSet(id: String) async {
        do {
            if let dup = try await boardsFacade.duplicateBoardSet(id: id) {
                let info = boardSetInfo(from: dup)
                await loadBoardSets()
                selectedBoardSetId = info.id
                selectedBoardId = info.rootBoardId
                await loadSelectedBoard()
                boardStatusMessage = NSLocalizedString("boardset.status.duplicated", comment: "")
            }
        } catch {
            boardStatusMessage = NSLocalizedString("boardset.error.duplicate_failed", comment: "")
        }
    }
}

private extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
