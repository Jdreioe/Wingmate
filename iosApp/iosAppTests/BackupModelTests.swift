import XCTest
@testable import Wingmate

@MainActor
final class BackupModelTests: XCTestCase {
    func testExportSuccessTransitionsToSucceeded() async {
        let service = FakeBackupService()
        service.shareHandler = { BackupOutcome(isSuccess: true, message: nil) }
        let model = BackupModel(service: service, localize: { $0 })

        let exported = await model.exportBackup().value

        XCTAssertTrue(exported)
        XCTAssertEqual(model.state, .succeeded("backup.exported"))
    }

    func testExportFailureUsesServiceMessageOrFallback() async {
        let service = FakeBackupService()
        service.shareHandler = { BackupOutcome(isSuccess: false, message: "Disk full") }
        let model = BackupModel(service: service, localize: { $0 })

        let first = await model.exportBackup().value
        XCTAssertFalse(first)
        XCTAssertEqual(model.state, .failed("Disk full"))

        service.shareHandler = { throw FakeBackupError.io }
        let second = await model.exportBackup().value
        XCTAssertFalse(second)
        XCTAssertEqual(model.state, .failed("backup.failed"))
    }

    func testExportCancellationResetsToIdle() async {
        let service = FakeBackupService()
        service.shareHandler = { throw CancellationError() }
        let model = BackupModel(service: service, localize: { $0 })

        let exported = await model.exportBackup().value

        XCTAssertFalse(exported)
        XCTAssertEqual(model.state, .idle)
    }

    func testRestoreSuccessRunsAfterRestoreHookAndTransitionsToSucceeded() async {
        let service = FakeBackupService()
        service.restoreHandler = { path in
            XCTAssertEqual(path, "/tmp/wingmate.zip")
            return BackupOutcome(isSuccess: true, message: nil)
        }
        let model = BackupModel(service: service, localize: { $0 })
        var reloaded = false

        let restored = await model.restoreBackup(path: "/tmp/wingmate.zip") {
            reloaded = true
        }.value

        XCTAssertTrue(restored)
        XCTAssertTrue(reloaded)
        XCTAssertEqual(model.state, .succeeded("backup.restored"))
    }

    func testRestoreFailureSkipsAfterRestoreHook() async {
        let service = FakeBackupService()
        service.restoreHandler = { _ in
            BackupOutcome(isSuccess: false, message: "Invalid archive")
        }
        let model = BackupModel(service: service, localize: { $0 })
        var reloaded = false

        let restored = await model.restoreBackup(path: "/tmp/bad.zip") {
            reloaded = true
        }.value

        XCTAssertFalse(restored)
        XCTAssertFalse(reloaded)
        XCTAssertEqual(model.state, .failed("Invalid archive"))
    }

    func testRestoreReloadErrorTransitionsToFailedInsteadOfSilentSuccess() async {
        let service = FakeBackupService()
        service.restoreHandler = { _ in BackupOutcome(isSuccess: true, message: nil) }
        let model = BackupModel(service: service, localize: { $0 })

        let restored = await model.restoreBackup(path: "/tmp/wingmate.zip") {
            throw FakeBackupError.io
        }.value

        XCTAssertFalse(restored)
        XCTAssertEqual(model.state, .failed("backup.failed"))
    }

    func testOverlappingExportWhileBusyDoesNotStartSecondOperation() async {
        let service = FakeBackupService()
        let started = expectation(description: "first export started")
        var callCount = 0
        service.shareHandler = {
            callCount += 1
            started.fulfill()
            try await Task.sleep(nanoseconds: 200_000_000)
            return BackupOutcome(isSuccess: true, message: nil)
        }
        let model = BackupModel(service: service, localize: { $0 })

        let first = model.exportBackup()
        await fulfillment(of: [started], timeout: 2)
        let second = model.exportBackup()

        let result1 = await first.value
        let result2 = await second.value

        XCTAssertTrue(result1)
        XCTAssertTrue(result2)
        XCTAssertEqual(callCount, 1)
    }

    func testCancelPendingTasksResetsBusyOperationToIdle() async {
        let service = FakeBackupService()
        let started = expectation(description: "restore started")
        service.restoreHandler = { _ in
            started.fulfill()
            try await Task.sleep(nanoseconds: 5_000_000_000)
            return BackupOutcome(isSuccess: true, message: nil)
        }
        let model = BackupModel(service: service, localize: { $0 })

        let task = model.restoreBackup(path: "/tmp/slow.zip")
        await fulfillment(of: [started], timeout: 2)
        XCTAssertEqual(model.state, .restoring)

        model.cancelPendingTasks()
        let restored = await task.value

        XCTAssertFalse(restored)
        XCTAssertEqual(model.state, .idle)
    }
}

private enum FakeBackupError: Error {
    case io
}

private final class FakeBackupService: BackupService {
    var shareHandler: () async throws -> BackupOutcome = {
        BackupOutcome(isSuccess: true, message: nil)
    }
    var restoreHandler: (String) async throws -> BackupOutcome = { _ in
        BackupOutcome(isSuccess: true, message: nil)
    }

    func shareBackup() async throws -> BackupOutcome {
        try await shareHandler()
    }

    func restoreBackup(path: String) async throws -> BackupOutcome {
        try await restoreHandler(path)
    }
}
