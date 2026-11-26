import XCTest
import Shared
@testable import Wingmate

final class TouchAccessParityTests: XCTestCase {

    func testTouchExitSlideExploreActivateAndCancelOutside() {
        let controller = TouchExitController()

        let begin = controller.begin(targetId: "btn-1")
        XCTAssertEqual((begin as? TouchExitEffectPreview)?.targetId, "btn-1")
        XCTAssertEqual(controller.state.activeTargetId, "btn-1")

        // Moving inside the same Button does not re-emit preview.
        XCTAssertNil(controller.move(targetId: "btn-1"))

        // Sliding to a neighbor updates highlight and emits preview.
        let moved = controller.move(targetId: "btn-2")
        XCTAssertEqual((moved as? TouchExitEffectPreview)?.targetId, "btn-2")
        XCTAssertEqual(controller.state.activeTargetId, "btn-2")

        // Lifting on the Button activates it and clears highlight.
        let release = controller.release(targetId: "btn-2")
        XCTAssertEqual((release as? TouchExitEffectActivate)?.targetId, "btn-2")
        XCTAssertFalse(controller.state.isTracking)
        XCTAssertNil(controller.state.activeTargetId)

        // Sliding off and lifting outside cancels safely.
        _ = controller.begin(targetId: "btn-1")
        _ = controller.move(targetId: nil)
        XCTAssertNil(controller.state.activeTargetId)
        XCTAssertTrue(controller.release(targetId: nil) is TouchExitEffectCancelled)
        XCTAssertFalse(controller.state.isTracking)
    }

    func testKeyguardSpacingCustomGapLeavesGutterUnselectable() {
        let layout = BoardFoldLayout.compute(
            rows: 2,
            columns: 2,
            contentWidth: 400,
            contentHeight: 300,
            gap: 24,
            gridInset: 20,
            division: nil
        )
        let leftCell = layout.frame(row: 0, column: 0, rowSpan: 1, columnSpan: 1)
        let rightCell = layout.frame(row: 0, column: 1, rowSpan: 1, columnSpan: 1)
        XCTAssertEqual(rightCell.minX - leftCell.maxX, 24, accuracy: 0.01)

        // Point inside the 24pt keyguard gap hits neither cell.
        let gutterPoint = CGPoint(x: leftCell.maxX + 12, y: leftCell.midY)
        XCTAssertNil(layout.cellAddress(at: gutterPoint))
        XCTAssertFalse(leftCell.contains(gutterPoint))
        XCTAssertFalse(rightCell.contains(gutterPoint))
    }

    func testDwellRearmDelayAppliesToSharedAccessInputController() {
        let controller = AccessInputController()
        controller.rearmDelayMillis = 200
        controller.targetEntered(targetId: "board:btn-1", nowMillis: 0)

        XCTAssertNil(controller.tick(nowMillis: 600, dwellMillis: 500))
        let activated = controller.tick(nowMillis: 700, dwellMillis: 500)
        XCTAssertEqual(activated?.targetId, "board:btn-1")
    }

    func testPhraseSubtreeCapturesCategoryAndChildrenInParentFirstOrderForUndo() {
        let category = Phrase(
            id: "cat-1",
            text: "Food",
            name: nil,
            backgroundColor: nil,
            imageUrl: nil,
            parentId: nil,
            linkedBoardId: "cat-1",
            createdAt: 1,
            recordingPath: nil,
            isGridItem: KotlinBoolean(value: false),
            isHidden: false
        )
        let child = Phrase(
            id: "p-1",
            text: "Apple",
            name: nil,
            backgroundColor: nil,
            imageUrl: nil,
            parentId: "cat-1",
            linkedBoardId: nil,
            createdAt: 2,
            recordingPath: "/tmp/apple.m4a",
            isGridItem: KotlinBoolean(value: true),
            isHidden: false
        )
        let subtree = PhraseTreeKt.phraseSubtree(phrases: [category, child], rootId: "cat-1")
        XCTAssertEqual(subtree.map(\.id), ["cat-1", "p-1"])
    }

