import Foundation
import Shared
import UIKit

/// Swift-side cancellation protocol conformed to by `Shared.NativeSubscription` so feature
/// models can test subscription replacement and disposal without constructing Kotlin objects.
protocol CancellableSubscription: AnyObject {
    func cancel()
}

extension Shared.NativeSubscription: CancellableSubscription {}

/// Protocol seam covering communication session, phrase store, typing tray, history,
/// predictions, symbol search, and button-look lookup so `CommunicationModel` can be
/// unit-tested cleanly without Koin or disk state.
protocol CommunicationService {
    func observeSession(_ onChange: @escaping (Shared.NativeCommunicationState) -> Void) -> any CancellableSubscription
    func currentSessionState() -> Shared.NativeCommunicationState
    func reloadSessionAfterRestore() async throws
    func editText(_ newText: String)
    func replaceRange(start: Int32, endExclusive: Int32, replacement: String)
    func appendScreenPart(
        text: String,
        screenId: String,
        pageId: String,
        buttonId: String,
        spellingMode: Bool,
        languageTag: String?,
        mathMode: Bool
    )
    func removeLastPart(spellingMode: Bool)
    func clearMessage()
    func swapHeldMessage()
    func toggleSecondaryLanguage(start: Int32, endExclusive: Int32)
    func activatePhrase(
        phrase: Shared.Phrase,
        start: Int32,
        endExclusive: Int32,
        activationBehavior: Shared.BoardActivationBehavior?
    ) -> Shared.NativePhraseActivation
    func speakMessage(voice: Shared.Voice?, cacheAudio: Bool)
    func speakText(_ text: String, voice: Shared.Voice?)
    func previewText(_ text: String, voice: Shared.Voice?, rateOverride: KotlinDouble?, languageTag: String?)
    func cancelPreview()
    func speakMessagePart(index: Int32, voice: Shared.Voice?)
    func speakPhrase(_ phrase: Shared.Phrase, voice: Shared.Voice?)
    func speakRecording(path: String, voice: Shared.Voice?)
    func pauseSpeech()
    func resumeSpeech()
    func stopSpeech()
    func dismissSessionNotice()
    func retrySessionStorage()

    func observePhrases(_ onChange: @escaping (Shared.PhraseListStoreState) -> Void) -> any CancellableSubscription
    func acceptPhraseIntent(_ intent: Shared.PhraseListStoreIntent)
    func refreshPhrases()
    func updatePhraseRecording(phraseId: String, recordingPath: String?)

    func loadTypingTray() async throws -> Shared.TypingTray
    func loadHistory() async throws -> Shared.HistoryResult
    func loadLikelyNext() async throws -> Shared.HistoryResult
    func listBoardCells(boardId: String) async throws -> [Shared.IosBoardCell]
    func searchSymbols(query: String, locale: String, symbolPackage: String) async throws -> Shared.SymbolSearchResult
    func observePredictions(
        context: String,
        maxWords: Int32,
        maxLetters: Int32,
        onUpdate: @escaping (Shared.PredictionUpdate) -> Void
    ) -> any CancellableSubscription
    func activateAudioPlayback()
}

struct SharedCommunicationService: CommunicationService {
    private let resolveSession: () -> Shared.CommunicationSessionFacade
    private let resolveCommunication: () -> Shared.CommunicationFacade
    private let resolveTypingScreens: () -> Shared.TypingScreenFacade
    private let resolvePredictions: () -> Shared.PredictionFacade
    private let resolveSymbolSearch: () -> Shared.SymbolSearchFacade
    private let resolveBoards: () -> Shared.BoardsFacade

    init(
        resolveSession: @escaping () -> Shared.CommunicationSessionFacade = { IosDiBridge().communicationSessionFacade() },
        resolveCommunication: @escaping () -> Shared.CommunicationFacade = { IosDiBridge().communicationFacade() },
        resolveTypingScreens: @escaping () -> Shared.TypingScreenFacade = { IosDiBridge().typingScreenFacade() },
        resolvePredictions: @escaping () -> Shared.PredictionFacade = { IosDiBridge().predictionFacade() },
        resolveSymbolSearch: @escaping () -> Shared.SymbolSearchFacade = { IosDiBridge().symbolSearchFacade() },
        resolveBoards: @escaping () -> Shared.BoardsFacade = { IosDiBridge().boardsFacade() }
    ) {
        self.resolveSession = resolveSession
        self.resolveCommunication = resolveCommunication
        self.resolveTypingScreens = resolveTypingScreens
        self.resolvePredictions = resolvePredictions
        self.resolveSymbolSearch = resolveSymbolSearch
        self.resolveBoards = resolveBoards
    }

