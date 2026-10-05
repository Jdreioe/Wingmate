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
    private final class StoreObserver: NSObject, Shared.RxObserver {
        private let onNextState: (Shared.PhraseListStoreState) -> Void
        private let onCompleteState: () -> Void
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
    private var buttonSoundPlayer: AVAudioPlayer?

    @Published var state: Shared.PhraseListStoreState = Shared.PhraseListStoreState(phrases: [], categories: [], selectedCategoryId: nil, isLoading: true, error: nil)

    // Bridge to shared KMP use-cases
    private let bridge = KoinBridge()
    private lazy var backupFacade = IosDiBridge().backupFacade()
    private lazy var speechFacade = IosDiBridge().speechFacade()
    private lazy var settingsFacade = IosDiBridge().settingsFacade()
    private lazy var boardsFacade = IosDiBridge().boardsFacade()
    private lazy var communicationFacade = IosDiBridge().communicationFacade()
    private lazy var session = IosDiBridge().communicationSessionFacade()
    private var sessionSubscription: Shared.NativeSubscription?

    // The shared Message (#306). Kotlin's Communication session owns it; these mirror its
    // state, and every edit goes through the session so Typing and Screens see one Message.
    @Published private(set) var input: String = ""
    var inputSelectionRange: NSRange = NSRange(location: 0, length: 0)
    @Published private(set) var messageParts: [Shared.MessagePart] = []
    @Published private(set) var heldMessageText: String? = nil
    @Published private(set) var playback: Shared.SessionPlayback = .idle
    @Published private(set) var sessionNotice: Shared.SessionNotice? = nil
    @Published private(set) var sessionIsSaving: Bool = false
    var hasHeldThought: Bool { heldMessageText != nil }
    @Published var primaryLanguage: String = "en-US"
    @Published var secondaryLanguage: String = "en-US"
    @Published private(set) var secondaryLanguageRanges: [NSRange] = []
    @Published var selectedVoice: Shared.Voice? = nil
    @Published var availableLanguages: [String] = []
    // Predictions
    #if DEBUG
    @Published var predictions: Shared.PredictionResult = Shared.PredictionResult(words: [], letters: [])
    private var predictionJob: Task<Void, Never>? = nil
    private var predictionSubscription: Shared.NativeSubscription?
    #endif
    private var boardPredictionSubscription: Shared.NativeSubscription?

    deinit {
        sessionSubscription?.cancel()
        boardPredictionSubscription?.cancel()
        #if DEBUG
        predictionJob?.cancel()
        predictionSubscription?.cancel()
        #endif
    }

    // History items exposed as phrases for UI rendering
    @Published var historyPhrases: [Shared.Phrase] = []
    // Special selection for History view
    let historyCategoryId = "__history__"

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
    @Published var startupUsesScreens: Bool = false
    @Published var startupBoardSetId: String? = nil

    // Pronunciation Dictionary
    @Published var pronunciations: [Shared.PronunciationEntry] = []

    // Azure availability (subscription configured)
    @Published var azureConfigured: Bool = false
    @Published var googleConfigured: Bool = false

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
    @Published var editingAccessEnabled: Bool = false
    @Published var editingAccessUnlocked: Bool = true
    @Published var editingAccessSupported: Bool = true
    @Published var selectionHighlightMillis: Int64 = 0
    @Published var highlightedButtonId: String? = nil
    private var selectionHighlightGeneration: Int64 = 0
    @Published var boardShowMessageBar: Bool = true
    @Published var boardShowSpeakButton: Bool = true
    @Published var resolvedBoardShowLabels: Bool? = nil
    @Published var resolvedBoardShowSymbols: Bool? = nil
    @Published var resolvedBoardLabelAtTop: Bool? = nil
    @Published var resolvedBoardShowMessageBar: Bool? = nil
    @Published var resolvedBoardShowSpeakButton: Bool? = nil
    @Published var resolvedBoardActivationBehavior: String? = nil
    @Published var resolvedBoardReturnBehavior: String? = nil
    @Published private(set) var boardStack: [String] = []

    var boardShowLabels: Bool { resolvedBoardShowLabels ?? showButtonLabels }
    var boardShowSymbols: Bool { resolvedBoardShowSymbols ?? showButtonSymbols }
    var boardLabelAtTop: Bool { resolvedBoardLabelAtTop ?? labelAtTop }
    var boardMessageBarVisible: Bool { resolvedBoardShowMessageBar ?? boardShowMessageBar }
    var boardSpeakButtonVisible: Bool { resolvedBoardShowSpeakButton ?? boardShowSpeakButton }
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
            try? await session.reloadAfterRestore()
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
        await MainActor.run { IosDiBridge().startKoinWithOverridesBridge(deviceSpeech: SystemTtsManager.shared) }
        sessionSubscription?.cancel()
        sessionSubscription = session.observe { [weak self] state in
            self?.applySessionState(state)
        }
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
            self.boardsFacade.updateBoardSetSpeechCacheOnline(online: online)
            if online {
                Task {
                    try? await self.boardsFacade.cacheAllBoardSetFields()
                    try? await self.boardsFacade.retryBoardSetSpeechCaching()
                }
            }
        }
    // Preload history once Koin is up
    await loadHistory()
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
                self.boardShowSpeakButton = settings.boardShowSpeakButton
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

    // MARK: - Message (shared Communication session)

    /// Mirrors the session. Runs for every change, including ones made by Kotlin
    /// (speech progress, restore) and by the other workspace.
    private func applySessionState(_ state: Shared.NativeCommunicationState) {
        let text = state.activeMessage.displayText
        let textChanged = input != text
        if textChanged { input = text }
        inputSelectionRange = clampedSelectionRange(inputSelectionRange, maxLength: (text as NSString).length)
        let secondary = secondaryLanguage
        let ranges = state.activeMessage.languageSpans
            .filter { $0.languageTag == secondary }
            .map { NSRange(location: Int($0.range.start), length: Int($0.range.length)) }
        if ranges != secondaryLanguageRanges { secondaryLanguageRanges = ranges }
        if messageParts != state.activeMessage.parts { messageParts = state.activeMessage.parts }
        if heldMessageText != state.heldMessage?.displayText { heldMessageText = state.heldMessage?.displayText }
        if playback != state.playback { playback = state.playback }
        if sessionNotice != state.notice { sessionNotice = state.notice }
        if sessionIsSaving != state.isSaving { sessionIsSaving = state.isSaving }
        if textChanged { refreshPredictions(for: text) }
    }

    /// Sends one edit to the session and mirrors the result right away, so the text
    /// field never shows a stale Message between an edit and its observation.
    private func editMessage(_ edit: () -> Void) {
        edit()
        applySessionState(session.state())
    }

    /// Applies the Message field's new text. Untouched Phrase and Button parts keep
    /// their recordings and sources.
    func onInputChanged(_ newValue: String) {
        guard newValue != input else { return }
        editMessage { session.editText(newText: newValue) }
    }

    func insertPhraseText(_ phrase: Shared.Phrase) {
        // #118: ignore rapid repeated activations of the same target.
        guard acceptActivation(targetId: phrase.id) else { return }
        guard !phrase.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let length = (input as NSString).length
        let cursor = inputSelectionRange.location == NSNotFound ? length : min(inputSelectionRange.location, length)
        var newCursor: Int32 = 0
        editMessage { newCursor = session.insertPhrase(phrase: phrase, cursor: Int32(cursor)) }
        inputSelectionRange = NSRange(location: Int(newCursor), length: 0)
        // #119: immediate speech policy speaks each inserted phrase as it is composed.
        if speechPolicy == "Immediate" {
            speakPhrase(phrase)
        }
    }

    /// Appends the active Screen Button's text to the Message.
    func appendScreenPart(text: String, buttonId: String) {
        editMessage {
            session.appendScreenPart(
                text: text,
                screenId: selectedBoardSetId ?? "",
                pageId: selectedBoardId ?? "",
                buttonId: buttonId,
                spellingMode: selectedBoardUsesSpellingMode
            )
        }
    }

    func removeLastMessagePart() {
        editMessage { session.removeLastPart(spellingMode: selectedBoardUsesSpellingMode) }
    }

    /// Removes one part, e.g. a Button tapped by mistake in the Screens message bar.
    func removeMessagePart(at index: Int) {
        guard messageParts.indices.contains(index) else { return }
        let start = messageParts[..<index].reduce(0) { $0 + ($1.displayText as NSString).length }
        let length = (messageParts[index].displayText as NSString).length
        editMessage {
            session.replaceRange(start: Int32(start), endExclusive: Int32(start + length), replacement: "")
        }
    }

    func clearMessage() {
        editMessage { session.clear() }
        inputSelectionRange = NSRange(location: 0, length: 0)
    }

    /// Holds the Message, or swaps it with the held one.
    func toggleHoldThatThought() {
        editMessage { session.swapHeldMessage() }
        inputSelectionRange = NSRange(location: (input as NSString).length, length: 0)
    }

    /// Speaks the whole Message. The session records it in History once spoken.
    func speakMessage(cacheAudio: Bool = true) {
        AudioSessionHelper.activatePlayback()
        session.speak(voice: selectedVoice, cacheAudio: cacheAudio)
    }

    /// Speaks the Message from a Screen, honoring the Screen's sentence caching.
    func speakBoardMessage(boardSetId: String) {
        speakMessage(cacheAudio: boardSets.first(where: { $0.id == boardSetId })?.cacheWholeSentences ?? true)
    }

    /// Speaks text on its own (previews, single Buttons) without changing the Message or History.
    func speak(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        AudioSessionHelper.activatePlayback()
        session.speakText(text: text, voice: selectedVoice)
    }

    /// Speaks a Phrase on its own, playing its recording when it has one.
    func speakPhrase(_ phrase: Shared.Phrase) {
        AudioSessionHelper.activatePlayback()
        session.speakPhrase(phrase: phrase, voice: selectedVoice)
    }

    func pauseSpeech() { session.pause() }
    func resumeSpeech() { session.resume() }
    func stopSpeech() { session.stop() }
    func dismissSessionNotice() { session.dismissNotice() }
    func retrySessionStorage() { session.retryStorage() }

    func markSelectionAsSecondaryLanguage(range: NSRange) {
        let locale = secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !locale.isEmpty, locale != primaryLanguage,
              range.location != NSNotFound, range.length > 0 else { return }
        editMessage {
            session.toggleSecondaryLanguage(start: Int32(range.location), endExclusive: Int32(range.location + range.length))
        }
    }

    func playBoardButtonSound(_ dataUrl: String) {
        guard !dataUrl.isEmpty, let url = playableURL(from: dataUrl) else { return }
        AudioSessionHelper.activatePlayback()
        buttonSoundPlayer = try? AVAudioPlayer(contentsOf: url)
        buttonSoundPlayer?.play()
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
    func setTtsEngine(_ engine: String) {
        ttsEngine = engine
        useSystemTts = engine == "SYSTEM"
        UserDefaults.standard.set(engine, forKey: "tts_engine")
        UserDefaults.standard.set(useSystemTts, forKey: "use_system_tts")
        Task { try? await speechFacade.updateTtsEngineNamed(engine: engine) }
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

    func setBoardShowSpeakButton(_ enabled: Bool) {
        boardShowSpeakButton = enabled
        Task { _ = try? await settingsFacade.updateBoardShowSpeakButton(enabled: enabled) }
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
            }
        } catch {
            // swallow for now
        }
    }

    func updateLanguage(_ lang: String) {
        Task {
            _ = try? await speechFacade.updateSelectedVoiceLanguage(lang: lang)
            self.primaryLanguage = lang
            refreshVoiceAndLanguages()
        }
    }

    func updateSecondaryLanguage(_ lang: String) {
        Task {
            _ = try? await settingsFacade.updateSecondaryLanguage(lang: lang)
            self.secondaryLanguage = lang
            // Highlight the spans tagged with the new secondary language.
            self.applySessionState(self.session.state())
        }
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
    }
    
    // MARK: - Prediction
    private func refreshPredictions(for text: String) {
        #if DEBUG
        predictionJob?.cancel()
        predictionSubscription?.cancel()
        predictionSubscription = nil
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            predictions = Shared.PredictionResult(words: [], letters: [])
            return
        }
        predictionJob = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard !Task.isCancelled, let self else { return }
            self.predictionSubscription = self.bridge.observePredictions(
                context: text, maxWords: 5, maxLetters: 5
            ) { [weak self] result in
                guard let self, self.input == text else { return }
                self.predictions = result
            }
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
        onInputChanged(result.text)
        inputSelectionRange = NSRange(location: Int(result.cursor), length: 0)
    }
    
    func applyLetterPrediction(_ char: String) {
        let result = bridge.insertPredictedText(
            text: input,
            cursor: Int32(inputSelectionRange.location),
            value: char
        )
        onInputChanged(result.text)
        inputSelectionRange = NSRange(location: Int(result.cursor), length: 0)
    }
    #endif

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
            boardStatusMessage = NSLocalizedString("board_sets.error.load_failed", comment: "")
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

    func boardButtonIsVisible(hidden: Bool, isEditMode: Bool, showHiddenButtons: Bool) -> Bool {
        boardsFacade.boardButtonIsVisible(hidden: hidden, isEditMode: isEditMode, showHiddenButtons: showHiddenButtons)
    }

    func boardFieldFontScale(rowSpan: Int, columnSpan: Int) -> CGFloat {
        CGFloat(boardsFacade.boardFieldFontScale(rowSpan: Int32(rowSpan), columnSpan: Int32(columnSpan)))
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
            resolvedBoardShowSpeakButton = nil
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
            resolvedBoardShowSpeakButton = resolved?.showSpeakButton
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
            resolvedBoardShowSpeakButton = nil
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
        stopBoardPredictions()
        var seenIds = Set<String>()
        let predictorIds = boardCells
            .filter { cell in cell.actions.contains { $0.lowercased() == ":prediction" || $0.lowercased() == ":predictions" } }
            .map(\.buttonId)
            .filter { seenIds.insert($0).inserted }
        guard !predictorIds.isEmpty else { return }
        boardPredictionSubscription = bridge.observePredictions(
            context: context, maxWords: Int32(predictorIds.count), maxLetters: 0
        ) { [weak self] result in
            self?.boardPredictionsByButtonId = Dictionary(
                uniqueKeysWithValues: zip(predictorIds, result.words).map { ($0.0, $0.1) }
            )
        }
    }

    func stopBoardPredictions() {
        boardPredictionSubscription?.cancel()
        boardPredictionSubscription = nil
        boardPredictionsByButtonId = [:]
    }

    func boardPrediction(for buttonId: String) -> String? {
        boardPredictionsByButtonId[buttonId]
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
            boardStatusMessage = NSLocalizedString("boardset.error.delete_board_failed", comment: "")
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
                boardStatusMessage = NSLocalizedString("boardset.error.save_failed", comment: "")
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
            boardStatusMessage = NSLocalizedString("board_sets.status.deleted", comment: "")
        } catch {
            boardStatusMessage = NSLocalizedString("board_sets.error.delete_failed", comment: "")
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
                boardStatusMessage = NSLocalizedString("board_sets.status.duplicated", comment: "")
            }
        } catch {
            boardStatusMessage = NSLocalizedString("board_sets.error.duplicate_failed", comment: "")
        }
    }
}

private extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
