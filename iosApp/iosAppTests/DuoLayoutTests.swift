import XCTest
@testable import Wingmate

final class DuoLayoutTests: XCTestCase {

    // MARK: - WindowLayoutClass

    func testWindowLayoutClassForIphoneDuoDisplaySizes() {
        // Outer display (closed): 466×678 portrait, 678×466 landscape
        XCTAssertEqual(WindowLayoutClass.of(width: 466), .compact)
        XCTAssertFalse(WindowLayoutClass.usesWideTyping(size: CGSize(width: 466, height: 678)))
        XCTAssertFalse(WindowLayoutClass.usesWideTyping(size: CGSize(width: 678, height: 466)))

        // Inner display (unfolded): 669×951 portrait, 951×669 landscape
        XCTAssertEqual(WindowLayoutClass.of(width: 669), .medium)
        XCTAssertTrue(WindowLayoutClass.usesWideTyping(size: CGSize(width: 669, height: 951)))
        XCTAssertEqual(WindowLayoutClass.typingSidePanelWidth(for: 669), 232)

        XCTAssertEqual(WindowLayoutClass.of(width: 951), .expanded)
        XCTAssertTrue(WindowLayoutClass.usesWideTyping(size: CGSize(width: 951, height: 669)))
        XCTAssertEqual(WindowLayoutClass.typingSidePanelWidth(for: 951), 288)
        // Raising the on-screen keyboard shrinks visible height, but wide Typing stays active
        XCTAssertTrue(WindowLayoutClass.usesWideTyping(
            size: CGSize(width: 951, height: 340),
            restingHeight: 669,
            keyboardVisible: true
        ))
    }

    func testWindowLayoutClassForStandardIphoneAndIpad() {
        // Standard iPhone portrait & landscape stay compact
        XCTAssertFalse(WindowLayoutClass.usesWideTyping(size: CGSize(width: 402, height: 874)))
        XCTAssertFalse(WindowLayoutClass.usesWideTyping(size: CGSize(width: 874, height: 402)))

        // iPad portrait & landscape use wide layout, including while the keyboard is raised
        XCTAssertTrue(WindowLayoutClass.usesWideTyping(size: CGSize(width: 820, height: 1180)))
        XCTAssertTrue(WindowLayoutClass.usesWideTyping(size: CGSize(width: 1180, height: 820)))
        XCTAssertTrue(WindowLayoutClass.usesWideTyping(
            size: CGSize(width: 1180, height: 350),
            restingHeight: 766,
            keyboardVisible: true
        ))
    }

    // MARK: - FoldDivision & BoardFoldLayout

    func testFoldDivisionDetectsVerticalAndHorizontalCrease() {
        let vertical = FoldDivision.from(
            rects: [CGRect(x: 326, y: 0, width: 18, height: 951)],
            in: CGSize(width: 669, height: 951)
        )
        XCTAssertEqual(vertical?.axis, .vertical)

        let horizontal = FoldDivision.from(
            rects: [CGRect(x: 0, y: 326, width: 951, height: 18)],
            in: CGSize(width: 951, height: 669)
        )
        XCTAssertEqual(horizontal?.axis, .horizontal)

        // Zero-width inactive crease returns nil
        XCTAssertNil(FoldDivision.from(rects: [.zero], in: CGSize(width: 669, height: 951)))
    }

    func testBoardFoldLayoutAvoidsVerticalCreaseWithoutChangingGridTopology() {
        let crease = FoldDivision(
            axis: .vertical,
            rect: CGRect(x: 320, y: 0, width: 28, height: 700)
        )
        let layout = BoardFoldLayout.compute(
            rows: 4,
            columns: 4,
            contentWidth: 653,
            contentHeight: 684,
            gap: 8,
            gridInset: 8,
            division: crease
        )
        XCTAssertEqual(layout.columnOrigins.count, 4)
        XCTAssertEqual(layout.rowOrigins.count, 4)

        // Columns 0..1 stay strictly left of the crease; columns 2..3 stay strictly right of it.
        let creaseInGridMinX = crease.rect.minX - 8
        let creaseInGridMaxX = crease.rect.maxX - 8
        let leftHalfMax = layout.frame(row: 0, column: 1, rowSpan: 1, columnSpan: 1).maxX
        let rightHalfMin = layout.frame(row: 0, column: 2, rowSpan: 1, columnSpan: 1).minX
        XCTAssertLessThanOrEqual(leftHalfMax, creaseInGridMinX)
        XCTAssertGreaterThanOrEqual(rightHalfMin, creaseInGridMaxX)

        // Hit-testing inside the crease returns nil; inside a cell returns its coordinates.
        let creasePoint = CGPoint(x: (creaseInGridMinX + creaseInGridMaxX) / 2, y: 50)
        XCTAssertNil(layout.cellAddress(at: creasePoint))
        let cell2Rect = layout.frame(row: 1, column: 2, rowSpan: 1, columnSpan: 1)
        let hit = layout.cellAddress(at: CGPoint(x: cell2Rect.midX, y: cell2Rect.midY))
        XCTAssertEqual(hit?.row, 1)
        XCTAssertEqual(hit?.column, 2)
    }

