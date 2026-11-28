import XCTest
import Shared
@testable import Wingmate

@MainActor
final class ScreensModelTests: XCTestCase {
    private func makeModel(
        service: FakeScreensService = FakeScreensService(),
        editingAccessService: FakeScreensEditingAccessService = FakeScreensEditingAccessService(),
        onSpeak: @escaping (String) -> Void = { _ in }
    ) -> ScreensModel {
        let settings = AppSettingsModel(service: StubScreensAppSettingsService())
        let editingAccess = EditingAccessModel(service: editingAccessService)
        return ScreensModel(
            service: service,
            settings: settings,
            editingAccess: editingAccess,
            onSpeak: onSpeak
        )
    }

    func testLoadBoardSetsSortsByUpdatedAtSelectsNewestAndLoadsRootPage() async {
        let service = FakeScreensService()
        let older = FakeScreensService.makeBoardSet(id: "set-old", name: "Older", rootBoardId: "b-old", boardIds: ["b-old"], updatedAt: 1_000)
        let newer = FakeScreensService.makeBoardSet(id: "set-new", name: "Newer", rootBoardId: "b-home", boardIds: ["b-home", "b-food"], updatedAt: 5_000)
        service.boardSets = [older, newer]
        service.boardsById["b-home"] = FakeScreensService.makeBoard(id: "b-home", name: "Home")
        service.boardsById["b-food"] = FakeScreensService.makeBoard(id: "b-food", name: "Food")
        service.cellsByBoardId["b-home"] = [
            FakeScreensService.makeCell(row: 0, col: 0, buttonId: "btn-1", label: "Hello")
        ]

        let model = makeModel(service: service)
        await model.loadBoardSets()

        XCTAssertEqual(model.boardSets.map(\.id), ["set-new", "set-old"])
        XCTAssertEqual(model.selectedBoardSetId, "set-new")
        XCTAssertEqual(model.selectedBoardId, "b-home")
        XCTAssertEqual(model.boardDisplayName(id: "b-food"), "Food")
        XCTAssertEqual(model.boardCells.count, 1)
        XCTAssertEqual(model.cellAt(row: 0, col: 0)?.label, "Hello")
    }

    func testLoadBoardSetsFailureSurfacesLocalizedErrorAndClearsSelection() async {
        let service = FakeScreensService()
        service.listBoardSetsError = FakeScreensError.persistence
        let model = makeModel(service: service)

        await model.loadBoardSets()

        XCTAssertTrue(model.boardSets.isEmpty)
        XCTAssertNil(model.selectedBoardSetId)
        XCTAssertNil(model.selectedBoardId)
        XCTAssertEqual(model.boardStatusMessage, NSLocalizedString("board_sets.error.load_failed", comment: ""))
    }

    func testBoardNavigationStackBackHomeAndReturnBehavior() async {
        let service = FakeScreensService()
        let set = FakeScreensService.makeBoardSet(id: "set-1", name: "Core", rootBoardId: "home", boardIds: ["home", "food", "drinks"], updatedAt: 2_000)
        service.boardSets = [set]
        service.boardsById["home"] = FakeScreensService.makeBoard(id: "home", name: "Home")
        service.boardsById["food"] = FakeScreensService.makeBoard(id: "food", name: "Food")
        service.boardsById["drinks"] = FakeScreensService.makeBoard(id: "drinks", name: "Drinks")
        service.resolvedSettings = Shared.IosResolvedBoardSettings(
            showLabels: true,
            showSymbols: true,
            labelAtTop: false,
            showMessageBar: true,
            showSpeakButton: true,
            messageBarEditable: false,
            activationBehavior: "SpeakAndAdd",
            returnBehavior: "Previous",
            speechPolicy: nil,
            messageBarActions: ["Speak"],
            cellGapDp: nil,
            outerMarginDp: nil
        )

        let model = makeModel(service: service)
        await model.loadBoardSets()
        XCTAssertTrue(model.isOnStartPage)
        XCTAssertFalse(model.canNavigateBoardBack)

        model.pushBoardNavigationStack("home")
        await model.selectBoard(id: "food")
        XCTAssertFalse(model.isOnStartPage)
        XCTAssertTrue(model.canNavigateBoardBack)

        model.pushBoardNavigationStack("food")
        await model.selectBoard(id: "drinks")
        XCTAssertEqual(model.boardStack, ["home", "food"])

        await model.applyBoardReturnBehavior()
        XCTAssertEqual(model.selectedBoardId, "food")
        XCTAssertEqual(model.boardStack, ["home"])

        await model.navigateBoardBack()
        XCTAssertEqual(model.selectedBoardId, "home")
        XCTAssertTrue(model.boardStack.isEmpty)

        model.pushBoardNavigationStack("home")
        await model.selectBoard(id: "drinks")
        await model.navigateBoardHome()
        XCTAssertEqual(model.selectedBoardId, "home")
        XCTAssertTrue(model.boardStack.isEmpty)
    }

