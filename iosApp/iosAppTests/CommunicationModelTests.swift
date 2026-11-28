import XCTest
import Shared
@testable import Wingmate

@MainActor
final class CommunicationModelTests: XCTestCase {
    private func makeModel(
        service: FakeCommunicationService = FakeCommunicationService(),
        settings: AppSettingsModel? = nil,
        speech: SpeechSettingsModel? = nil
    ) -> CommunicationModel {
        let settingsModel = settings ?? AppSettingsModel(service: StubAppSettingsService())
        let speechModel = speech ?? SpeechSettingsModel(
            service: StubSpeechService(),
            defaults: UserDefaults(suiteName: "CommunicationModelTests.\(UUID().uuidString)")!
        )
        return CommunicationModel(
            service: service,
            settings: settingsModel,
            speech: speechModel,
            removeFileAtPath: { _ in }
        )
    }

    func testStartObservingMirrorsSessionAndPhraseStoreAndCancelsPreviousSubscriptions() {
        let service = FakeCommunicationService()
        let model = makeModel(service: service)

        model.startObserving()
        XCTAssertEqual(service.sessionSubscriptions.count, 1)
        XCTAssertEqual(service.phraseSubscriptions.count, 1)

        let loadedState = FakeCommunicationService.makeSessionState(text: "Hello", isLoaded: true)
        service.emitSession(loadedState)
        XCTAssertEqual(model.input, "Hello")
        XCTAssertTrue(model.messageLoaded)

        model.startObserving()
        XCTAssertTrue(service.sessionSubscriptions[0].cancelled)
        XCTAssertTrue(service.phraseSubscriptions[0].cancelled)
    }

    func testEditsWaitUntilSessionLoaded() {
        let service = FakeCommunicationService()
        let model = makeModel(service: service)
        model.startObserving()

        // Before session reports isLoaded == true, input edits must not overwrite persisted state.
        service.emitSession(FakeCommunicationService.makeSessionState(text: "", isLoaded: false))
        model.onInputChanged("Premature")
        XCTAssertTrue(service.editedTexts.isEmpty)

        service.emitSession(FakeCommunicationService.makeSessionState(text: "Saved", isLoaded: true))
        model.onInputChanged("Saved!")
        XCTAssertEqual(service.editedTexts, ["Saved!"])
    }

    func testLoadTypingTraySetsFailureOnErrorAndClearsOnRetry() async {
        let service = FakeCommunicationService()
        service.typingTrayError = FakeCommError.storage
        let model = makeModel(service: service)

        await model.loadTypingTray()
        XCTAssertTrue(model.typingTrayFailed)
        XCTAssertNil(model.typingTray)

        service.typingTrayError = nil
        await model.loadTypingTray()
        XCTAssertFalse(model.typingTrayFailed)
        XCTAssertNotNil(model.typingTray)
    }

    func testLoadHistoryReversesOrderAndRespectsVisibilityAndLikelyNext() async {
        let service = FakeCommunicationService()
        let p1 = Shared.Phrase(id: "1", text: "First", name: nil, backgroundColor: nil, imageUrl: nil, parentId: nil, linkedBoardId: nil, createdAt: 1, recordingPath: nil, isGridItem: nil, isHidden: false)
        let p2 = Shared.Phrase(id: "2", text: "Second", name: nil, backgroundColor: nil, imageUrl: nil, parentId: nil, linkedBoardId: nil, createdAt: 2, recordingPath: nil, isGridItem: nil, isHidden: false)
        let next = Shared.Phrase(id: "n1", text: "Thanks", name: nil, backgroundColor: nil, imageUrl: nil, parentId: nil, linkedBoardId: nil, createdAt: 3, recordingPath: nil, isGridItem: nil, isHidden: false)
        service.historyResult = Shared.HistoryResult(status: .success, phrases: [p1, p2])
        service.likelyNextResult = Shared.HistoryResult(status: .success, phrases: [next])

        let settings = AppSettingsModel(service: StubAppSettingsService())
        _ = await settings.setLikelyNextPageEnabled(true).value
        let model = makeModel(service: service, settings: settings)

        await model.loadHistory()
        XCTAssertEqual(model.historyPhrases.map(\.text), ["Second", "First"])
        XCTAssertEqual(model.likelyNextPhrases.map(\.text), ["Thanks"])
        XCTAssertTrue(model.likelyNextAvailable)
        XCTAssertFalse(model.historyLoadFailed)

        _ = await settings.setHistoryVisible(false).value
        await model.loadHistory()
        XCTAssertTrue(model.historyPhrases.isEmpty)
        XCTAssertFalse(model.likelyNextAvailable)
    }