    func testTouchExitPrecedenceAndPageSwipeGating() {
        XCTAssertTrue(
            TouchExitControllerKt.touchExitIsActive(
                isEditMode: false,
                holdToSelectMillis: 0,
                selectOnTouchDown: false,
                selectOnTouchExit: true
            )
        )
        XCTAssertFalse(
            TouchExitControllerKt.touchExitIsActive(
                isEditMode: true,
                holdToSelectMillis: 0,
                selectOnTouchDown: false,
                selectOnTouchExit: true
            )
        )
        XCTAssertFalse(
            TouchExitControllerKt.touchExitIsActive(
                isEditMode: false,
                holdToSelectMillis: 200,
                selectOnTouchDown: false,
                selectOnTouchExit: true
            )
        )
        XCTAssertFalse(
            TouchExitControllerKt.pageSwipeIsActive(
                isEditMode: false,
                swipeBetweenPages: true,
                holdToSelectMillis: 0,
                selectOnTouchDown: false,
                selectOnTouchExit: true,
                disableOnHoldToSelect: true
            )
        )
        XCTAssertTrue(
            TouchExitControllerKt.pageSwipeIsActive(
                isEditMode: false,
                swipeBetweenPages: true,
                holdToSelectMillis: 200,
                selectOnTouchDown: false,
                selectOnTouchExit: true,
                disableOnHoldToSelect: false
            )
        )
    }

    func testEffectiveKeyguardSpacingClampsNarrowViewportAndPreservesPageZeroOverride() {
        let narrow = BoardSettingsKt.effectiveKeyguardSpacing(
            viewportWidthDp: 390,
            viewportHeightDp: 600,
            columns: 7,
            rows: 4,
            desiredHorizontalGapDp: Double(BoardSettingsKt.MAX_KEYGUARD_CELL_GAP_DP),
            desiredVerticalGapDp: Double(BoardSettingsKt.MAX_KEYGUARD_CELL_GAP_DP),
            desiredOuterMarginDp: Double(BoardSettingsKt.MAX_KEYGUARD_OUTER_MARGIN_DP),
            minimumCellSizeDp: BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP,
            heightFraction: 1.0,
            constrainHeight: true
        )
        let gap = min(narrow.horizontalGapDp, narrow.verticalGapDp)
        let cellWidth = (390 - narrow.outerMarginDp * 2 - gap * 6) / 7
        XCTAssertGreaterThanOrEqual(cellWidth, BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP - 0.001)

        // Saved gridHeightFraction (50% of 400pt with 6 rows and 48pt gap) clamps against final grid height.
        let halfHeight = BoardSettingsKt.effectiveKeyguardSpacing(
            viewportWidthDp: 400,
            viewportHeightDp: 400,
            columns: 4,
            rows: 6,
            desiredHorizontalGapDp: Double(BoardSettingsKt.MAX_KEYGUARD_CELL_GAP_DP),
            desiredVerticalGapDp: Double(BoardSettingsKt.MAX_KEYGUARD_CELL_GAP_DP),
            desiredOuterMarginDp: 8,
            minimumCellSizeDp: BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP,
            heightFraction: 0.5,
            constrainHeight: true
        )
        let halfGap = min(halfHeight.horizontalGapDp, halfHeight.verticalGapDp)
        let halfContentHeight = (400 - halfHeight.outerMarginDp * 2) * halfHeight.effectiveHeightFraction
        let halfCellHeight = (halfContentHeight - halfGap * 5) / 6
        XCTAssertGreaterThanOrEqual(halfCellHeight, BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP - 0.001)

        // Small saved fraction (0.30 on 400pt with 6 rows) lifts effectiveHeightFraction so cells stay >= 24pt.
        let tinyFraction = BoardSettingsKt.effectiveKeyguardSpacing(
            viewportWidthDp: 400,
            viewportHeightDp: 400,
            columns: 4,
            rows: 6,
            desiredHorizontalGapDp: 16,
            desiredVerticalGapDp: 16,
            desiredOuterMarginDp: 8,
            minimumCellSizeDp: BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP,
            heightFraction: 0.30,
            constrainHeight: true
        )
        let tinyGap = min(tinyFraction.horizontalGapDp, tinyFraction.verticalGapDp)
        let tinyContentHeight = (400 - tinyFraction.outerMarginDp * 2) * tinyFraction.effectiveHeightFraction
        let tinyCellHeight = (tinyContentHeight - tinyGap * 5) / 6
        XCTAssertGreaterThanOrEqual(tinyCellHeight, BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP - 0.001)

        let tabletFraction = BoardSettingsKt.effectiveKeyguardSpacing(
            viewportWidthDp: 1000,
            viewportHeightDp: 1000,
            columns: 4,
            rows: 6,
            desiredHorizontalGapDp: 16,
            desiredVerticalGapDp: 16,
            desiredOuterMarginDp: 8,
            minimumCellSizeDp: BoardSettingsKt.MIN_KEYGUARD_CELL_SIZE_DP,
            heightFraction: 0.30,
            constrainHeight: true
        )
        XCTAssertEqual(tabletFraction.effectiveHeightFraction, 0.30, accuracy: 0.001)

        // Explicit 0 pt on a Page overrides a non-zero Screen keyguard spacing.
        let screen = BoardSettingsOverrides(
            showLabels: nil, showSymbols: nil, labelAtTop: nil,
            showMessageBar: nil, showSpeakButton: nil, messageBarEditable: nil,
            activationBehavior: nil, returnBehavior: nil, speechPolicy: nil,
            cacheSpeech: nil, messageBarActions: nil,
            cellGapDp: KotlinInt(int: 20), outerMarginDp: KotlinInt(int: 24)
        )
        let page = BoardSettingsOverrides(
            showLabels: nil, showSymbols: nil, labelAtTop: nil,
            showMessageBar: nil, showSpeakButton: nil, messageBarEditable: nil,
            activationBehavior: nil, returnBehavior: nil, speechPolicy: nil,
            cacheSpeech: nil, messageBarActions: nil,
            cellGapDp: KotlinInt(int: 0), outerMarginDp: KotlinInt(int: 0)
        )
        let resolved = BoardSettingsKt.resolveBoardSettings(
            appShowLabels: true,
            appShowSymbols: true,
            appLabelAtTop: false,
            appShowMessageBar: true,
            appShowSpeakButton: true,
            appMessageBarEditable: true,
            appActivationBehavior: .speakandadd,
            appReturnBehavior: .stay,
            screen: screen,
            page: page
        )
        XCTAssertEqual(resolved.cellGapDp?.intValue, 0)
        XCTAssertEqual(resolved.outerMarginDp?.intValue, 0)
    }

