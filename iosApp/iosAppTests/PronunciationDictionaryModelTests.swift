import XCTest
import Shared
@testable import Wingmate

@MainActor
final class PronunciationDictionaryModelTests: XCTestCase {
    func testLoadTransitionsToEmptyWhenDictionaryHasNoEntries() async {
        let service = FakePronunciationService()
        service.listHandler = {
            Shared.PronunciationListResult(status: .success, entries: [])
        }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        await model.load()

        XCTAssertEqual(model.loadState, .empty)
        XCTAssertTrue(model.entries.isEmpty)
        XCTAssertNil(model.errorMessage)
    }

    func testLoadTransitionsToLoadedWithEntries() async {
        let entry = Shared.PronunciationEntry(word: "Wingmate", phoneme: "win-mate", alphabet: "text")
        let service = FakePronunciationService()
        service.listHandler = {
            Shared.PronunciationListResult(status: .success, entries: [entry])
        }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        await model.load()

        XCTAssertEqual(model.loadState, .loaded)
        XCTAssertEqual(model.entries.map(\.word), ["Wingmate"])
        XCTAssertNil(model.errorMessage)
    }

    func testLoadFailurePreservesPreviouslyLoadedEntriesAndShowsError() async {
        let entry = Shared.PronunciationEntry(word: "Wingmate", phoneme: "win-mate", alphabet: "text")
        let service = FakePronunciationService()
        var shouldFail = false
        service.listHandler = {
            if shouldFail {
                return Shared.PronunciationListResult(status: .persistencefailure, entries: [])
            }
            return Shared.PronunciationListResult(status: .success, entries: [entry])
        }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        await model.load()
        XCTAssertEqual(model.entries.map(\.word), ["Wingmate"])

        shouldFail = true
        await model.load()

        XCTAssertEqual(model.entries.map(\.word), ["Wingmate"])
        XCTAssertEqual(model.loadState, .loaded)
        XCTAssertEqual(model.errorMessage, "pronunciation.error.load_failed")
    }

    func testLoadThrownErrorOnEmptyListTransitionsToFailed() async {
        let service = FakePronunciationService()
        service.listHandler = { throw TestBridgeError.storage }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        await model.load()

        XCTAssertEqual(model.loadState, .failed("pronunciation.error.load_failed"))
        XCTAssertEqual(model.errorMessage, "pronunciation.error.load_failed")
    }

    func testLoadCancellationLeavesStateUntouched() async {
        let service = FakePronunciationService()
        service.listHandler = { throw CancellationError() }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        await model.load()

        XCTAssertNil(model.errorMessage)
        XCTAssertTrue(model.entries.isEmpty)
    }

    func testAddSuccessReloadsEntriesAndClearsError() async {
        let service = FakePronunciationService()
        var stored: [Shared.PronunciationEntry] = []
        service.listHandler = {
            Shared.PronunciationListResult(status: .success, entries: stored)
        }
        service.addHandler = { word, phoneme, alphabet in
            stored.append(Shared.PronunciationEntry(word: word, phoneme: phoneme, alphabet: alphabet))
            return Shared.PronunciationResult(status: .success)
        }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        let saved = await model.add(word: "AAC", phoneme: "ay ay see", alphabet: "text").value

        XCTAssertTrue(saved)
        XCTAssertFalse(model.isSaving)
        XCTAssertEqual(model.loadState, .loaded)
        XCTAssertEqual(model.entries.map(\.word), ["AAC"])
        XCTAssertNil(model.errorMessage)
    }

    func testAddFailureMapsLocalizedSaveErrorWithoutHidingExistingEntries() async {
        let existing = Shared.PronunciationEntry(word: "Wingmate", phoneme: "win-mate", alphabet: "text")
        let service = FakePronunciationService()
        service.listHandler = {
            Shared.PronunciationListResult(status: .success, entries: [existing])
        }
        service.addHandler = { _, _, _ in
            throw TestBridgeError.storage
        }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        await model.load()
        let saved = await model.add(word: "Test", phoneme: "test", alphabet: "text").value

        XCTAssertFalse(saved)
        XCTAssertEqual(model.entries.map(\.word), ["Wingmate"])
        XCTAssertEqual(model.errorMessage, "pronunciation.error.save_failed")
    }

    func testDeleteSuccessRemovesEntryAndTransitionsToEmpty() async {
        let service = FakePronunciationService()
        var stored = [Shared.PronunciationEntry(word: "Wingmate", phoneme: "win-mate", alphabet: "text")]
        service.listHandler = {
            Shared.PronunciationListResult(status: .success, entries: stored)
        }
        service.deleteHandler = { word in
            stored.removeAll { $0.word == word }
            return Shared.PronunciationResult(status: .success)
        }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        await model.load()
        let deleted = await model.delete(word: "Wingmate").value

        XCTAssertTrue(deleted)
        XCTAssertEqual(model.loadState, .empty)
        XCTAssertTrue(model.entries.isEmpty)
    }

    func testCancelPendingTasksCancelsInFlightMutation() async {
        let service = FakePronunciationService()
        let started = expectation(description: "add started")
        service.addHandler = { _, _, _ in
            started.fulfill()
            try await Task.sleep(nanoseconds: 5_000_000_000)
            return Shared.PronunciationResult(status: .success)
        }
        let model = PronunciationDictionaryModel(service: service, localize: { $0 })

        let task = model.add(word: "Slow", phoneme: "slow", alphabet: "text")
        await fulfillment(of: [started], timeout: 2)
        model.cancelPendingTasks()
        let saved = await task.value

        XCTAssertFalse(saved)
        XCTAssertFalse(model.isSaving)
        XCTAssertNil(model.errorMessage)
    }
}

private enum TestBridgeError: Error {
    case storage
}

private final class FakePronunciationService: PronunciationDictionaryService {
    var listHandler: () async throws -> Shared.PronunciationListResult = {
        Shared.PronunciationListResult(status: .success, entries: [])
    }
    var addHandler: (String, String, String) async throws -> Shared.PronunciationResult = { _, _, _ in
        Shared.PronunciationResult(status: .success)
    }
    var deleteHandler: (String) async throws -> Shared.PronunciationResult = { _ in
        Shared.PronunciationResult(status: .success)
    }

    func list() async throws -> Shared.PronunciationListResult {
        try await listHandler()
    }

    func add(word: String, phoneme: String, alphabet: String) async throws -> Shared.PronunciationResult {
        try await addHandler(word, phoneme, alphabet)
    }

    func delete(word: String) async throws -> Shared.PronunciationResult {
        try await deleteHandler(word)
    }
}