    func testLoadHistoryFailurePreservesExistingItemsAndFlagsFailure() async {
        let service = FakeCommunicationService()
        let p1 = Shared.Phrase(id: "1", text: "Hello", name: nil, backgroundColor: nil, imageUrl: nil, parentId: nil, linkedBoardId: nil, createdAt: 1, recordingPath: nil, isGridItem: nil, isHidden: false)
        service.historyResult = Shared.HistoryResult(status: .success, phrases: [p1])
        let model = makeModel(service: service)

        await model.loadHistory()
        XCTAssertEqual(model.historyPhrases.map(\.text), ["Hello"])

        service.historyError = FakeCommError.storage
        await model.loadHistory()
        XCTAssertEqual(model.historyPhrases.map(\.text), ["Hello"])
        XCTAssertTrue(model.historyLoadFailed)
    }

    func testAddPhraseAndCategoryTrimWhitespaceAndIgnoreBlanks() {
        let service = FakeCommunicationService()
        let model = makeModel(service: service)

        model.addPhrase(text: "   ")
        model.addCategory(name: "  \n ")
        XCTAssertTrue(service.phraseIntents.isEmpty)

        model.addPhrase(text: "  Water please  ", alternativeText: " Water ", imageUrl: "  ", recordingPath: nil)
        model.addCategory(name: "  Food  ")
        XCTAssertEqual(service.phraseIntents.count, 2)
    }

    func testCancelPendingTasksCancelsInFlightHistoryLoadWithoutError() async {
        let service = FakeCommunicationService()
        let started = expectation(description: "history load started")
        service.historyHook = {
            started.fulfill()
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        let model = makeModel(service: service)

        let task = Task { await model.loadHistory() }
        await fulfillment(of: [started], timeout: 2)
        model.cancelPendingTasks()
        await task.value

        XCTAssertFalse(model.historyLoadFailed)
    }
}

private enum FakeCommError: Error {
    case storage
}

private final class TrackingSubscription: CancellableSubscription {
    var cancelled = false
    func cancel() {
        cancelled = true
    }
}

private final class FakeCommunicationService: CommunicationService {
    var sessionState = FakeCommunicationService.makeSessionState(text: "", isLoaded: false)
    var sessionObserver: ((Shared.NativeCommunicationState) -> Void)?
    var phraseObserver: ((Shared.PhraseListStoreState) -> Void)?
    var sessionSubscriptions: [TrackingSubscription] = []
    var phraseSubscriptions: [TrackingSubscription] = []
    var editedTexts: [String] = []
    var phraseIntents: [Shared.PhraseListStoreIntent] = []

    var typingTrayError: Error? = nil
    var historyError: Error? = nil
    var historyHook: (() async throws -> Void)? = nil
    var historyResult = Shared.HistoryResult(status: .success, phrases: [])
    var likelyNextResult = Shared.HistoryResult(status: .success, phrases: [])

    static func makeSessionState(text: String, isLoaded: Bool) -> Shared.NativeCommunicationState {
        let parts = text.isEmpty ? [] : [
            Shared.MessagePart(
                displayText: text,
                spokenText: text,
                source: Shared.MessagePartSourceTyped.shared,
                languageTag: nil,
                recordingPath: nil,
                mathMode: false
            )
        ]
        return Shared.NativeCommunicationState(
            activeMessage: Shared.Message(parts: parts, languageSpans: [], editProvenance: []),
            heldMessage: nil,
            playback: .idle,
            notice: nil,
            isSaving: false,
            isLoaded: isLoaded
        )
    }

