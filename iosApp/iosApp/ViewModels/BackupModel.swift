import Foundation
import Shared

/// Pure Swift value outcome mapped from `Shared.BackupOperationResult`.
struct BackupOutcome: Equatable {
    let isSuccess: Bool
    let message: String?
}

/// Protocol seam around `Shared.BackupSharingFacade` for unit testing.
protocol BackupService {
    func shareBackup() async throws -> BackupOutcome
    func restoreBackup(path: String) async throws -> BackupOutcome
}

struct SharedBackupService: BackupService {
    private let resolveFacade: () -> Shared.BackupSharingFacade

    init(resolveFacade: @escaping () -> Shared.BackupSharingFacade = { IosDiBridge().backupFacade() }) {
        self.resolveFacade = resolveFacade
    }

    func shareBackup() async throws -> BackupOutcome {
        let result = try await resolveFacade().shareBackup()
        return BackupOutcome(isSuccess: result.isSuccess, message: result.message)
    }

    func restoreBackup(path: String) async throws -> BackupOutcome {
        let result = try await resolveFacade().restoreBackup(path: path)
        return BackupOutcome(isSuccess: result.isSuccess, message: result.message)
    }
}

enum BackupOperationState: Equatable {
    case idle
    case exporting
    case restoring
    case succeeded(String)
    case failed(String)

    var isBusy: Bool {
        self == .exporting || self == .restoring
    }

    var isRestoring: Bool {
        self == .restoring
    }

    var statusMessage: String? {
        switch self {
        case .succeeded(let message), .failed(let message):
            return message
        case .idle, .exporting, .restoring:
            return nil
        }
    }
}

/// Feature-scoped observable model for full-installation Backup export and restore (#187).
/// Prevents overlapping backup operations, cancels active work on disposal, and centralizes
/// localized status mapping across Settings and Welcome onboarding.
@MainActor
final class BackupModel: ObservableObject {
    @Published private(set) var state: BackupOperationState = .idle

    private let service: any BackupService
    private let localize: (String) -> String
    private var activeTask: Task<Bool, Never>?

    init(
        service: any BackupService = SharedBackupService(),
        localize: @escaping (String) -> String = { NSLocalizedString($0, comment: "") }
    ) {
        self.service = service
        self.localize = localize
    }

    deinit {
        activeTask?.cancel()
    }

    func cancelPendingTasks() {
        activeTask?.cancel()
        activeTask = nil
        if state.isBusy {
            state = .idle
        }
    }

    func clearStatus() {
        if !state.isBusy {
            state = .idle
        }
    }

    @discardableResult
    func exportBackup() -> Task<Bool, Never> {
        if let activeTask, state.isBusy {
            return activeTask
        }
        state = .exporting
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            defer { self.activeTask = nil }
            do {
                let outcome = try await self.service.shareBackup()
                guard !Task.isCancelled else {
                    self.state = .idle
                    return false
                }
                if outcome.isSuccess {
                    self.state = .succeeded(self.localize("backup.exported"))
                    return true
                } else {
                    self.state = .failed(outcome.message ?? self.localize("backup.failed"))
                    return false
                }
            } catch {
                if error.isNativeCancellation || Task.isCancelled {
                    self.state = .idle
                    return false
                }
                self.state = .failed(self.localize("backup.failed"))
                return false
            }
        }
        activeTask = task
        return task
    }

    @discardableResult
    func restoreBackup(
        from url: URL,
        afterRestore: (@MainActor () async throws -> Void)? = nil
    ) -> Task<Bool, Never> {
        if let activeTask, state.isBusy {
            return activeTask
        }
        state = .restoring
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            defer { self.activeTask = nil }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            return await self.performRestore(path: url.path, afterRestore: afterRestore)
        }
        activeTask = task
        return task
    }

    @discardableResult
    func restoreBackup(
        path: String,
        afterRestore: (@MainActor () async throws -> Void)? = nil
    ) -> Task<Bool, Never> {
        if let activeTask, state.isBusy {
            return activeTask
        }
        state = .restoring
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            defer { self.activeTask = nil }
            return await self.performRestore(path: path, afterRestore: afterRestore)
        }
        activeTask = task
        return task
    }

    private func performRestore(
        path: String,
        afterRestore: (@MainActor () async throws -> Void)?
    ) async -> Bool {
        do {
            let outcome = try await self.service.restoreBackup(path: path)
            guard !Task.isCancelled else {
                self.state = .idle
                return false
            }
            if !outcome.isSuccess {
                self.state = .failed(outcome.message ?? self.localize("backup.failed"))
                return false
            }
            if let afterRestore {
                try await afterRestore()
            }
            guard !Task.isCancelled else {
                self.state = .idle
                return false
            }
            self.state = .succeeded(self.localize("backup.restored"))
            return true
        } catch {
            if error.isNativeCancellation || Task.isCancelled {
                self.state = .idle
                return false
            }
            self.state = .failed(self.localize("backup.failed"))
            return false
        }
    }
}