    func observeSession(_ onChange: @escaping (Shared.NativeCommunicationState) -> Void) -> any CancellableSubscription {
        resolveSession().observe(onChange: onChange)
    }

    func currentSessionState() -> Shared.NativeCommunicationState {
        resolveSession().state()
    }

    func reloadSessionAfterRestore() async throws {
        try await resolveSession().reloadAfterRestore()
    }

    func editText(_ newText: String) { resolveSession().editText(newText: newText) }
    func replaceRange(start: Int32, endExclusive: Int32, replacement: String) {
        resolveSession().replaceRange(start: start, endExclusive: endExclusive, replacement: replacement)
    }

    func appendScreenPart(
        text: String,
        screenId: String,
        pageId: String,
        buttonId: String,
        spellingMode: Bool,
        languageTag: String?,
        mathMode: Bool
    ) {
        resolveSession().appendScreenPart(
            text: text,
            screenId: screenId,
            pageId: pageId,
            buttonId: buttonId,
            spellingMode: spellingMode,
            languageTag: languageTag,
            mathMode: mathMode
        )
    }

    func removeLastPart(spellingMode: Bool) { resolveSession().removeLastPart(spellingMode: spellingMode) }
    func clearMessage() { resolveSession().clear() }
    func swapHeldMessage() { resolveSession().swapHeldMessage() }
    func toggleSecondaryLanguage(start: Int32, endExclusive: Int32) {
        resolveSession().toggleSecondaryLanguage(start: start, endExclusive: endExclusive)
    }

    func activatePhrase(
        phrase: Shared.Phrase,
        start: Int32,
        endExclusive: Int32,
        activationBehavior: Shared.BoardActivationBehavior?
    ) -> Shared.NativePhraseActivation {
        resolveSession().activatePhrase(
            phrase: phrase,
            start: start,
            endExclusive: endExclusive,
            activationBehavior: activationBehavior
        )
    }

    func speakMessage(voice: Shared.Voice?, cacheAudio: Bool) {
        resolveSession().speak(voice: voice, cacheAudio: cacheAudio)
    }

    func speakText(_ text: String, voice: Shared.Voice?) { resolveSession().speakText(text: text, voice: voice) }
    func previewText(_ text: String, voice: Shared.Voice?, rateOverride: KotlinDouble?, languageTag: String?) {
        resolveSession().previewText(text: text, voice: voice, rateOverride: rateOverride, languageTag: languageTag)
    }
    func cancelPreview() { resolveSession().cancelPreview() }
    func speakMessagePart(index: Int32, voice: Shared.Voice?) { resolveSession().speakMessagePart(index: index, voice: voice) }
    func speakPhrase(_ phrase: Shared.Phrase, voice: Shared.Voice?) { resolveSession().speakPhrase(phrase: phrase, voice: voice) }
    func speakRecording(path: String, voice: Shared.Voice?) { resolveSession().speakRecording(path: path, voice: voice) }
    func pauseSpeech() { resolveSession().pause() }
    func resumeSpeech() { resolveSession().resume() }
    func stopSpeech() { resolveSession().stop() }
    func dismissSessionNotice() { resolveSession().dismissNotice() }
    func retrySessionStorage() { resolveSession().retryStorage() }

    func observePhrases(_ onChange: @escaping (Shared.PhraseListStoreState) -> Void) -> any CancellableSubscription {
        resolveCommunication().observePhrases(onChange: onChange)
    }

    func acceptPhraseIntent(_ intent: Shared.PhraseListStoreIntent) {
        resolveCommunication().phraseListStore().accept(intent: intent)
    }

    func refreshPhrases() { resolveCommunication().refreshPhrases() }

    func updatePhraseRecording(phraseId: String, recordingPath: String?) {
        resolveCommunication().updatePhraseRecording(phraseId: phraseId, recordingPath: recordingPath)
    }

    func loadTypingTray() async throws -> Shared.TypingTray { try await resolveTypingScreens().tray() }
    func loadHistory() async throws -> Shared.HistoryResult { try await resolveCommunication().history() }
    func loadLikelyNext() async throws -> Shared.HistoryResult { try await resolveCommunication().likelyNext() }
    func listBoardCells(boardId: String) async throws -> [Shared.IosBoardCell] {
        try await resolveBoards().listBoardCells(boardId: boardId)
    }