    func emitSession(_ state: Shared.NativeCommunicationState) {
        sessionState = state
        sessionObserver?(state)
    }

    func observeSession(_ onChange: @escaping (Shared.NativeCommunicationState) -> Void) -> any CancellableSubscription {
        sessionObserver = onChange
        let sub = TrackingSubscription()
        sessionSubscriptions.append(sub)
        return sub
    }

    func currentSessionState() -> Shared.NativeCommunicationState { sessionState }
    func reloadSessionAfterRestore() async throws {}
    func editText(_ newText: String) {
        editedTexts.append(newText)
        sessionState = Self.makeSessionState(text: newText, isLoaded: true)
    }
    func replaceRange(start: Int32, endExclusive: Int32, replacement: String) {}
    func appendScreenPart(text: String, screenId: String, pageId: String, buttonId: String, spellingMode: Bool, languageTag: String?, mathMode: Bool) {}
    func removeLastPart(spellingMode: Bool) {}
    func clearMessage() { sessionState = Self.makeSessionState(text: "", isLoaded: true) }
    func swapHeldMessage() {}
    func toggleSecondaryLanguage(start: Int32, endExclusive: Int32) {}
    func activatePhrase(phrase: Shared.Phrase, start: Int32, endExclusive: Int32, activationBehavior: Shared.BoardActivationBehavior?) -> Shared.NativePhraseActivation {
        Shared.NativePhraseActivation(cursor: start, shouldSpeak: true)
    }
    func speakMessage(voice: Shared.Voice?, cacheAudio: Bool) {}
    func speakText(_ text: String, voice: Shared.Voice?) {}
    func previewText(_ text: String, voice: Shared.Voice?, rateOverride: KotlinDouble?, languageTag: String?) {}
    func cancelPreview() {}
    func speakMessagePart(index: Int32, voice: Shared.Voice?) {}
    func speakPhrase(_ phrase: Shared.Phrase, voice: Shared.Voice?) {}
    func speakRecording(path: String, voice: Shared.Voice?) {}
    func pauseSpeech() {}
    func resumeSpeech() {}
    func stopSpeech() {}
    func dismissSessionNotice() {}
    func retrySessionStorage() {}

    func observePhrases(_ onChange: @escaping (Shared.PhraseListStoreState) -> Void) -> any CancellableSubscription {
        phraseObserver = onChange
        let sub = TrackingSubscription()
        phraseSubscriptions.append(sub)
        return sub
    }

    func acceptPhraseIntent(_ intent: Shared.PhraseListStoreIntent) { phraseIntents.append(intent) }
    func refreshPhrases() {}
    func updatePhraseRecording(phraseId: String, recordingPath: String?) {}

    func loadTypingTray() async throws -> Shared.TypingTray {
        if let typingTrayError { throw typingTrayError }
        return Shared.TypingTray(
            elements: [],
            gridColumns: 3,
            phraseColumns: 3,
            activationBehavior: .speakandadd
        )
    }

    func loadHistory() async throws -> Shared.HistoryResult {
        if let historyHook { try await historyHook() }
        if let historyError { throw historyError }
        return historyResult
    }