    func testStayOnCurrentPageOverridesReturnBehaviorAndResetsOnPageExit() async {
        let service = FakeScreensService()
        let set = FakeScreensService.makeBoardSet(id: "set-1", name: "Core", rootBoardId: "home", boardIds: ["home", "food", "drinks"], updatedAt: 2_000)
        service.boardSets = [set]
        service.boardsById["home"] = FakeScreensService.makeBoard(id: "home", name: "Home")
        service.boardsById["food"] = FakeScreensService.makeBoard(id: "food", name: "Food")
        service.boardsById["drinks"] = FakeScreensService.makeBoard(id: "drinks", name: "Drinks")
        service.resolvedSettings = Shared.IosResolvedBoardSettings(
            showLabels: true,
            showSymbols: true,
            labelAtTop: false,
            showMessageBar: true,
            showSpeakButton: true,
            messageBarEditable: false,
            activationBehavior: "SpeakAndAdd",
            returnBehavior: "Previous",
            speechPolicy: nil,
            messageBarActions: ["Speak"],
            cellGapDp: nil,
            outerMarginDp: nil
        )

        let model = makeModel(service: service)
        await model.loadBoardSets()
        XCTAssertFalse(model.canStayOnCurrentPage)
        XCTAssertFalse(model.stayOnCurrentPage)

        model.pushBoardNavigationStack("home")
        await model.selectBoard(id: "food")
        XCTAssertTrue(model.canStayOnCurrentPage)
        XCTAssertFalse(model.stayOnCurrentPage)

        model.toggleStayOnCurrentPage()
        XCTAssertTrue(model.stayOnCurrentPage)

        await model.applyBoardReturnBehavior()
        XCTAssertEqual(model.selectedBoardId, "food")
        XCTAssertEqual(model.boardStack, ["home"])
        XCTAssertTrue(model.stayOnCurrentPage)

        await model.navigateBoardBack()
        XCTAssertEqual(model.selectedBoardId, "home")
        XCTAssertFalse(model.stayOnCurrentPage)

        // Visiting the Page again restores its configured self-returning behavior.
        model.pushBoardNavigationStack("home")
        await model.selectBoard(id: "food")
        XCTAssertFalse(model.stayOnCurrentPage)
        await model.applyBoardReturnBehavior()
        XCTAssertEqual(model.selectedBoardId, "home")
    }

    func testLockedScreenBlocksEditsAndSetsLockedStatus() async {
        let service = FakeScreensService()
        let lockedSet = FakeScreensService.makeBoardSet(id: "set-locked", name: "Locked", rootBoardId: "home", boardIds: ["home"], isLocked: true, updatedAt: 1_000)
        service.boardSets = [lockedSet]
        service.boardsById["home"] = FakeScreensService.makeBoard(id: "home", name: "Home")

        let model = makeModel(service: service)
        await model.loadBoardSets()
        XCTAssertFalse(model.canEditSelectedBoardSet)

        await model.upsertSelectedBoardCell(
            row: 0,
            col: 0,
            label: "Water",
            vocalization: nil,
            backgroundColor: nil,
            borderColor: nil,
            linkedBoardId: nil,
            imageUrl: nil,
            clearImage: false,
            actions: [],
            wordType: nil
        )
        XCTAssertEqual(model.boardStatusMessage, NSLocalizedString("boardset.error.locked", comment: ""))
        XCTAssertEqual(service.upsertCalls, 0)

        await model.clearSelectedBoardCell(row: 0, col: 0)
        XCTAssertEqual(service.clearCalls, 0)
    }

