import Foundation
import Shared

/// Protocol seam around `Shared.PronunciationFacade` so `PronunciationDictionaryModel`
/// can be unit-tested with deterministic fakes and cancellation probes.
protocol PronunciationDictionaryService {
    func list() async throws -> Shared.PronunciationListResult
    func add(word: String, phoneme: String, alphabet: String) async throws -> Shared.PronunciationResult
    func delete(word: String) async throws -> Shared.PronunciationResult
}

struct SharedPronunciationDictionaryService: PronunciationDictionaryService {
    private let resolveFacade: () -> Shared.PronunciationFacade

    init(resolveFacade: @escaping () -> Shared.PronunciationFacade = { IosDiBridge().pronunciationFacade() }) {
        self.resolveFacade = resolveFacade
    }

    func list() async throws -> Shared.PronunciationListResult {
        try await resolveFacade().list()
    }

    func add(word: String, phoneme: String, alphabet: String) async throws -> Shared.PronunciationResult {
        try await resolveFacade().add(word: word, phoneme: phoneme, alphabet: alphabet)
    }

    func delete(word: String) async throws -> Shared.PronunciationResult {
        try await resolveFacade().delete(word: word)
    }
}

enum PronunciationLoadState: Equatable {
    case idle
    case loading
    case empty
    case loaded
    case failed(String)
}

/// Feature-scoped observable model for the Pronunciation Dictionary (#187).
/// Owns its async tasks, cancels pending work on replacement or disposal, and maps
/// shared facade results/errors to localized user-facing feedback without silent `try?`.
@MainActor
final class PronunciationDictionaryModel: ObservableObject {
    @Published private(set) var entries: [Shared.PronunciationEntry] = []
    @Published private(set) var loadState: PronunciationLoadState = .idle
    @Published private(set) var errorMessage: String? = nil
    @Published private(set) var isSaving: Bool = false

    private let service: any PronunciationDictionaryService
    private let localize: (String) -> String
    private var loadTask: Task<Void, Never>?
    private var mutationTask: Task<Bool, Never>?

    init(
        service: any PronunciationDictionaryService = SharedPronunciationDictionaryService(),
        localize: @escaping (String) -> String = { NSLocalizedString($0, comment: "") }
    ) {
        self.service = service
        self.localize = localize
    }

    deinit {
        loadTask?.cancel()
        mutationTask?.cancel()
    }

    func cancelPendingTasks() {
        loadTask?.cancel()
        loadTask = nil
        mutationTask?.cancel()
        mutationTask = nil
        isSaving = false
        if loadState == .loading {
            loadState = entries.isEmpty ? .idle : .loaded
        }
    }

    func load() async {
        loadTask?.cancel()
        if entries.isEmpty {
            loadState = .loading
        }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.performLoad()
        }
        loadTask = task
        await task.value
    }

    @discardableResult
    func add(word: String, phoneme: String, alphabet: String) -> Task<Bool, Never> {
        runMutation { service in
            try await service.add(word: word, phoneme: phoneme, alphabet: alphabet)
        }
    }

    @discardableResult
    func delete(word: String) -> Task<Bool, Never> {
        runMutation { service in
            try await service.delete(word: word)
        }
    }

    private func performLoad() async {
        do {
            let result = try await service.list()
            guard !Task.isCancelled else { return }
            if result.isSuccess {
                entries = result.entries
                errorMessage = nil
                loadState = result.entries.isEmpty ? .empty : .loaded
            } else {
                let message = localize("pronunciation.error.load_failed")
                errorMessage = message
                // Keep previously loaded entries visible so a transient failure does not blank the list.
                loadState = entries.isEmpty ? .failed(message) : .loaded
            }
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            let message = localize("pronunciation.error.load_failed")
            errorMessage = message
            loadState = entries.isEmpty ? .failed(message) : .loaded
        }
    }

    private func runMutation(
        _ operation: @escaping (any PronunciationDictionaryService) async throws -> Shared.PronunciationResult
    ) -> Task<Bool, Never> {
        mutationTask?.cancel()
        isSaving = true
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            defer { self.isSaving = false }
            do {
                let result = try await operation(self.service)
                guard !Task.isCancelled else { return false }
                if result.isSuccess {
                    self.errorMessage = nil
                    await self.performLoad()
                    return true
                } else {
                    self.errorMessage = self.localize("pronunciation.error.save_failed")
                    return false
                }
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return false }
                self.errorMessage = self.localize("pronunciation.error.save_failed")
                return false
            }
        }
        mutationTask = task
        return task
    }
}