    func loadLikelyNext() async throws -> Shared.HistoryResult { likelyNextResult }
    func listBoardCells(boardId: String) async throws -> [Shared.IosBoardCell] { [] }
    func searchSymbols(query: String, locale: String, symbolPackage: String) async throws -> Shared.SymbolSearchResult {
        Shared.SymbolSearchResult(status: .success, symbols: [])
    }
    func observePredictions(context: String, maxWords: Int32, maxLetters: Int32, onUpdate: @escaping (Shared.PredictionUpdate) -> Void) -> any CancellableSubscription {
        TrackingSubscription()
    }
    func activateAudioPlayback() {}
}

private final class StubAppSettingsService: AppSettingsService {
    func loadSnapshot(fallbackUseSystemTts: Bool) async throws -> AppSettingsSnapshot { AppSettingsSnapshot() }
    func updateLikelyNextPageEnabled(_ enabled: Bool) async throws {}
    func updateSelectOnTouchDown(_ enabled: Bool) async throws {}
    func updateSelectOnTouchExit(_ enabled: Bool) async throws {}
    func updateSwipeBetweenPages(_ enabled: Bool) async throws {}
    func updateScanningEnabled(_ enabled: Bool) async throws {}
    func updateScanPlaybackAreaEnabled(_ enabled: Bool) async throws {}
    func updateScanInputFieldEnabled(_ enabled: Bool) async throws {}
    func updateScanPhraseGridEnabled(_ enabled: Bool) async throws {}
    func updateScanCategoryItemsEnabled(_ enabled: Bool) async throws {}
    func updateScanTopBarEnabled(_ enabled: Bool) async throws {}
    func updateScanPhraseGridOrder(_ order: String) async throws {}
    func updateScanDwellTimeSeconds(_ seconds: Float) async throws {}
    func updateScanAutoAdvanceSeconds(_ seconds: Float) async throws {}
    func updateShowLabels(_ enabled: Bool) async throws {}
    func updateShowSymbols(_ enabled: Bool) async throws {}
    func updateLabelAtTop(_ enabled: Bool) async throws {}
    func updateGridColumns(_ columns: Int32) async throws {}
    func updateHighContrastMode(_ enabled: Bool) async throws {}
    func updateWordTypeColorScheme(_ scheme: String) async throws {}
    func updateHoldToSelectMillis(_ millis: Int64) async throws {}
    func updateDwellToSelectMillis(_ millis: Int64) async throws {}
    func updateDwellRearmDelayMillis(_ millis: Int64) async throws {}
    func updateSelectKeyBinding(_ binding: String) async throws {}
    func updateRestModeKeyBinding(_ binding: String) async throws {}
    func updatePointerEmphasis(style: String, scale: Float) async throws {}
    func updateSelectionHighlightMillis(_ millis: Int64) async throws {}
    func updateSelectionDebounceMillis(_ millis: Int64) async throws {}
    func updateSelectionSoundEnabled(_ enabled: Bool) async throws {}
    func updateAuditoryFishingEnabled(_ enabled: Bool) async throws {}
    func updateRouteAuditoryPreviewToHeadphones(_ enabled: Bool) async throws {}
    func updateSpeechPolicy(_ policy: String) async throws {}
    func updateBoardShowMessageBar(_ enabled: Bool) async throws {}
    func updateBoardShowSpeakButton(_ enabled: Bool) async throws {}
    func updateUsageLoggingEnabled(_ enabled: Bool) async throws {}
    func updateHistoryVisible(_ visible: Bool) async throws {}
    func updateFeatureUsageReportingEnabled(_ enabled: Bool) async throws {}
    func updateStartupUsesScreens(_ enabled: Bool) async throws {}
    func updateStartupBoardSetId(_ id: String?) async throws {}
    func speechPolicySpeaksSelection(policy: String, behavior: String) -> Bool { true }
}

private final class StubSpeechService: SpeechSettingsService {
    func isAzureConfigured() async throws -> Bool { false }
    func isGoogleConfigured() async throws -> Bool { false }
    func isElevenLabsConfigured() async throws -> Bool { false }
    func saveGoogleApiKey(_ apiKey: String) async throws {}
    func clearGoogleApiKey() async throws {}
    func saveElevenLabsApiKey(_ apiKey: String) async throws {}
    func clearElevenLabsApiKey() async throws {}
    func selectedVoice() async throws -> Shared.Voice? { nil }
    func selectVoiceAndMaybeUpdatePrimary(_ voice: Shared.Voice) async throws {}
    func updateSelectedVoiceLanguage(_ lang: String) async throws {}
    func updateTtsEngineNamed(_ engine: String) async throws {}
    func languagePreferences() async throws -> LanguagePreferencesSnapshot { LanguagePreferencesSnapshot(primaryLanguage: "en-US", secondaryLanguage: "en-US") }
    func updateSecondaryLanguage(_ lang: String) async throws {}
}
