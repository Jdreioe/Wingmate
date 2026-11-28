import XCTest
import Shared
@testable import Wingmate

@MainActor
final class AppSettingsModelTests: XCTestCase {
    func testRefreshAppliesClampedSnapshotAndForwardsEngineFlags() async {
        let service = FakeAppSettingsService()
        service.snapshot = AppSettingsSnapshot(
            useSystemTts: false,
            ttsEngine: "GOOGLE_CLOUD",
            startupUsesScreens: true,
            startupBoardSetId: "core-1",
            scanningEnabled: true,
            scanPhraseGridOrder: "COLUMN-MAJOR",
            scanDwellTimeSeconds: 9.0,
            scanAutoAdvanceSeconds: 0.1,
            showButtonLabels: false,
            preferredGridColumns: 12,
            highContrastMode: true,
            wordTypeColorScheme: "Fitzgerald",
            holdToSelectMillis: 400,
            dwellToSelectMillis: 1_000,
            selectionHighlightMillis: 500,
            auditoryFishingEnabled: true,
            routeAuditoryPreviewToHeadphones: true,
            speechPolicy: "SentenceOnly",
            selectKeyBinding: "Space",
            restModeKeyBinding: "F8",
            historyVisible: false,
            likelyNextPageEnabled: true
        )
        let model = AppSettingsModel(service: service)
        var loadedEngine: (Bool, String)?

        let snapshot = await model.refresh { useSystem, engine in
            loadedEngine = (useSystem, engine)
        }

        XCTAssertNotNil(snapshot)
        XCTAssertEqual(loadedEngine?.0, false)
        XCTAssertEqual(loadedEngine?.1, "GOOGLE_CLOUD")
        XCTAssertTrue(model.startupUsesScreens)
        XCTAssertEqual(model.startupBoardSetId, "core-1")
        XCTAssertTrue(model.scanningEnabled)
        XCTAssertEqual(model.scanPhraseGridOrder, "column-major")
        XCTAssertEqual(model.scanDwellTimeSeconds, 2.0, accuracy: 0.001)
        XCTAssertEqual(model.scanAutoAdvanceSeconds, 0.5, accuracy: 0.001)
        XCTAssertEqual(model.preferredGridColumns, 6)
        XCTAssertTrue(model.highContrastMode)
        XCTAssertEqual(model.wordTypeColorScheme, "Fitzgerald")
        XCTAssertTrue(model.auditoryFishingEnabled)
        XCTAssertTrue(model.routeAuditoryPreviewToHeadphones)
        XCTAssertEqual(model.speechPolicy, "SentenceOnly")
        XCTAssertFalse(model.historyVisible)
        XCTAssertNil(model.lastFailureKind)
    }

    func testRefreshFailureRecordsFailureKindWithoutCrashing() async {
        let service = FakeAppSettingsService()
        service.loadError = FakeSettingsError.storage
        let model = AppSettingsModel(service: service)

        let snapshot = await model.refresh()

        XCTAssertNil(snapshot)
        XCTAssertEqual(model.lastFailureKind, .persistence)
    }

    func testSettingMutationRollsBackOnPersistenceFailure() async {
        let service = FakeAppSettingsService()
        service.mutationError = FakeSettingsError.storage
        let model = AppSettingsModel(service: service)

        let ok = await model.setHighContrastMode(true).value

        XCTAssertFalse(ok)
        XCTAssertFalse(model.highContrastMode)
        XCTAssertEqual(model.lastFailureKind, .persistence)
    }

    func testSettingMutationClampsValuesAndPersists() async {
        let service = FakeAppSettingsService()
        let model = AppSettingsModel(service: service)

        let colsOk = await model.setPreferredGridColumns(99).value
        let holdOk = await model.setHoldToSelectMillis(5_000).value
        let dwellOk = await model.setDwellToSelectMillis(-50).value

        XCTAssertTrue(colsOk)
        XCTAssertTrue(holdOk)
        XCTAssertTrue(dwellOk)
        XCTAssertEqual(model.preferredGridColumns, 6)
        XCTAssertEqual(model.holdToSelectMillis, 2_000)
        XCTAssertEqual(model.dwellToSelectMillis, 0)
        XCTAssertEqual(service.recordedMutations, ["gridColumns:6", "holdToSelectMillis:2000", "dwellToSelectMillis:0"])
    }