    @MainActor
    func testIosViewModelSyncsRearmDelayAtInit() {
        let model = IosViewModel()
        XCTAssertEqual(model.accessInput.rearmDelayMillis, ModelKt.DEFAULT_DWELL_REARM_DELAY_MILLIS)
        model.setDwellRearmDelayMillis(250)
        XCTAssertEqual(model.accessInput.rearmDelayMillis, 250)
    }

    @MainActor
    func testIosPhraseUndoRemainsRecoverableAfterFailedRestore() {
        let model = IosViewModel()
        let idleUndo = Shared.PhraseListStoreDeletedPhraseUndo(
            id: 42,
            phrases: [],
            originalIndices: [],
            includesSubItems: false,
            restoreStatus: .idle
        )
        let deletedState = Shared.PhraseListStoreState(
            phrases: [],
            categories: [],
            selectedCategoryId: nil,
            isLoading: false,
            error: nil,
            lastDeletedUndo: idleUndo
        )
        model.syncPhraseUndo(from: deletedState)
        XCTAssertEqual(model.pendingPhraseUndo, PendingPhraseUndo(id: 42, includesSubItems: false, isRestoring: false))

        // Tapping Undo disables Undo and Dismiss while restore is in flight and keeps the banner visible.
        model.undoPendingPhraseDeletion()
        XCTAssertEqual(model.pendingPhraseUndo, PendingPhraseUndo(id: 42, includesSubItems: false, isRestoring: true))

        // Dismiss is ignored while restore is in flight.
        model.dismissPendingPhraseUndo()
        XCTAssertEqual(model.pendingPhraseUndo, PendingPhraseUndo(id: 42, includesSubItems: false, isRestoring: true))

        // An unrelated store error while restoreStatus is still .restoring must NOT re-enable Undo.
        let restoringUndo = Shared.PhraseListStoreDeletedPhraseUndo(
            id: 42,
            phrases: [],
            originalIndices: [],
            includesSubItems: false,
            restoreStatus: .restoring
        )
        let unrelatedErrorState = Shared.PhraseListStoreState(
            phrases: [],
            categories: [],
            selectedCategoryId: nil,
            isLoading: false,
            error: "unrelated update error",
            lastDeletedUndo: restoringUndo
        )
        model.syncPhraseUndo(from: unrelatedErrorState)
        XCTAssertEqual(model.pendingPhraseUndo, PendingPhraseUndo(id: 42, includesSubItems: false, isRestoring: true))

        // A restore failure scoped to this undo entry re-enables Undo so the Communicator can retry.
        let failedUndo = Shared.PhraseListStoreDeletedPhraseUndo(
            id: 42,
            phrases: [],
            originalIndices: [],
            includesSubItems: false,
            restoreStatus: .failed
        )
        let failedState = Shared.PhraseListStoreState(
            phrases: [],
            categories: [],
            selectedCategoryId: nil,
            isLoading: false,
            error: "disk full",
            lastDeletedUndo: failedUndo
        )
        model.syncPhraseUndo(from: failedState)
        XCTAssertEqual(model.pendingPhraseUndo, PendingPhraseUndo(id: 42, includesSubItems: false, isRestoring: false))

        // Retrying and succeeding clears lastDeletedUndo and hides the banner.
        model.undoPendingPhraseDeletion()
        let restoredState = Shared.PhraseListStoreState(
            phrases: [],
            categories: [],
            selectedCategoryId: nil,
            isLoading: false,
            error: nil,
            lastDeletedUndo: nil
        )
        model.syncPhraseUndo(from: restoredState)
        XCTAssertNil(model.pendingPhraseUndo)
    }