    func testSetBoardSetSentenceCachingRollsBackAndSurfacesErrorOnFailure() async {
        let service = FakeScreensService()
        let set = FakeScreensService.makeBoardSet(id: "set-1", name: "Core", rootBoardId: "home", boardIds: ["home"], cacheWholeSentences: true, updatedAt: 1_000)
        service.boardSets = [set]
        service.boardsById["home"] = FakeScreensService.makeBoard(id: "home", name: "Home")
        service.sentenceCachingError = FakeScreensError.persistence

        let model = makeModel(service: service)
        await model.loadBoardSets()
        XCTAssertTrue(model.boardSets.first?.cacheWholeSentences == true)

        await model.setBoardSetSentenceCaching(id: "set-1", enabled: false).value

        XCTAssertEqual(model.boardSets.first?.cacheWholeSentences, true)
        XCTAssertEqual(model.boardStatusMessage, NSLocalizedString("boardset.error.save_failed", comment: ""))
    }

    func testSetPageBackgroundRequiresEditingAccessAndSurfacesFailure() async {
        let service = FakeScreensService()
        let set = FakeScreensService.makeBoardSet(id: "set-1", name: "Core", rootBoardId: "home", boardIds: ["home"], updatedAt: 1_000)
        service.boardSets = [set]
        service.boardsById["home"] = FakeScreensService.makeBoard(id: "home", name: "Home")

        let editingAccess = FakeScreensEditingAccessService(enabled: true, unlocked: false)
        let model = makeModel(service: service, editingAccessService: editingAccess)
        await model.loadBoardSets()

        // Locked editing access must not invoke background mutation.
        await model.setPageBackground(imageUrl: "file:///bg.png", fit: "Fill")
        XCTAssertEqual(service.backgroundImageCalls, 0)

        // Authorized editing access invokes mutation and surfaces failure.
        editingAccess.unlocked = true
        service.backgroundImageError = FakeScreensError.persistence
        await model.setPageBackground(imageUrl: "file:///bg.png", fit: "Fill")
        XCTAssertEqual(service.backgroundImageCalls, 1)
        XCTAssertEqual(model.boardStatusMessage, NSLocalizedString("boardset.background_failed", comment: ""))
    }

    func testBoardPredictionsObserveReplaceAndCancelSubscription() async {
        let service = FakeScreensService()
        let set = FakeScreensService.makeBoardSet(id: "set-1", name: "Core", rootBoardId: "home", boardIds: ["home"], updatedAt: 1_000)
        service.boardSets = [set]
        service.boardsById["home"] = FakeScreensService.makeBoard(id: "home", name: "Home")
        service.cellsByBoardId["home"] = [
            FakeScreensService.makeCell(row: 0, col: 0, buttonId: "pred-1", label: "", actions: [":prediction"]),
            FakeScreensService.makeCell(row: 0, col: 1, buttonId: "pred-2", label: "", actions: [":predictions"])
        ]

        let model = makeModel(service: service)
        await model.loadBoardSets()

        await model.refreshBoardPredictions(context: "I want")
        XCTAssertEqual(service.predictionSubscriptions.count, 1)

        service.emitPrediction(words: ["water", "food"])
        XCTAssertEqual(model.boardPrediction(for: "pred-1"), "water")
        XCTAssertEqual(model.boardPrediction(for: "pred-2"), "food")

        await model.refreshBoardPredictions(context: "I want to")
        XCTAssertTrue(service.predictionSubscriptions[0].cancelled)
        XCTAssertEqual(service.predictionSubscriptions.count, 2)

        model.stopBoardPredictions()
        XCTAssertTrue(service.predictionSubscriptions[1].cancelled)
        XCTAssertNil(model.boardPrediction(for: "pred-1"))
    }

    func testExportBoardSetSurfacesSuccessAndFailureStatus() async {
        let service = FakeScreensService()
        service.exportResult = Shared.BoardSetExportResult(status: .success, fileName: "Core.obz")
        let model = makeModel(service: service)

        await model.exportBoardSetAsObz(id: "set-1")
        XCTAssertEqual(
            model.boardStatusMessage,
            String(format: NSLocalizedString("boardset.export_obz_done", comment: ""), "Core.obz")
        )

        service.exportError = FakeScreensError.persistence
        await model.exportBoardSetAsGridset(id: "set-1")
        XCTAssertEqual(model.boardStatusMessage, NSLocalizedString("boardset.export_obz_failed", comment: ""))
    }