    func searchSymbols(query: String, locale: String, symbolPackage: String) async throws -> Shared.SymbolSearchResult {
        try await resolveSymbolSearch().search(
            query: query,
            locale: locale,
            symbolPackage: symbolPackage,
            prioritizeArasaac: false
        )
    }

    func observePredictions(
        context: String,
        maxWords: Int32,
        maxLetters: Int32,
        onUpdate: @escaping (Shared.PredictionUpdate) -> Void
    ) -> any CancellableSubscription {
        resolvePredictions().observe(context: context, maxWords: maxWords, maxLetters: maxLetters, onChange: onUpdate)
    }

    func activateAudioPlayback() {
        AudioSessionHelper.activatePlayback()
    }
}

/// Feature-scoped observable model for Message composition, playback, Phrases/Categories,
/// Typing tray, History, Likely Next, Predictions, and Symbol search (#187).
@MainActor
final class CommunicationModel: ObservableObject {
    @Published private(set) var state: Shared.PhraseListStoreState = Shared.PhraseListStoreState(
        phrases: [],
        categories: [],
        selectedCategoryId: nil,
        isLoading: true,
        error: nil,
        lastDeletedUndo: nil
    )

    // Pending destructive Phrase/Category undo banner (#405)
    @Published private(set) var pendingPhraseUndo: PendingPhraseUndo? = nil

    // Shared Message (#306)
    @Published private(set) var input: String = ""
    var inputSelectionRange: NSRange = NSRange(location: 0, length: 0)
    @Published private(set) var messageParts: [Shared.MessagePart] = []
    @Published private(set) var heldMessageText: String? = nil
    @Published private(set) var playback: Shared.SessionPlayback = .idle
    @Published private(set) var sessionNotice: Shared.SessionNotice? = nil
    @Published private(set) var sessionIsSaving: Bool = false
    /// False until the saved Message has loaded; edits wait so they cannot replace it.
    @Published private(set) var messageLoaded: Bool = false
    var hasHeldThought: Bool { heldMessageText != nil }

    /// How a Screen Button looks in the message bar, by "pageId|buttonId".
    @Published private(set) var screenButtonLooks: [String: (label: String, imageUrl: String?)] = [:]
    /// The Typing Screen template that lays out the Typing tray; nil until loaded.
    @Published private(set) var typingTray: Shared.TypingTray? = nil
    @Published private(set) var typingTrayFailed: Bool = false
    @Published private(set) var secondaryLanguageRanges: [NSRange] = []

    // History & Likely Next
    @Published private(set) var historyPhrases: [Shared.Phrase] = []
    @Published private(set) var likelyNextPhrases: [Shared.Phrase] = []
    @Published private(set) var historyLoadFailed: Bool = false

    #if DEBUG
    @Published private(set) var predictions: Shared.PredictionResult = Shared.PredictionResult(words: [], letters: [])
    private var predictionJob: Task<Void, Never>?
    private var predictionSubscription: (any CancellableSubscription)?
    #endif

    private let service: any CommunicationService
    private let settings: AppSettingsModel
    private let speech: SpeechSettingsModel
    private let isInScreensMode: () -> Bool
    private let activeScreenContext: () -> (
        screenId: String,
        pageId: String?,
        spellingMode: Bool,
        currentCells: [(buttonId: String, label: String?, vocalization: String?, imageUrl: String?)],
        cacheWholeSentences: (String) -> Bool
    )
    private let removeFileAtPath: (String) -> Void

    private var phraseSubscription: (any CancellableSubscription)?
    private var sessionSubscription: (any CancellableSubscription)?
    private var historyTask: Task<Void, Never>?
    private var typingTrayTask: Task<Void, Never>?
    private var buttonLooksTasks: [String: Task<Void, Never>] = [:]
    private var lookedUpPageIds: Set<String> = []
    private var pendingPhraseUndoTask: Task<Void, Never>?
    private var dismissedPhraseUndoIds: Set<Int64> = []

