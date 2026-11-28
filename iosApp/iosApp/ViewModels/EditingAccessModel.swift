import Foundation
import Shared

/// Pure Swift value snapshot of Editing Access state from `Shared.SettingsFacade`.
struct EditingAccessSnapshot: Equatable {
    let enabled: Bool
    let unlocked: Bool
    let supported: Bool
    let retryNotBefore: TimeInterval
}

/// Protocol seam around `Shared.SettingsFacade`'s editing-access operations for unit testing.
protocol EditingAccessService {
    func state() async throws -> EditingAccessSnapshot
    func unlock(code: String) async throws -> Bool
    func configure(code: String) async throws
    func disable(code: String) async throws -> Bool
    func lock()
}

struct SharedEditingAccessService: EditingAccessService {
    private let resolveFacade: () -> Shared.SettingsFacade

    init(resolveFacade: @escaping () -> Shared.SettingsFacade = { IosDiBridge().settingsFacade() }) {
        self.resolveFacade = resolveFacade
    }

    func state() async throws -> EditingAccessSnapshot {
        let state = try await resolveFacade().editingAccessState()
        return EditingAccessSnapshot(
            enabled: state.enabled,
            unlocked: state.unlocked,
            supported: state.supported,
            retryNotBefore: TimeInterval(state.retryNotBeforeMillis) / 1_000
        )
    }

    func unlock(code: String) async throws -> Bool {
        try await resolveFacade().unlockEditing(code: code).boolValue
    }

    func configure(code: String) async throws {
        try await resolveFacade().configureEditingAccess(code: code)
    }

    func disable(code: String) async throws -> Bool {
        try await resolveFacade().disableEditingAccess(code: code).boolValue
    }

    func lock() {
        resolveFacade().lockEditingAccess()
    }
}

/// Feature-scoped observable model for Editing Access PIN state and authorization (#187).
/// Owns refresh task cancellation and maps bridge errors to localized failure keys instead of
/// swallowing failures with `try?`.
@MainActor
final class EditingAccessModel: ObservableObject {
    @Published private(set) var enabled: Bool = false
    @Published private(set) var unlocked: Bool = true
    @Published private(set) var supported: Bool = true
    @Published private(set) var retryNotBefore: TimeInterval = 0
    @Published private(set) var lastFailureKey: String? = nil

    private let service: any EditingAccessService
    private let now: () -> TimeInterval
    private var refreshTask: Task<Bool, Never>?

    init(
        service: any EditingAccessService = SharedEditingAccessService(),
        now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }
    ) {
        self.service = service
        self.now = now
    }

    deinit {
        refreshTask?.cancel()
    }

    func cancelPendingTasks() {
        refreshTask?.cancel()
        refreshTask = nil
    }

    func isThrottled() -> Bool {
        retryNotBefore > now()
    }

    @discardableResult
    func refresh() async -> Bool {
        refreshTask?.cancel()
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            return await self.performRefresh()
        }
        refreshTask = task
        return await task.value
    }

    func unlock(code: String) async -> Bool {
        lastFailureKey = nil
        do {
            let success = try await service.unlock(code: code)
            guard !Task.isCancelled else { return false }
            _ = await refresh()
            if !success {
                lastFailureKey = isThrottled() ? "editing_access.wait" : "editing_access.incorrect"
            }
            return success
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return false }
            _ = await refresh()
            lastFailureKey = isThrottled() ? "editing_access.wait" : "editing_access.unavailable"
            return false
        }
    }

    func configure(code: String) async -> Bool {
        lastFailureKey = nil
        do {
            try await service.configure(code: code)
            guard !Task.isCancelled else { return false }
            _ = await refresh()
            return true
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return false }
            if error.nativeFailure?.kind == NativeFailureKind.validation {
                lastFailureKey = "editing_access.incorrect"
            } else {
                lastFailureKey = isThrottled() ? "editing_access.wait" : "editing_access.unavailable"
            }
            return false
        }
    }

    func disable(code: String) async -> Bool {
        lastFailureKey = nil
        do {
            let success = try await service.disable(code: code)
            guard !Task.isCancelled else { return false }
            _ = await refresh()
            if !success {
                lastFailureKey = isThrottled() ? "editing_access.wait" : "editing_access.incorrect"
            }
            return success
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return false }
            _ = await refresh()
            lastFailureKey = isThrottled() ? "editing_access.wait" : "editing_access.unavailable"
            return false
        }
    }

    func lock() {
        service.lock()
        unlocked = !enabled
    }

    func isAuthorized() async -> Bool {
        _ = await refresh()
        return !enabled || unlocked
    }

    private func performRefresh() async -> Bool {
        do {
            let snapshot = try await service.state()
            guard !Task.isCancelled else { return false }
            enabled = snapshot.enabled
            unlocked = snapshot.unlocked
            supported = snapshot.supported
            retryNotBefore = snapshot.retryNotBefore
            return true
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return false }
            lastFailureKey = "editing_access.unavailable"
            return false
        }
    }
}