    func testInvalidSpeechPolicyIsRejectedLocally() async {
        let service = FakeAppSettingsService()
        let model = AppSettingsModel(service: service)

        let ok = await model.setSpeechPolicy("InvalidPolicy").value

        XCTAssertFalse(ok)
        XCTAssertEqual(model.speechPolicy, "Immediate")
        XCTAssertTrue(service.recordedMutations.isEmpty)
    }

    func testAccessInputDwellActivatesRegisteredTargetAndPauseBlocksActivation() async {
        var now: Int64 = 1_000
        let service = FakeAppSettingsService()
        let model = AppSettingsModel(service: service, nowMillis: { now })
        _ = await model.setDwellToSelectMillis(500).value

        var activated = 0
        model.registerAccessTarget("btn-1") { activated += 1 }

        model.accessEnter("btn-1")
        XCTAssertEqual(model.accessTargetId, "btn-1")

        now = 1_250
        model.tickAccessInput()
        XCTAssertEqual(activated, 0)
        XCTAssertGreaterThan(model.accessDwellProgress, 0.4)

        now = 1_600
        model.tickAccessInput()
        XCTAssertEqual(activated, 1)

        model.toggleInputPause()
        XCTAssertTrue(model.inputIsPaused)
        now = 2_500
        model.tickAccessInput()
        XCTAssertEqual(activated, 1)
    }

    func testSelectionHighlightActivatesAndClearsWhenDurationChanges() async {
        let service = FakeAppSettingsService()
        let model = AppSettingsModel(service: service)
        _ = await model.setSelectionHighlightMillis(1_000).value

        model.triggerSelectionHighlight(buttonId: "cell-4")
        XCTAssertEqual(model.highlightedButtonId, "cell-4")

        _ = await model.setSelectionHighlightMillis(0).value
        XCTAssertNil(model.highlightedButtonId)
    }

    func testCancelPendingTasksCancelsInFlightMutationWithoutRollbackError() async {
        let service = FakeAppSettingsService()
        let started = expectation(description: "mutation started")
        service.mutationHook = {
            started.fulfill()
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        let model = AppSettingsModel(service: service)

        let task = model.setScanningEnabled(true)
        await fulfillment(of: [started], timeout: 2)
        model.cancelPendingTasks()
        let ok = await task.value

        XCTAssertFalse(ok)
        XCTAssertNil(model.lastFailureKind)
    }

    func testRouteAuditoryPreviewToHeadphonesPersistsAndRollsBackOnFailure() async {
        let service = FakeAppSettingsService()
        let model = AppSettingsModel(service: service)

        XCTAssertFalse(model.routeAuditoryPreviewToHeadphones)

        let saved = await model.setRouteAuditoryPreviewToHeadphones(true).value
        XCTAssertTrue(saved)
        XCTAssertTrue(model.routeAuditoryPreviewToHeadphones)
        XCTAssertEqual(service.recordedMutations, ["previewHeadphones:true"])

        service.mutationError = FakeSettingsError.storage
        let failed = await model.setRouteAuditoryPreviewToHeadphones(false).value
        XCTAssertFalse(failed)
        XCTAssertTrue(model.routeAuditoryPreviewToHeadphones)
        XCTAssertEqual(model.lastFailureKind, .persistence)
    }
}

private enum FakeSettingsError: Error {
    case storage
}

private final class FakeAppSettingsService: AppSettingsService {
    var snapshot = AppSettingsSnapshot()
    var loadError: Error? = nil
    var mutationError: Error? = nil
    var mutationHook: (() async throws -> Void)? = nil
    var recordedMutations: [String] = []

    func loadSnapshot(fallbackUseSystemTts: Bool) async throws -> AppSettingsSnapshot {
        if let loadError { throw loadError }
        return snapshot
    }

    private func record(_ name: String) async throws {
        if let mutationHook { try await mutationHook() }
        if let mutationError { throw mutationError }
        recordedMutations.append(name)
    }