    func testCancelPendingTasksCancelsInFlightSentenceCachingWithoutError() async {
        let service = FakeScreensService()
        let set = FakeScreensService.makeBoardSet(id: "set-1", name: "Core", rootBoardId: "home", boardIds: ["home"], cacheWholeSentences: true, updatedAt: 1_000)
        service.boardSets = [set]
        service.boardsById["home"] = FakeScreensService.makeBoard(id: "home", name: "Home")

        let started = expectation(description: "sentence caching started")
        service.sentenceCachingHook = {
            started.fulfill()
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }

        let model = makeModel(service: service)
        await model.loadBoardSets()

        let task = model.setBoardSetSentenceCaching(id: "set-1", enabled: false)
        await fulfillment(of: [started], timeout: 2)
        model.cancelPendingTasks()
        await task.value

        XCTAssertNil(model.boardStatusMessage)
    }
}

private enum FakeScreensError: Error {
    case persistence
}

private final class TrackingScreensSubscription: CancellableSubscription {
    var cancelled = false
    func cancel() {
        cancelled = true
    }
}

private final class FakeScreensEditingAccessService: EditingAccessService {
    var enabled: Bool
    var unlocked: Bool

    init(enabled: Bool = false, unlocked: Bool = true) {
        self.enabled = enabled
        self.unlocked = unlocked
    }

    func state() async throws -> EditingAccessSnapshot {
        EditingAccessSnapshot(enabled: enabled, unlocked: unlocked, supported: true, retryNotBefore: 0)
    }
    func unlock(code: String) async throws -> Bool { true }
    func configure(code: String) async throws {}
    func disable(code: String) async throws -> Bool { true }
    func lock() { unlocked = false }
}

private final class StubScreensAppSettingsService: AppSettingsService {
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

private final class FakeScreensService: ScreensService {
    var boardSets: [Shared.ObfBoardSet] = []
    var boardsById: [String: Shared.ObfBoard] = [:]
    var cellsByBoardId: [String: [Shared.IosBoardCell]] = [:]
    var fieldsByBoardId: [String: [Shared.IosBoardFieldItem]] = [:]
    var resolvedSettings = Shared.IosResolvedBoardSettings(
        showLabels: true,
        showSymbols: true,
        labelAtTop: false,
        showMessageBar: true,
        showSpeakButton: true,
        messageBarEditable: false,
        activationBehavior: "SpeakAndAdd",
        returnBehavior: "Stay",
        speechPolicy: nil,
        messageBarActions: ["Speak"],
        cellGapDp: nil,
        outerMarginDp: nil
    )
    var listBoardSetsError: Error? = nil
    var sentenceCachingError: Error? = nil
    var sentenceCachingHook: (() async throws -> Void)? = nil
    var backgroundImageError: Error? = nil
    var exportError: Error? = nil
    var exportResult = Shared.BoardSetExportResult(status: .success, fileName: "Screen.obz")

    var upsertCalls = 0
    var clearCalls = 0
    var backgroundImageCalls = 0
    var predictionObserver: ((Shared.PredictionUpdate) -> Void)?
    var predictionSubscriptions: [TrackingScreensSubscription] = []

    static func makeBoardSet(
        id: String,
        name: String,
        rootBoardId: String,
        boardIds: [String],
        isLocked: Bool = false,
        cacheWholeSentences: Bool = true,
        updatedAt: Int64
    ) -> Shared.ObfBoardSet {
        Shared.ObfBoardSet(
            id: id,
            name: name,
            rootBoardId: rootBoardId,
            boardIds: boardIds,
            isLocked: isLocked,
            cacheWholeSentences: cacheWholeSentences,
            screenSettings: Shared.BoardSettingsOverrides(
                showLabels: nil,
                showSymbols: nil,
                labelAtTop: nil,
                showMessageBar: nil,
                showSpeakButton: nil,
                messageBarEditable: nil,
                activationBehavior: nil,
                returnBehavior: nil,
                speechPolicy: nil,
                cacheSpeech: nil,
                messageBarActions: nil,
                cellGapDp: nil,
                outerMarginDp: nil
            ),
            kind: .user,
            remoteLink: nil,
            createdAt: updatedAt,
            updatedAt: updatedAt
        )
    }