    @MainActor
    func testIosTouchExitBlocksStalePageHandoffUntilNewPageCellsLoad() async {
        let model = IosViewModel()
        model.selectionDebounceMillis = 500
        let pageA = ObfBoard(
            format: "open-board-0.1",
            id: "page-a",
            locale: "en",
            url: nil,
            name: "Page A",
            descriptionHtml: nil,
            backgroundColor: nil,
            buttons: [],
            images: [],
            grid: ObfGrid(rows: 1, columns: 2, order: [["b1", "b2"]]),
            sounds: [],
            strings: [:],
            license: nil,
            extensions: [:]
        )
        let pageB = ObfBoard(
            format: "open-board-0.1",
            id: "page-b",
            locale: "en",
            url: nil,
            name: "Page B",
            descriptionHtml: nil,
            backgroundColor: nil,
            buttons: [],
            images: [],
            grid: ObfGrid(rows: 1, columns: 2, order: [["b1", "b2"]]),
            sounds: [],
            strings: [:],
            license: nil,
            extensions: [:]
        )
        let staleCell = BoardCellInfo(
            row: 0,
            col: 0,
            buttonId: "b1",
            label: "Old Button",
            vocalization: nil,
            backgroundColor: nil,
            resolvedBackgroundColor: nil,
            wordType: nil,
            borderColor: nil,
            linkedBoardId: nil,
            imageId: nil,
            imageUrl: nil,
            hidden: false,
            actions: [],
            soundId: nil,
            soundDataUrl: nil,
            soundPath: nil,
            shape: "rectangle",
            language: nil,
            mathMode: false
        )

        model.selectedBoardId = "page-a"
        model.selectedBoard = pageA
        model.loadedBoardCellsPageId = "page-a"
        XCTAssertTrue(model.boardCellsReady(for: "page-a"))
        XCTAssertFalse(model.boardCellsReady(for: "page-b"))

        // Starting navigation to page-b immediately invalidates page-a before page-b loads.
        model.loadedBoardCellsPageId = nil
        model.selectedBoardId = "page-b"
        XCTAssertFalse(model.boardCellsReady(for: "page-a"))
        XCTAssertFalse(model.boardCellsReady(for: "page-b"))

        // Attempting activation of an old cell during handoff must be ignored before debounce/speech.
        await model.activateBoardCell(staleCell, onPageId: "page-a", checkDebounce: true)

        // Rendering page-b before its cells arrive still keeps activation gated.
        model.selectedBoard = pageB
        XCTAssertFalse(model.boardCellsReady(for: "page-b"))
        await model.activateBoardCell(staleCell, onPageId: "page-b", checkDebounce: true)

        // Neither stale attempt consumed the debounce slot for page-a or page-b.
        XCTAssertTrue(model.acceptActivation(targetId: model.screenButtonDebounceKey(pageId: "page-a", buttonId: "b1")))
        XCTAssertTrue(model.acceptActivation(targetId: model.screenButtonDebounceKey(pageId: "page-b", buttonId: "b1")))

        // Once page-b's cells finish loading, Touch Exit becomes active on page-b.
        model.loadedBoardCellsPageId = "page-b"
        XCTAssertTrue(model.boardCellsReady(for: "page-b"))
    }

    @MainActor
    func testIosScreenButtonDebounceIsScopedByPageId() {
        let model = IosViewModel()
        model.selectionDebounceMillis = 500
        model.selectedBoardId = "page-a"

        XCTAssertTrue(model.acceptActivation(targetId: model.screenButtonDebounceKey(buttonId: "btn-1")))
        // Immediate repeat on the same Page is debounced.
        XCTAssertFalse(model.acceptActivation(targetId: model.screenButtonDebounceKey(buttonId: "btn-1")))

        // Navigating to another Page with the same Button ID allows legitimate activation immediately.
        model.selectedBoardId = "page-b"
        XCTAssertTrue(model.acceptActivation(targetId: model.screenButtonDebounceKey(buttonId: "btn-1")))
    }
}