    func updateLikelyNextPageEnabled(_ enabled: Bool) async throws { try await record("likelyNext:\(enabled)") }
    func updateSelectOnTouchDown(_ enabled: Bool) async throws { try await record("touchDown:\(enabled)") }
    func updateSelectOnTouchExit(_ enabled: Bool) async throws { try await record("touchExit:\(enabled)") }
    func updateSwipeBetweenPages(_ enabled: Bool) async throws { try await record("swipe:\(enabled)") }
    func updateScanningEnabled(_ enabled: Bool) async throws { try await record("scanning:\(enabled)") }
    func updateScanPlaybackAreaEnabled(_ enabled: Bool) async throws { try await record("scanPlayback:\(enabled)") }
    func updateScanInputFieldEnabled(_ enabled: Bool) async throws { try await record("scanInput:\(enabled)") }
    func updateScanPhraseGridEnabled(_ enabled: Bool) async throws { try await record("scanGrid:\(enabled)") }
    func updateScanCategoryItemsEnabled(_ enabled: Bool) async throws { try await record("scanCategory:\(enabled)") }
    func updateScanTopBarEnabled(_ enabled: Bool) async throws { try await record("scanTopBar:\(enabled)") }
    func updateScanPhraseGridOrder(_ order: String) async throws { try await record("scanOrder:\(order)") }
    func updateScanDwellTimeSeconds(_ seconds: Float) async throws { try await record("scanDwell:\(seconds)") }
    func updateScanAutoAdvanceSeconds(_ seconds: Float) async throws { try await record("scanAdvance:\(seconds)") }
    func updateShowLabels(_ enabled: Bool) async throws { try await record("showLabels:\(enabled)") }
    func updateShowSymbols(_ enabled: Bool) async throws { try await record("showSymbols:\(enabled)") }
    func updateLabelAtTop(_ enabled: Bool) async throws { try await record("labelAtTop:\(enabled)") }
    func updateGridColumns(_ columns: Int32) async throws { try await record("gridColumns:\(columns)") }
    func updateHighContrastMode(_ enabled: Bool) async throws { try await record("highContrast:\(enabled)") }
    func updateWordTypeColorScheme(_ scheme: String) async throws { try await record("wordType:\(scheme)") }
    func updateHoldToSelectMillis(_ millis: Int64) async throws { try await record("holdToSelectMillis:\(millis)") }
    func updateDwellToSelectMillis(_ millis: Int64) async throws { try await record("dwellToSelectMillis:\(millis)") }
    func updateDwellRearmDelayMillis(_ millis: Int64) async throws { try await record("dwellRearmDelayMillis:\(millis)") }
    func updateSelectKeyBinding(_ binding: String) async throws { try await record("selectKey:\(binding)") }
    func updateRestModeKeyBinding(_ binding: String) async throws { try await record("restKey:\(binding)") }
    func updatePointerEmphasis(style: String, scale: Float) async throws { try await record("pointer:\(style):\(scale)") }
    func updateSelectionHighlightMillis(_ millis: Int64) async throws { try await record("highlight:\(millis)") }
    func updateSelectionDebounceMillis(_ millis: Int64) async throws { try await record("debounce:\(millis)") }
    func updateSelectionSoundEnabled(_ enabled: Bool) async throws { try await record("sound:\(enabled)") }
    func updateAuditoryFishingEnabled(_ enabled: Bool) async throws { try await record("fishing:\(enabled)") }
    func updateRouteAuditoryPreviewToHeadphones(_ enabled: Bool) async throws { try await record("previewHeadphones:\(enabled)") }
    func updateSpeechPolicy(_ policy: String) async throws { try await record("speechPolicy:\(policy)") }
    func updateBoardShowMessageBar(_ enabled: Bool) async throws { try await record("boardMessageBar:\(enabled)") }
    func updateBoardShowSpeakButton(_ enabled: Bool) async throws { try await record("boardSpeakButton:\(enabled)") }
    func updateUsageLoggingEnabled(_ enabled: Bool) async throws { try await record("usageLog:\(enabled)") }
    func updateHistoryVisible(_ visible: Bool) async throws { try await record("historyVisible:\(visible)") }
    func updateFeatureUsageReportingEnabled(_ enabled: Bool) async throws { try await record("reporting:\(enabled)") }
    func updateStartupUsesScreens(_ enabled: Bool) async throws { try await record("startupScreens:\(enabled)") }
    func updateStartupBoardSetId(_ id: String?) async throws { try await record("startupBoard:\(id ?? "nil")") }

    func speechPolicySpeaksSelection(policy: String, behavior: String) -> Bool {
        policy != "SentenceOnly" && behavior != "AddOnly"
    }
}