    static func makeBoard(id: String, name: String) -> Shared.ObfBoard {
        Shared.ObfBoard(
            format: "open-board-0.1",
            id: id,
            locale: "en-US",
            url: nil,
            name: name,
            descriptionHtml: nil,
            backgroundColor: nil,
            buttons: [],
            images: [],
            grid: Shared.ObfGrid(rows: 2, columns: 2, order: [[NSNull(), NSNull()], [NSNull(), NSNull()]]),
            sounds: [],
            strings: [:],
            license: nil,
            extensions: [:]
        )
    }

    static func makeCell(
        row: Int32,
        col: Int32,
        buttonId: String,
        label: String?,
        actions: [String] = []
    ) -> Shared.IosBoardCell {
        Shared.IosBoardCell(
            row: row,
            col: col,
            buttonId: buttonId,
            label: label,
            vocalization: nil,
            backgroundColor: nil,
            resolvedBackgroundColor: nil,
            wordType: nil,
            borderColor: nil,
            linkedBoardId: nil,
            imageId: nil,
            imageUrl: nil,
            hidden: false,
            actions: actions,
            soundId: nil,
            soundDataUrl: nil,
            soundPath: nil,
            shape: "rounded",
            language: nil,
            mathMode: false
        )
    }

    func emitPrediction(words: [String]) {
        predictionObserver?(
            Shared.PredictionUpdate(
                status: .success,
                predictions: Shared.PredictionResult(words: words, letters: [])
            )
        )
    }

    func listBoardSets() async throws -> [Shared.ObfBoardSet] {
        if let listBoardSetsError { throw listBoardSetsError }
        return boardSets
    }

    func getBoard(id: String) async throws -> Shared.ObfBoard? { boardsById[id] }
    func loadBoardSnapshot(boardId: String) async throws -> Shared.IosBoardSnapshot? {
        guard let board = boardsById[boardId] else { return nil }
        return Shared.IosBoardSnapshot(
            board: board,
            settings: resolvedSettings,
            cells: cellsByBoardId[boardId] ?? [],
            fieldItems: fieldsByBoardId[boardId] ?? []
        )
    }
    func listBoardCells(boardId: String) async throws -> [Shared.IosBoardCell] { cellsByBoardId[boardId] ?? [] }
    func listBoardFieldItems(boardId: String) async throws -> [Shared.IosBoardFieldItem] { fieldsByBoardId[boardId] ?? [] }
    func resolveBoardSettings(boardId: String) async throws -> Shared.IosResolvedBoardSettings { resolvedSettings }
    func availableFieldSpans(boardId: String, row: Int32, col: Int32) async throws -> [Shared.IosGridFieldSpan] { [] }
    func resizeBoardField(boardId: String, row: Int32, col: Int32, rowSpan: Int32, columnSpan: Int32) async throws {}
    func moveBoardField(boardId: String, fromRow: Int32, fromCol: Int32, toRow: Int32, toCol: Int32) async throws -> Bool { true }

    func updateBoardSetSentenceCaching(id: String, enabled: Bool) async throws -> Shared.ObfBoardSet {
        if let sentenceCachingHook { try await sentenceCachingHook() }
        if let sentenceCachingError { throw sentenceCachingError }
        return boardSets.first(where: { $0.id == id }) ?? Self.makeBoardSet(id: id, name: "Screen", rootBoardId: "home", boardIds: ["home"], cacheWholeSentences: enabled, updatedAt: 1)
    }

