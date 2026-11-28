import XCTest
import Shared
@testable import Wingmate

@MainActor
final class EditingAccessModelTests: XCTestCase {
    func testRefreshAppliesSnapshotFromService() async {
        let service = FakeEditingAccessService()
        service.snapshot = EditingAccessSnapshot(enabled: true, unlocked: false, supported: true, retryNotBefore: 1_500)
        let model = EditingAccessModel(service: service, now: { 1_000 })

        let ok = await model.refresh()

        XCTAssertTrue(ok)
        XCTAssertTrue(model.enabled)
        XCTAssertFalse(model.unlocked)
        XCTAssertTrue(model.supported)
        XCTAssertEqual(model.retryNotBefore, 1_500)
        XCTAssertTrue(model.isThrottled())
        XCTAssertNil(model.lastFailureKey)
    }

    func testRefreshFailureSetsUnavailableErrorAndRemainsFailClosed() async {
        let service = FakeEditingAccessService()
        service.snapshot = EditingAccessSnapshot(enabled: true, unlocked: false, supported: true, retryNotBefore: 0)
        let model = EditingAccessModel(service: service, now: { 1_000 })
        _ = await model.refresh()

        service.stateError = FakeEditingError.keychainUnavailable
        let authorized = await model.isAuthorized()

        XCTAssertFalse(authorized)
        XCTAssertEqual(model.lastFailureKey, "editing_access.unavailable")
    }

    func testUnlockSuccessRefreshesStateAndAuthorizes() async {
        let service = FakeEditingAccessService()
        service.snapshot = EditingAccessSnapshot(enabled: true, unlocked: false, supported: true, retryNotBefore: 0)
        service.unlockHandler = { code in
            let matches = code == "1234"
            if matches {
                service.snapshot = EditingAccessSnapshot(enabled: true, unlocked: true, supported: true, retryNotBefore: 0)
            }
            return matches
        }
        let model = EditingAccessModel(service: service, now: { 1_000 })
        _ = await model.refresh()

        let unlocked = await model.unlock(code: "1234")

        XCTAssertTrue(unlocked)
        XCTAssertTrue(model.unlocked)
        XCTAssertNil(model.lastFailureKey)
    }

    func testUnlockWrongCodeSetsIncorrectThenWaitWhenThrottled() async {
        let service = FakeEditingAccessService()
        service.snapshot = EditingAccessSnapshot(enabled: true, unlocked: false, supported: true, retryNotBefore: 0)
        var attempts = 0
        service.unlockHandler = { _ in
            attempts += 1
            if attempts >= 2 {
                service.snapshot = EditingAccessSnapshot(enabled: true, unlocked: false, supported: true, retryNotBefore: 1_060)
            }
            return false
        }
        let model = EditingAccessModel(service: service, now: { 1_000 })

        let first = await model.unlock(code: "0000")
        XCTAssertFalse(first)
        XCTAssertEqual(model.lastFailureKey, "editing_access.incorrect")

        let second = await model.unlock(code: "0000")
        XCTAssertFalse(second)
        XCTAssertEqual(model.lastFailureKey, "editing_access.wait")
    }

    func testUnlockTransportFailureMapsToUnavailableInsteadOfSilentFalse() async {
        let service = FakeEditingAccessService()
        service.unlockHandler = { _ in throw FakeEditingError.keychainUnavailable }
        let model = EditingAccessModel(service: service, now: { 1_000 })

        let unlocked = await model.unlock(code: "1234")

        XCTAssertFalse(unlocked)
        XCTAssertEqual(model.lastFailureKey, "editing_access.unavailable")
    }

    func testConfigureFailureMapsUnavailableAndSuccessEnables() async {
        let service = FakeEditingAccessService()
        service.configureHandler = { _ in throw FakeEditingError.keychainUnavailable }
        let model = EditingAccessModel(service: service, now: { 1_000 })

        let failed = await model.configure(code: "1234")
        XCTAssertFalse(failed)
        XCTAssertEqual(model.lastFailureKey, "editing_access.unavailable")

        service.configureHandler = { _ in
            service.snapshot = EditingAccessSnapshot(enabled: true, unlocked: true, supported: true, retryNotBefore: 0)
        }
        let configured = await model.configure(code: "1234")
        XCTAssertTrue(configured)
        XCTAssertTrue(model.enabled)
        XCTAssertNil(model.lastFailureKey)
    }

    func testLockRelocksWhenEnabledAndStaysUnlockedWhenDisabled() async {
        let service = FakeEditingAccessService()
        service.snapshot = EditingAccessSnapshot(enabled: true, unlocked: true, supported: true, retryNotBefore: 0)
        let model = EditingAccessModel(service: service, now: { 1_000 })
        _ = await model.refresh()

        model.lock()
        XCTAssertEqual(service.lockCount, 1)
        XCTAssertFalse(model.unlocked)

        service.snapshot = EditingAccessSnapshot(enabled: false, unlocked: true, supported: true, retryNotBefore: 0)
        _ = await model.refresh()
        model.lock()
        XCTAssertEqual(service.lockCount, 2)
        XCTAssertTrue(model.unlocked)
    }

    func testRefreshCancellationDoesNotRecordFailure() async {
        let service = FakeEditingAccessService()
        service.stateError = CancellationError()
        let model = EditingAccessModel(service: service, now: { 1_000 })

        let ok = await model.refresh()

        XCTAssertFalse(ok)
        XCTAssertNil(model.lastFailureKey)
    }
}

private enum FakeEditingError: Error {
    case keychainUnavailable
}

private final class FakeEditingAccessService: EditingAccessService {
    var snapshot = EditingAccessSnapshot(enabled: false, unlocked: true, supported: true, retryNotBefore: 0)
    var stateError: Error? = nil
    var unlockHandler: (String) async throws -> Bool = { _ in true }
    var configureHandler: (String) async throws -> Void = { _ in }
    var disableHandler: (String) async throws -> Bool = { _ in true }
    var lockCount = 0

    func state() async throws -> EditingAccessSnapshot {
        if let stateError { throw stateError }
        return snapshot
    }

    func unlock(code: String) async throws -> Bool {
        try await unlockHandler(code)
    }

    func configure(code: String) async throws {
        try await configureHandler(code)
    }

    func disable(code: String) async throws -> Bool {
        try await disableHandler(code)
    }

    func lock() {
        lockCount += 1
    }
}