    init(
        service: any CommunicationService = SharedCommunicationService(),
        settings: AppSettingsModel,
        speech: SpeechSettingsModel,
        isInScreensMode: @escaping () -> Bool = { false },
        activeScreenContext: @escaping () -> (
            screenId: String,
            pageId: String?,
            spellingMode: Bool,
            currentCells: [(buttonId: String, label: String?, vocalization: String?, imageUrl: String?)],
            cacheWholeSentences: (String) -> Bool
        ) = { ("", nil, false, [], { _ in true }) },
        removeFileAtPath: @escaping (String) -> Void = { path in
            if FileManager.default.fileExists(atPath: path) {
                try? FileManager.default.removeItem(atPath: path)
            }
        }
    ) {
        self.service = service
        self.settings = settings
        self.speech = speech
        self.isInScreensMode = isInScreensMode
        self.activeScreenContext = activeScreenContext
        self.removeFileAtPath = removeFileAtPath
    }

    deinit {
        phraseSubscription?.cancel()
        sessionSubscription?.cancel()
        historyTask?.cancel()
        typingTrayTask?.cancel()
        pendingPhraseUndoTask?.cancel()
        buttonLooksTasks.values.forEach { $0.cancel() }
        #if DEBUG
        predictionJob?.cancel()
        predictionSubscription?.cancel()
        #endif
    }

    func cancelPendingTasks() {
        historyTask?.cancel()
        historyTask = nil
        typingTrayTask?.cancel()
        typingTrayTask = nil
        pendingPhraseUndoTask?.cancel()
        pendingPhraseUndoTask = nil
        buttonLooksTasks.values.forEach { $0.cancel() }
        buttonLooksTasks.removeAll()
        #if DEBUG
        predictionJob?.cancel()
        predictionJob = nil
        predictionSubscription?.cancel()
        predictionSubscription = nil
        #endif
    }

    func startObserving() {
        sessionSubscription?.cancel()
        sessionSubscription = service.observeSession { [weak self] state in
            self?.applySessionState(state)
        }
        phraseSubscription?.cancel()
        phraseSubscription = service.observePhrases { [weak self] state in
            self?.state = state
            self?.syncPhraseUndo(from: state)
        }
    }

    func reloadAfterBackupRestore() async throws {
        // The restored library replaces the one the pending deletion came from, so its
        // Undo must not insert an old Phrase or Category into it.
        dismissPendingPhraseUndo()
        service.refreshPhrases()
        try await service.reloadSessionAfterRestore()
        await loadTypingTray()
        await loadHistory()
    }

    func refreshSessionLanguageHighlights() {
        applySessionState(service.currentSessionState())
    }

    // MARK: - Message (shared Communication session)

    func applySessionState(_ state: Shared.NativeCommunicationState) {
        let text = state.activeMessage.displayText
        let textChanged = input != text
        if textChanged { input = text }
        inputSelectionRange = clampedSelectionRange(inputSelectionRange, maxLength: (text as NSString).length)
        let secondary = speech.secondaryLanguage
        let ranges = state.activeMessage.languageSpans
            .filter { $0.languageTag == secondary }
            .map { NSRange(location: Int($0.range.start), length: Int($0.range.length)) }
        if ranges != secondaryLanguageRanges { secondaryLanguageRanges = ranges }
        if messageParts != state.activeMessage.parts {
            messageParts = state.activeMessage.parts
            resolveScreenButtonLooks(for: state.activeMessage.parts + (state.heldMessage?.parts ?? []))
        }
        if heldMessageText != state.heldMessage?.displayText { heldMessageText = state.heldMessage?.displayText }
        if playback != state.playback {
            let finished = state.playback.name == "Idle"
            playback = state.playback
            if finished && settings.historyVisible {
                _ = startHistoryLoad()
            }
        }
        if sessionNotice != state.notice { sessionNotice = state.notice }
        if sessionIsSaving != state.isSaving { sessionIsSaving = state.isSaving }
        if messageLoaded != state.isLoaded { messageLoaded = state.isLoaded }
        if textChanged { refreshPredictions(for: text) }
    }

    func editMessage(_ edit: () -> Void) {
        guard messageLoaded else {
            applySessionState(service.currentSessionState())
            return
        }
        edit()
        applySessionState(service.currentSessionState())
    }

    func onInputChanged(_ newValue: String) {
        guard newValue != input else { return }
        editMessage { service.editText(newValue) }
    }