    func previewSpeech(name: String, text: String, paragraphsPerPage: Int32) throws -> [Shared.SpeechImportPage] { [] }
    func importSpeech(name: String, text: String, paragraphsPerPage: Int32, previousLabel: String, nextLabel: String) async throws -> Shared.ObfBoardSet {
        Self.makeBoardSet(id: "speech-1", name: name, rootBoardId: "home", boardIds: ["home"], updatedAt: 1)
    }
    func createBoardSet(name: String, rows: Int32, columns: Int32, rootBoardName: String) async throws -> Shared.ObfBoardSet {
        Self.makeBoardSet(id: "new-1", name: name, rootBoardId: "home", boardIds: ["home"], updatedAt: 1)
    }
    func createKeyboardBoardSet(name: String, preset: String) async throws -> Shared.ObfBoardSet {
        Self.makeBoardSet(id: "kb-1", name: name, rootBoardId: "home", boardIds: ["home"], updatedAt: 1)
    }
    func createBoard(boardSetId: String, name: String, rows: Int32, columns: Int32) async throws -> Shared.ObfBoard {
        Self.makeBoard(id: "new-page", name: name)
    }
    func createKeyboardBoard(boardSetId: String, name: String, rows: Int32, columns: Int32, layout: String) async throws -> Shared.ObfBoard {
        Self.makeBoard(id: "new-kb-page", name: name)
    }
    func setBoardBackgroundImage(boardSetId: String, boardId: String, imageUrl: String?, fit: String) async throws -> Shared.ObfBoard {
        backgroundImageCalls += 1
        if let backgroundImageError { throw backgroundImageError }
        return boardsById[boardId] ?? Self.makeBoard(id: boardId, name: "Page")
    }
    func backgroundImageUrl(board: Shared.ObfBoard) -> String? { nil }
    func backgroundImageFit(board: Shared.ObfBoard) -> String { "Fill" }
    func boardKeyboardLayout(board: Shared.ObfBoard) -> String? { nil }
    func boardUsesSpellingMode(board: Shared.ObfBoard) -> Bool { false }
    func upsertBoardCellButton(
        boardId: String,
        row: Int32,
        col: Int32,
        label: String?,
        vocalization: String?,
        backgroundColor: String?,
        borderColor: String?,
        linkedBoardId: String?,
        imageUrl: String?,
        clearImage: Bool,
        actions: [String],
        wordType: String?,
        applyAppearance: Bool,
        hidden: Bool,
        shape: String?,
        language: String?,
        mathMode: Bool,
        recordingPath: String?,
        clearRecording: Bool
    ) async throws -> Shared.ObfBoard {
        upsertCalls += 1
        return boardsById[boardId] ?? Self.makeBoard(id: boardId, name: "Page")
    }
    func clearBoardCellButton(boardId: String, row: Int32, col: Int32) async throws -> Shared.ObfBoard {
        clearCalls += 1
        return boardsById[boardId] ?? Self.makeBoard(id: boardId, name: "Page")
    }
    func touchBoardSet(id: String) async throws -> Shared.ObfBoardSet {
        boardSets.first(where: { $0.id == id }) ?? Self.makeBoardSet(id: id, name: "Screen", rootBoardId: "home", boardIds: ["home"], updatedAt: 1)
    }
    func renameBoardSet(boardSetId: String, name: String) async throws -> Shared.ObfBoardSet {
        Self.makeBoardSet(id: boardSetId, name: name, rootBoardId: "home", boardIds: ["home"], updatedAt: 1)
    }
    func renameBoard(boardSetId: String, boardId: String, name: String) async throws -> Shared.ObfBoard {
        Self.makeBoard(id: boardId, name: name)
    }
    func resizeBoard(boardSetId: String, boardId: String, rows: Int32, columns: Int32) async throws -> Shared.ObfBoard {
        Self.makeBoard(id: boardId, name: "Page")
    }
    func setBoardBackgroundColor(boardSetId: String, boardId: String, backgroundColor: String?) async throws -> Shared.ObfBoard {
        Self.makeBoard(id: boardId, name: "Page")
    }
    func boardSettingsDraft(boardSetId: String, boardId: String, forPage: Bool) async throws -> Shared.IosBoardSettingsDraft {
        Shared.IosBoardSettingsDraft(
            showLabels: "inherit",
            showSymbols: "inherit",
            labelAtTop: "inherit",
            showMessageBar: "inherit",
            showSpeakButton: "inherit",
            messageBarEditable: "inherit",
            activationBehavior: "inherit",
            returnBehavior: "inherit",
            cacheSpeech: "inherit",
            cellGapDp: nil,
            outerMarginDp: nil
        )
    }
    func updateMessageBarActions(boardSetId: String, actions: [String]) async throws {}
    func updateBoardSettings(
        boardSetId: String,
        boardId: String,
        forPage: Bool,
        showLabels: String,
        showSymbols: String,
        labelAtTop: String,
        showMessageBar: String,
        showSpeakButton: String,
        messageBarEditable: String,
        activationBehavior: String,
        returnBehavior: String,
        cacheSpeech: String,
        cellGapDp: Int32?,
        outerMarginDp: Int32?
    ) async throws {}
    func setRootBoard(boardSetId: String, boardId: String) async throws -> Shared.ObfBoardSet {
        Self.makeBoardSet(id: boardSetId, name: "Screen", rootBoardId: boardId, boardIds: [boardId], updatedAt: 1)
    }
    func deleteBoard(boardSetId: String, boardId: String) async throws -> Shared.ObfBoardSet {
        Self.makeBoardSet(id: boardSetId, name: "Screen", rootBoardId: "home", boardIds: ["home"], updatedAt: 1)
    }
    func toggleBoardSetLocked(id: String) async throws -> Shared.ObfBoardSet {
        Self.makeBoardSet(id: id, name: "Screen", rootBoardId: "home", boardIds: ["home"], isLocked: true, updatedAt: 1)
    }
    func deleteBoardSet(id: String) async throws {}
    func deleteRemoteScreen(screenId: String) async throws -> Shared.RemoteScreenResult {
        Shared.RemoteScreenResult(status: "ok", message: "Deleted")
    }
    func duplicateBoardSet(id: String) async throws -> Shared.ObfBoardSet {
        Self.makeBoardSet(id: "dup-1", name: "Copy", rootBoardId: "home", boardIds: ["home"], updatedAt: 1)
    }
    func shareBoardSetAsObz(id: String) async throws -> Shared.BoardSetExportResult {
        if let exportError { throw exportError }
        return exportResult
    }
    func shareBoardSetAsGridset(id: String) async throws -> Shared.BoardSetExportResult {
        if let exportError { throw exportError }
        return exportResult
    }
    func syncRemoteScreensAutomatic(force: Bool) async throws -> Bool { false }
    func updateBoardSetSpeechCacheOnline(online: Bool) {}
    func cacheAllBoardSetFields() async throws {}
    func retryBoardSetSpeechCaching() async throws {}
    func boardReturnBehavior(behavior: String, currentBoardId: String?, boardStack: [String], rootBoardId: String, stayOnCurrentPage: Bool) -> Shared.IosBoardReturnResult {
        if stayOnCurrentPage {
            return Shared.IosBoardReturnResult(boardId: currentBoardId, boardStack: boardStack)
        }
        if behavior == "Previous", let prev = boardStack.last {
            return Shared.IosBoardReturnResult(boardId: prev, boardStack: Array(boardStack.dropLast()))
        }
        if behavior == "StartPage" {
            return Shared.IosBoardReturnResult(boardId: rootBoardId, boardStack: [])
        }
        return Shared.IosBoardReturnResult(boardId: currentBoardId, boardStack: boardStack)
    }
    func canStayOnCurrentPage(behavior: String, currentBoardId: String?, boardStack: [String], rootBoardId: String) -> Bool {
        guard behavior != "Stay", let currentBoardId else { return false }
        let result = boardReturnBehavior(
            behavior: behavior,
            currentBoardId: currentBoardId,
            boardStack: boardStack,
            rootBoardId: rootBoardId,
            stayOnCurrentPage: false
        )
        return result.boardId != nil && result.boardId != currentBoardId
    }
    func nGramPredictionInsertion(sentence: String, suggestion: String) -> String { suggestion }
    func boardButtonIsVisible(hidden: Bool, isEditMode: Bool, showHiddenButtons: Bool) -> Bool {
        !hidden || isEditMode || showHiddenButtons
    }
    func boardFieldFontScale(rowSpan: Int32, columnSpan: Int32) -> Float { 1.0 }
    func observePredictions(context: String, maxWords: Int32, maxLetters: Int32, onChange: @escaping (Shared.PredictionUpdate) -> Void) -> any CancellableSubscription {
        predictionObserver = onChange
        let sub = TrackingScreensSubscription()
        predictionSubscriptions.append(sub)
        return sub
    }
}
