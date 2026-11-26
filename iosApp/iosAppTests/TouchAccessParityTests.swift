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
                selectOnTouchExit: true
            )
        )
    }
}