    func loadTypingTray() async {
        typingTrayTask?.cancel()
        let task = Task { [weak self] in
            guard let self else { return }
            do {
                let tray = try await self.service.loadTypingTray()
                guard !Task.isCancelled else { return }
                self.typingTray = tray
                self.typingTrayFailed = false
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return }
                self.typingTrayFailed = true
            }
        }
        typingTrayTask = task
        await task.value
    }

    func activateTypingPhrase(_ phrase: Shared.Phrase) {
        guard settings.acceptActivation(targetId: phrase.id) else { return }
        guard !phrase.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        settings.triggerSelectionHighlight(buttonId: "phrase:\(phrase.id)")
        let selection = clampedSelectionRange(inputSelectionRange, maxLength: (input as NSString).length)
        let behavior = typingTray?.activationBehavior
        var activation: Shared.NativePhraseActivation?
        editMessage {
            activation = service.activatePhrase(
                phrase: phrase,
                start: Int32(selection.location),
                endExclusive: Int32(selection.location + selection.length),
                activationBehavior: behavior
            )
        }
        guard let activation else { return }
        if let behavior, behavior.name != "SpeakOnly" {
            inputSelectionRange = NSRange(location: Int(activation.cursor), length: 0)
        }
        if activation.shouldSpeak { speakPhrase(phrase) }
    }

    func performTypingEffect(_ effect: Shared.TypingEffect) {
        let kind = effect.kind
        if kind.name == "InsertText" || kind.name == "WrapSelection" || kind.name == "Backspace" {
            editSelection(effect)
        } else if kind.name == "Clear" {
            clearMessage()
        } else if kind.name == "Speak" {
            speakMessage()
        } else if kind.name == "Pause" {
            pauseSpeech()
        } else if kind.name == "Resume" {
            resumeSpeech()
        } else if kind.name == "Stop" {
            stopSpeech()
        } else if kind.name == "SecondaryLanguage" {
            markSelectionAsSecondaryLanguage(range: inputSelectionRange)
        } else if kind.name == "HoldMessage" {
            toggleHoldThatThought()
        }
    }

    func editSelection(_ effect: Shared.TypingEffect) {
        let text = input as NSString
        let selection = clampedSelectionRange(inputSelectionRange, maxLength: text.length)
        var range = selection
        var replacement = ""
        var cursor = selection.location
        if effect.kind.name == "InsertText" {
            replacement = effect.prefix
            cursor = selection.location + (replacement as NSString).length
        } else if effect.kind.name == "WrapSelection" {
            let selected = text.substring(with: selection)
            replacement = effect.prefix + selected + effect.suffix
            cursor = selected.isEmpty
                ? selection.location + (effect.prefix as NSString).length
                : selection.location + (replacement as NSString).length
        } else if selection.length == 0 {
            guard selection.location > 0 else { return }
            range = text.rangeOfComposedCharacterSequence(at: selection.location - 1)
            cursor = range.location
        }
        editMessage {
            service.replaceRange(
                start: Int32(range.location),
                endExclusive: Int32(range.location + range.length),
                replacement: replacement
            )
        }
        inputSelectionRange = NSRange(location: cursor, length: 0)
    }

    var hasUsableSecondaryLanguage: Bool {
        let secondary = speech.secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        return !secondary.isEmpty && secondary != speech.primaryLanguage
    }

    func appendScreenPart(text: String, buttonId: String, pageId: String? = nil, languageTag: String? = nil, mathMode: Bool = false) {
        let screenContext = activeScreenContext()
        if let activePageId = screenContext.pageId {
            rememberScreenButtonLooks(pageId: activePageId, cells: screenContext.currentCells)
        }
        editMessage {
            service.appendScreenPart(
                text: text,
                screenId: screenContext.screenId,
                pageId: pageId ?? screenContext.pageId ?? "",
                buttonId: buttonId,
                spellingMode: screenContext.spellingMode,
                languageTag: languageTag,
                mathMode: mathMode
            )
        }
    }

    func removeLastMessagePart() {
        let spellingMode = activeScreenContext().spellingMode
        editMessage { service.removeLastPart(spellingMode: spellingMode) }
    }

    func removeMessagePart(at index: Int) {
        guard messageParts.indices.contains(index) else { return }
        let start = messageParts[..<index].reduce(0) { $0 + ($1.displayText as NSString).length }
        let length = (messageParts[index].displayText as NSString).length
        editMessage {
            service.replaceRange(start: Int32(start), endExclusive: Int32(start + length), replacement: "")
        }
    }

    func clearMessage() {
        editMessage { service.clearMessage() }
        inputSelectionRange = NSRange(location: 0, length: 0)
    }

    func toggleHoldThatThought() {
        editMessage { service.swapHeldMessage() }
        inputSelectionRange = NSRange(location: (input as NSString).length, length: 0)
    }

    func speakMessage(cacheAudio: Bool = true) {
        service.activateAudioPlayback()
        service.speakMessage(voice: speech.selectedVoice, cacheAudio: cacheAudio)
    }

    func speakBoardMessage(boardSetId: String) {
        let cacheAudio = activeScreenContext().cacheWholeSentences(boardSetId)
        speakMessage(cacheAudio: cacheAudio)
    }

    func speak(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        service.activateAudioPlayback()
        service.speakText(text, voice: speech.selectedVoice)
    }

    /// Speaks an auditory-fishing preview, replacing any earlier preview without
    /// interrupting committed speech or recording History.
    func previewSpeech(_ text: String, languageTag: String? = nil) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        service.activateAudioPlayback()
        service.previewText(
            text,
            voice: speech.selectedVoice,
            rateOverride: KotlinDouble(value: 0.8),
            languageTag: languageTag
        )
    }

    /// Stops any active or queued auditory-fishing preview without interrupting committed speech.
    func cancelPreviewSpeech() {
        service.cancelPreview()
    }

    func speakMessagePart(at index: Int) {
        service.activateAudioPlayback()
        service.speakMessagePart(index: Int32(index), voice: speech.selectedVoice)
    }

    func speakPhrase(_ phrase: Shared.Phrase) {
        service.activateAudioPlayback()
        service.speakPhrase(phrase, voice: speech.selectedVoice)
    }

    func pauseSpeech() { service.pauseSpeech() }
    func resumeSpeech() { service.resumeSpeech() }
    func stopSpeech() { service.stopSpeech() }
    func dismissSessionNotice() { service.dismissSessionNotice() }
    func retrySessionStorage() { service.retrySessionStorage() }

    func markSelectionAsSecondaryLanguage(range: NSRange) {
        let locale = speech.secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !locale.isEmpty, locale != speech.primaryLanguage,
              range.location != NSNotFound, range.length > 0 else { return }
        editMessage {
            service.toggleSecondaryLanguage(start: Int32(range.location), endExclusive: Int32(range.location + range.length))
        }
    }

    func playBoardButtonSound(_ dataUrl: String) {
        guard !dataUrl.isEmpty, let url = playableURL(from: dataUrl), url.isFileURL else { return }
        service.activateAudioPlayback()
        service.speakRecording(path: url.path, voice: speech.selectedVoice)
    }

    func playBoardButtonFile(_ path: String) {
        guard !path.isEmpty else { return }
        service.activateAudioPlayback()
        service.speakRecording(path: path, voice: speech.selectedVoice)
    }

    // MARK: - Screen button looks cache

    func rememberScreenButtonLooks(
        pageId: String,
        cells: [(buttonId: String, label: String?, vocalization: String?, imageUrl: String?)]
    ) {
        var looks = screenButtonLooks
        for cell in cells {
            let label = (cell.label ?? cell.vocalization ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let imageUrl = cell.imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
            looks["\(pageId)|\(cell.buttonId)"] = (label, imageUrl?.isEmpty == false ? imageUrl : nil)
        }
        let changed = looks.count != screenButtonLooks.count || looks.contains { key, look in
            guard let old = screenButtonLooks[key] else { return true }
            return old.label != look.label || old.imageUrl != look.imageUrl
        }
        if changed { screenButtonLooks = looks }
        lookedUpPageIds.insert(pageId)
    }

    func resolveScreenButtonLooks(for parts: [Shared.MessagePart]) {
        let pageIds = Set(parts.compactMap { ($0.source as? Shared.MessagePartSourceScreenButton)?.pageId })
            .subtracting(lookedUpPageIds)
        guard !pageIds.isEmpty else { return }
        lookedUpPageIds.formUnion(pageIds)
        for pageId in pageIds {
            buttonLooksTasks[pageId]?.cancel()
            buttonLooksTasks[pageId] = Task { [weak self] in
                guard let self else { return }
                defer { self.buttonLooksTasks[pageId] = nil }
                do {
                    let cells = try await self.service.listBoardCells(boardId: pageId)
                    guard !Task.isCancelled else {
                        self.lookedUpPageIds.remove(pageId)
                        return
                    }
                    self.rememberScreenButtonLooks(
                        pageId: pageId,
                        cells: cells.map { ($0.buttonId, $0.label, $0.vocalization, $0.imageUrl) }
                    )
                } catch {
                    self.lookedUpPageIds.remove(pageId)
                }
            }
        }
    }

    func playableURL(from dataUrl: String) -> URL? {
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

    // MARK: - History & Likely Next

    func clearHistoryDisplay() {
        historyTask?.cancel()
        historyTask = nil
        historyPhrases = []
        likelyNextPhrases = []
    }

    func clearLikelyNextDisplay() {
        likelyNextPhrases = []
    }

    func loadHistory() async {
        await startHistoryLoad().value
    }

    @discardableResult
    private func startHistoryLoad() -> Task<Void, Never> {
        historyTask?.cancel()
        guard settings.historyVisible else {
            historyPhrases = []
            likelyNextPhrases = []
            historyLoadFailed = false
            return Task {}
        }
        let task = Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await self.service.loadHistory()
                guard !Task.isCancelled, self.settings.historyVisible else { return }
                guard result.isSuccess else {
                    self.historyLoadFailed = true
                    return
                }
                self.historyLoadFailed = false
                self.historyPhrases = result.phrases.reversed()
                if self.settings.likelyNextPageEnabled {
                    let next = try await self.service.loadLikelyNext()
                    guard !Task.isCancelled, self.settings.historyVisible, self.settings.likelyNextPageEnabled else { return }
                    if next.isSuccess {
                        self.likelyNextPhrases = next.phrases
                    }
                } else {
                    self.likelyNextPhrases = []
                }
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return }
                self.historyLoadFailed = true
            }
        }
        historyTask = task
        return task
    }

    var likelyNextAvailable: Bool {
        settings.historyVisible && settings.likelyNextPageEnabled && !likelyNextPhrases.isEmpty
    }

    // MARK: - Phrases & Categories

    func retryPhraseLoad() {
        service.refreshPhrases()
    }

    func recordingPath(for phraseId: String) -> String? {
        state.phrases.first(where: { $0.id == phraseId })?.recordingPath
    }

    func setRecordingPath(_ path: String?, for phraseId: String) {
        service.updatePhraseRecording(phraseId: phraseId, recordingPath: path)
    }

    func deletePhrase(id: String) {
        service.acceptPhraseIntent(Shared.PhraseListStoreIntent.DeletePhrase(phraseId: id))
    }

    func selectCategory(id: String?) {
        service.acceptPhraseIntent(Shared.PhraseListStoreIntent.SelectCategory(categoryId: id))
    }

    func deleteCategory(id: String) {
        service.acceptPhraseIntent(Shared.PhraseListStoreIntent.DeleteCategory(categoryId: id))
    }

    /// Mirrors confirmed deletion undo state from `PhraseListStore` so the banner only
    /// appears after persistence succeeds, stays visible while a restore is in flight so
    /// failed restores can be retried, and never unlinks recordings still referenced by
    /// active/held Messages or History.
    func syncPhraseUndo(from storeState: Shared.PhraseListStoreState) {
        guard let undo = storeState.lastDeletedUndo else {
            pendingPhraseUndoTask?.cancel()
            pendingPhraseUndoTask = nil
            if let current = pendingPhraseUndo {
                if current.isRestoring {
                    AccessHaptics.perform(.confirm)
                }
                pendingPhraseUndo = nil
            }
            return
        }
        if let current = pendingPhraseUndo, current.id == undo.id {
            switch undo.restoreStatus {
            case .restoring:
                if !current.isRestoring {
                    pendingPhraseUndo = PendingPhraseUndo(
                        id: undo.id,
                        includesSubItems: undo.includesSubItems,
                        isRestoring: true
                    )
                }
            case .failed:
                if current.isRestoring {
                    AccessHaptics.perform(.reject)
                    pendingPhraseUndo = PendingPhraseUndo(
                        id: undo.id,
                        includesSubItems: undo.includesSubItems,
                        isRestoring: false
                    )
                }
            default:
                break
            }
            return
        }
        guard !dismissedPhraseUndoIds.contains(undo.id) else { return }
        pendingPhraseUndoTask?.cancel()
        pendingPhraseUndoTask = nil

        let entry = PendingPhraseUndo(
            id: undo.id,
            includesSubItems: undo.includesSubItems,
            isRestoring: undo.restoreStatus == .restoring
        )
        pendingPhraseUndo = entry

        let messageKey = entry.includesSubItems ? "phrase.deleted_with_subitems" : "phrase.deleted"
        UIAccessibility.post(notification: .announcement, argument: NSLocalizedString(messageKey, comment: ""))

        // Keep the undo banner visible for assistive-technology users until they choose Undo or Close.
        if !UIAccessibility.isVoiceOverRunning && !UIAccessibility.isSwitchControlRunning {
            pendingPhraseUndoTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                guard !Task.isCancelled, let self,
                      let current = self.pendingPhraseUndo,
                      current.id == entry.id,
                      !current.isRestoring else { return }
                self.dismissPendingPhraseUndo()
            }
        }
    }

    func undoPendingPhraseDeletion() {
        pendingPhraseUndoTask?.cancel()
        pendingPhraseUndoTask = nil
        guard let pending = pendingPhraseUndo, !pending.isRestoring else { return }
        pendingPhraseUndo = PendingPhraseUndo(
            id: pending.id,
            includesSubItems: pending.includesSubItems,
            isRestoring: true
        )
        service.acceptPhraseIntent(Shared.PhraseListStoreIntent.UndoLastDeletion(undoId: pending.id))
    }

    func dismissPendingPhraseUndo() {
        pendingPhraseUndoTask?.cancel()
        pendingPhraseUndoTask = nil
        guard let pending = pendingPhraseUndo, !pending.isRestoring else { return }
        dismissedPhraseUndoIds.insert(pending.id)
        pendingPhraseUndo = nil
        service.acceptPhraseIntent(Shared.PhraseListStoreIntent.DismissDeletionUndo(undoId: pending.id))
    }

    func updatePhrase(id: String, text: String?, name: String?, imageUrl: String? = nil) {
        let normalizedImageUrl = imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        service.acceptPhraseIntent(
            Shared.PhraseListStoreIntent.UpdatePhrase(id: id, text: text, name: name, imageUrl: normalizedImageUrl)
        )
    }

    func movePhrase(_ phraseId: String, onto targetId: String) {
        guard phraseId != targetId else { return }
        service.acceptPhraseIntent(Shared.PhraseListStoreIntent.MovePhrase(phraseId: phraseId, targetId: targetId))
    }

    func addCategory(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        service.acceptPhraseIntent(Shared.PhraseListStoreIntent.AddCategory(name: trimmed))
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
        service.acceptPhraseIntent(
            Shared.PhraseListStoreIntent.AddPhrase(
                text: trimmed,
                name: finalAlternative,
                imageUrl: finalImageUrl,
                recordingPath: finalRecordingPath
            )
        )
    }

    // MARK: - Predictions

    func refreshPredictions(for text: String) {
        #if DEBUG
        predictionJob?.cancel()
        predictionSubscription?.cancel()
        predictionSubscription = nil
        if isInScreensMode() || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            if !(predictions.words.isEmpty && predictions.letters.isEmpty) {
                predictions = Shared.PredictionResult(words: [], letters: [])
            }
            return
        }
        predictionJob = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: 250_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled, let self else { return }
            self.predictionSubscription = self.service.observePredictions(
                context: text,
                maxWords: 5,
                maxLetters: 5
            ) { [weak self] update in
                guard let self, self.input == text else { return }
                self.predictions = update.predictions
            }
        }
        #endif
    }

    #if DEBUG
    func applyWordPrediction(_ word: String) {
        let result = TextEditingPolicy.shared.completeWord(
            text: input,
            cursor: Int32(inputSelectionRange.location),
            suggestion: word
        )
        onInputChanged(result.text)
        inputSelectionRange = NSRange(location: Int(result.cursor), length: 0)
    }

    func applyLetterPrediction(_ char: String) {
        let result = TextEditingPolicy.shared.insert(
            text: input,
            cursor: Int32(inputSelectionRange.location),
            value: char
        )
        onInputChanged(result.text)
        inputSelectionRange = NSRange(location: Int(result.cursor), length: 0)
    }
    #endif

    // MARK: - Symbol search

    func searchSymbols(query: String, symbolPackage: String) async -> Shared.SymbolSearchResult? {
        do {
            let result = try await service.searchSymbols(
                query: query,
                locale: Locale.current.identifier,
                symbolPackage: symbolPackage
            )
            guard !Task.isCancelled else { return nil }
            return result
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return nil }
            return nil
        }
    }

    func clampedSelectionRange(_ range: NSRange, maxLength: Int) -> NSRange {
        if range.location == NSNotFound {
            return NSRange(location: maxLength, length: 0)
        }
        let safeLocation = min(max(0, range.location), maxLength)
        let safeLength = min(max(0, range.length), max(0, maxLength - safeLocation))
        return NSRange(location: safeLocation, length: safeLength)
    }
}

struct PendingPhraseUndo: Equatable {
    let id: Int64
    let includesSubItems: Bool
    var isRestoring: Bool = false
}