    func testBoardFoldLayoutAvoidsHorizontalCrease() {
        let crease = FoldDivision(
            axis: .horizontal,
            rect: CGRect(x: 0, y: 260, width: 900, height: 24)
        )
        let layout = BoardFoldLayout.compute(
            rows: 4,
            columns: 5,
            contentWidth: 884,
            contentHeight: 540,
            gap: 8,
            gridInset: 8,
            division: crease
        )
        let creaseMinY = crease.rect.minY - 8
        let creaseMaxY = crease.rect.maxY - 8
        let topHalfMax = layout.frame(row: 1, column: 0, rowSpan: 1, columnSpan: 1).maxY
        let bottomHalfMin = layout.frame(row: 2, column: 0, rowSpan: 1, columnSpan: 1).minY
        XCTAssertLessThanOrEqual(topHalfMax, creaseMinY)
        XCTAssertGreaterThanOrEqual(bottomHalfMin, creaseMaxY)
    }

    // MARK: - PartnerDisplayPolicy

    func testPartnerDisplayPolicyPrivacyAndConditions() {
        // Active Message while unfolded -> shown
        XCTAssertTrue(PartnerDisplayPolicy.shouldShow(
            mode: .whileCommunicating,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .fullyOpen,
            messageText: "Hello",
            hasMessageParts: true,
            isSpeakingOrActive: false,
            isFullscreenMessage: false
        ))

        // Closed iPhone Duo -> hidden (Communicator is using the outer screen)
        XCTAssertFalse(PartnerDisplayPolicy.shouldShow(
            mode: .whileCommunicating,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .closed,
            messageText: "Hello",
            hasMessageParts: true,
            isSpeakingOrActive: true,
            isFullscreenMessage: true
        ))

        // Settings / Welcome / Edit mode / Rest mode -> always hidden
        XCTAssertFalse(PartnerDisplayPolicy.shouldShow(
            mode: .whileCommunicating,
            inWelcomeFlow: true,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .fullyOpen,
            messageText: "Hello",
            hasMessageParts: true,
            isSpeakingOrActive: true,
            isFullscreenMessage: false
        ))
        XCTAssertFalse(PartnerDisplayPolicy.shouldShow(
            mode: .whileCommunicating,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: true,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .fullyOpen,
            messageText: "Hello",
            hasMessageParts: true,
            isSpeakingOrActive: true,
            isFullscreenMessage: false
        ))
        XCTAssertFalse(PartnerDisplayPolicy.shouldShow(
            mode: .whileCommunicating,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: true,
            inputIsPaused: false,
            hingePosture: .fullyOpen,
            messageText: "Hello",
            hasMessageParts: true,
            isSpeakingOrActive: true,
            isFullscreenMessage: false
        ))
        XCTAssertFalse(PartnerDisplayPolicy.shouldShow(
            mode: .whileCommunicating,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: true,
            hingePosture: .fullyOpen,
            messageText: "Hello",
            hasMessageParts: true,
            isSpeakingOrActive: true,
            isFullscreenMessage: false
        ))

        // Empty message & idle -> hidden
        XCTAssertFalse(PartnerDisplayPolicy.shouldShow(
            mode: .whileCommunicating,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .fullyOpen,
            messageText: "   ",
            hasMessageParts: false,
            isSpeakingOrActive: false,
            isFullscreenMessage: false
        ))

        // Speech-only mode: hidden while drafting, shown while speaking or fullscreen
        XCTAssertFalse(PartnerDisplayPolicy.shouldShow(
            mode: .whenSpeaking,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .fullyOpen,
            messageText: "Drafting",
            hasMessageParts: true,
            isSpeakingOrActive: false,
            isFullscreenMessage: false
        ))
        XCTAssertTrue(PartnerDisplayPolicy.shouldShow(
            mode: .whenSpeaking,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .partiallyOpen,
            messageText: "Speaking now",
            hasMessageParts: true,
            isSpeakingOrActive: true,
            isFullscreenMessage: false
        ))
        XCTAssertTrue(PartnerDisplayPolicy.shouldShow(
            mode: .whenSpeaking,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .fullyOpen,
            messageText: "Read this please",
            hasMessageParts: true,
            isSpeakingOrActive: false,
            isFullscreenMessage: true
        ))

        // Off mode -> always hidden
        XCTAssertFalse(PartnerDisplayPolicy.shouldShow(
            mode: .off,
            inWelcomeFlow: false,
            isConfiguringOrUnlocking: false,
            isEditingScreen: false,
            inputIsPaused: false,
            hingePosture: .fullyOpen,
            messageText: "Hello",
            hasMessageParts: true,
            isSpeakingOrActive: true,
            isFullscreenMessage: true
        ))
    }

    func testTopOcclusionClearanceIgnoresFullHeightAndInfiniteOcclusions() {
        let iPadSize = CGSize(width: 1376, height: 1032)

        // Small top camera cutout is respected.
        XCTAssertEqual(
            TopOcclusionClearance.from(
                rects: [CGRect(x: 640, y: 8, width: 96, height: 36)],
                in: iPadSize
            ),
            44,
            accuracy: 0.01
        )

        // Full-height, tall, or infinite occlusion regions must never push edit UI off-screen.
        XCTAssertEqual(
            TopOcclusionClearance.from(
                rects: [
                    CGRect(x: 0, y: 0, width: 1376, height: 1032),
                    CGRect(x: 0, y: 0, width: 64, height: 900),
                    .infinite,
                    .null,
                ],
                in: iPadSize
            ),
            0,
            accuracy: 0.01
        )
    }
}
