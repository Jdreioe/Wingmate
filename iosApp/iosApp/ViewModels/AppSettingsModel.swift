import Foundation
import Shared

/// Pure Swift snapshot of app settings and iOS parity flags loaded from `Shared.SettingsFacade`.
struct AppSettingsSnapshot: Equatable {
    // Engine & startup flags
    var useSystemTts: Bool = true
    var ttsEngine: String = "SYSTEM"
    var startupUsesScreens: Bool = false
    var startupBoardSetId: String? = nil

    // Scanning
    var scanningEnabled: Bool = false
    var scanPlaybackAreaEnabled: Bool = true
    var scanInputFieldEnabled: Bool = true
    var scanPhraseGridEnabled: Bool = true
    var scanCategoryItemsEnabled: Bool = true
    var scanTopBarEnabled: Bool = true
    var scanPhraseGridOrder: String = "row-major"
    var scanDwellTimeSeconds: Double = 1.0
    var scanAutoAdvanceSeconds: Double = 1.2

    // Display
    var showButtonLabels: Bool = true
    var showButtonSymbols: Bool = true
    var labelAtTop: Bool = false
    var preferredGridColumns: Int = 3
    var highContrastMode: Bool = false
    var wordTypeColorScheme: String = "None"
    var boardShowMessageBar: Bool = true
    var boardShowSpeakButton: Bool = true

    // Timing, feedback, and pointer/switch access
    var holdToSelectMillis: Double = 0
    var dwellToSelectMillis: Double = 0
    var selectionDebounceMillis: Double = 0
    var selectionHighlightMillis: Int64 = 0
    var selectionSoundEnabled: Bool = false
    var auditoryFishingEnabled: Bool = false
    var speechPolicy: String = "Immediate"
    var selectKeyBinding: String = ""
    var restModeKeyBinding: String = ""
    var pointerEmphasisStyle: String = "System"
    var pointerEmphasisScale: Double = 1.5
    var selectOnTouchDown: Bool = false
    var swipeBetweenPages: Bool = false

    // Privacy & history
    var usageLoggingEnabled: Bool = false
    var featureUsageReportingEnabled: Bool = false
    var historyVisible: Bool = true
    var likelyNextPageEnabled: Bool = false
}

/// Protocol seam around `Shared.SettingsFacade` so `AppSettingsModel` can be unit-tested
/// with deterministic fakes, failure injection, and cancellation probes.
protocol AppSettingsService {
    func loadSnapshot(fallbackUseSystemTts: Bool) async throws -> AppSettingsSnapshot
    func updateLikelyNextPageEnabled(_ enabled: Bool) async throws
    func updateSelectOnTouchDown(_ enabled: Bool) async throws
    func updateSwipeBetweenPages(_ enabled: Bool) async throws
    func updateScanningEnabled(_ enabled: Bool) async throws
    func updateScanPlaybackAreaEnabled(_ enabled: Bool) async throws
    func updateScanInputFieldEnabled(_ enabled: Bool) async throws
    func updateScanPhraseGridEnabled(_ enabled: Bool) async throws
    func updateScanCategoryItemsEnabled(_ enabled: Bool) async throws
    func updateScanTopBarEnabled(_ enabled: Bool) async throws
    func updateScanPhraseGridOrder(_ order: String) async throws
    func updateScanDwellTimeSeconds(_ seconds: Float) async throws
    func updateScanAutoAdvanceSeconds(_ seconds: Float) async throws
    func updateShowLabels(_ enabled: Bool) async throws
    func updateShowSymbols(_ enabled: Bool) async throws
    func updateLabelAtTop(_ enabled: Bool) async throws
    func updateGridColumns(_ columns: Int32) async throws
    func updateHighContrastMode(_ enabled: Bool) async throws
    func updateWordTypeColorScheme(_ scheme: String) async throws
    func updateHoldToSelectMillis(_ millis: Int64) async throws
    func updateDwellToSelectMillis(_ millis: Int64) async throws
    func updateSelectKeyBinding(_ binding: String) async throws
    func updateRestModeKeyBinding(_ binding: String) async throws
    func updatePointerEmphasis(style: String, scale: Float) async throws
    func updateSelectionHighlightMillis(_ millis: Int64) async throws
    func updateSelectionDebounceMillis(_ millis: Int64) async throws
    func updateSelectionSoundEnabled(_ enabled: Bool) async throws
    func updateAuditoryFishingEnabled(_ enabled: Bool) async throws
    func updateSpeechPolicy(_ policy: String) async throws
    func updateBoardShowMessageBar(_ enabled: Bool) async throws
    func updateBoardShowSpeakButton(_ enabled: Bool) async throws
    func updateUsageLoggingEnabled(_ enabled: Bool) async throws
    func updateHistoryVisible(_ visible: Bool) async throws
    func updateFeatureUsageReportingEnabled(_ enabled: Bool) async throws
    func updateStartupUsesScreens(_ enabled: Bool) async throws
    func updateStartupBoardSetId(_ id: String?) async throws
    func speechPolicySpeaksSelection(policy: String, behavior: String) -> Bool
}

struct SharedAppSettingsService: AppSettingsService {
    private let resolveFacade: () -> Shared.SettingsFacade

    init(resolveFacade: @escaping () -> Shared.SettingsFacade = { IosDiBridge().settingsFacade() }) {
        self.resolveFacade = resolveFacade
    }

    func loadSnapshot(fallbackUseSystemTts: Bool) async throws -> AppSettingsSnapshot {
        let facade = resolveFacade()
        let settings = try await facade.getSettings()
        let flags = try await facade.iosSettingsFlags()
        return AppSettingsSnapshot(
            useSystemTts: flags.usesSystemTts,
            ttsEngine: flags.ttsEngine,
            startupUsesScreens: flags.startupUsesScreens,
            startupBoardSetId: settings.startupBoardSetId,
            scanningEnabled: settings.scanningEnabled,
            scanPlaybackAreaEnabled: settings.scanPlaybackAreaEnabled,
            scanInputFieldEnabled: settings.scanInputFieldEnabled,
            scanPhraseGridEnabled: settings.scanPhraseGridEnabled,
            scanCategoryItemsEnabled: settings.scanCategoryItemsEnabled,
            scanTopBarEnabled: settings.scanTopBarEnabled,
            scanPhraseGridOrder: settings.scanPhraseGridOrder,
            scanDwellTimeSeconds: Double(settings.scanDwellTimeSeconds),
            scanAutoAdvanceSeconds: Double(settings.scanAutoAdvanceSeconds),
            showButtonLabels: settings.showLabels,
            showButtonSymbols: settings.showSymbols,
            labelAtTop: settings.labelAtTop,
            preferredGridColumns: min(max(Int(settings.gridColumns), 1), 6),
            highContrastMode: settings.highContrastMode,
            wordTypeColorScheme: settings.wordTypeColorScheme.name,
            boardShowMessageBar: settings.boardShowMessageBar,
            boardShowSpeakButton: settings.boardShowSpeakButton,
            holdToSelectMillis: Double(settings.holdToSelectMillis),
            dwellToSelectMillis: Double(settings.dwellToSelectMillis),
            selectionDebounceMillis: Double(settings.selectionDebounceMillis),
            selectionHighlightMillis: settings.selectionHighlightMillis,
            selectionSoundEnabled: settings.selectionSoundEnabled,
            auditoryFishingEnabled: settings.auditoryFishingEnabled,
            speechPolicy: settings.speechPolicy.name,
            selectKeyBinding: settings.selectKeyBinding,
            restModeKeyBinding: settings.restModeKeyBinding,
            pointerEmphasisStyle: settings.pointerEmphasisStyle.name,
            pointerEmphasisScale: Double(settings.pointerEmphasisScale),
            selectOnTouchDown: settings.selectOnTouchDown,
            swipeBetweenPages: settings.swipeBetweenPages,
            usageLoggingEnabled: settings.usageLoggingEnabled,
            featureUsageReportingEnabled: settings.featureUsageReportingEnabled,
            historyVisible: settings.historyVisible,
            likelyNextPageEnabled: settings.likelyNextPageEnabled
        )
    }

    func updateLikelyNextPageEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateLikelyNextPageEnabled(enabled: enabled) }
    func updateSelectOnTouchDown(_ enabled: Bool) async throws { try await resolveFacade().updateSelectOnTouchDown(enabled: enabled) }
    func updateSwipeBetweenPages(_ enabled: Bool) async throws { try await resolveFacade().updateSwipeBetweenPages(enabled: enabled) }
    func updateScanningEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateScanningEnabled(enabled: enabled) }
    func updateScanPlaybackAreaEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateScanPlaybackAreaEnabled(enabled: enabled) }
    func updateScanInputFieldEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateScanInputFieldEnabled(enabled: enabled) }
    func updateScanPhraseGridEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateScanPhraseGridEnabled(enabled: enabled) }
    func updateScanCategoryItemsEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateScanCategoryItemsEnabled(enabled: enabled) }
    func updateScanTopBarEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateScanTopBarEnabled(enabled: enabled) }
    func updateScanPhraseGridOrder(_ order: String) async throws { try await resolveFacade().updateScanPhraseGridOrder(order: order) }
    func updateScanDwellTimeSeconds(_ seconds: Float) async throws { try await resolveFacade().updateScanDwellTimeSeconds(seconds: seconds) }
    func updateScanAutoAdvanceSeconds(_ seconds: Float) async throws { try await resolveFacade().updateScanAutoAdvanceSeconds(seconds: seconds) }
    func updateShowLabels(_ enabled: Bool) async throws { try await resolveFacade().updateShowLabels(enabled: enabled) }
    func updateShowSymbols(_ enabled: Bool) async throws { try await resolveFacade().updateShowSymbols(enabled: enabled) }
    func updateLabelAtTop(_ enabled: Bool) async throws { try await resolveFacade().updateLabelAtTop(enabled: enabled) }
    func updateGridColumns(_ columns: Int32) async throws { try await resolveFacade().updateGridColumns(columns: columns) }
    func updateHighContrastMode(_ enabled: Bool) async throws { try await resolveFacade().updateHighContrastMode(enabled: enabled) }
    func updateWordTypeColorScheme(_ scheme: String) async throws { try await resolveFacade().updateWordTypeColorScheme(scheme: scheme) }
    func updateHoldToSelectMillis(_ millis: Int64) async throws { try await resolveFacade().updateHoldToSelectMillis(millis: millis) }
    func updateDwellToSelectMillis(_ millis: Int64) async throws { try await resolveFacade().updateDwellToSelectMillis(millis: millis) }
    func updateSelectKeyBinding(_ binding: String) async throws { try await resolveFacade().updateSelectKeyBinding(binding: binding) }
    func updateRestModeKeyBinding(_ binding: String) async throws { try await resolveFacade().updateRestModeKeyBinding(binding: binding) }
    func updatePointerEmphasis(style: String, scale: Float) async throws { try await resolveFacade().updatePointerEmphasis(style: style, scale: scale) }
    func updateSelectionHighlightMillis(_ millis: Int64) async throws { try await resolveFacade().updateSelectionHighlightMillis(millis: millis) }
    func updateSelectionDebounceMillis(_ millis: Int64) async throws { try await resolveFacade().updateSelectionDebounceMillis(millis: millis) }
    func updateSelectionSoundEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateSelectionSoundEnabled(enabled: enabled) }
    func updateAuditoryFishingEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateAuditoryFishingEnabled(enabled: enabled) }
    func updateSpeechPolicy(_ policy: String) async throws { try await resolveFacade().updateSpeechPolicy(policy: policy) }
    func updateBoardShowMessageBar(_ enabled: Bool) async throws { try await resolveFacade().updateBoardShowMessageBar(enabled: enabled) }
    func updateBoardShowSpeakButton(_ enabled: Bool) async throws { try await resolveFacade().updateBoardShowSpeakButton(enabled: enabled) }
    func updateUsageLoggingEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateUsageLoggingEnabled(enabled: enabled) }
    func updateHistoryVisible(_ visible: Bool) async throws { try await resolveFacade().updateHistoryVisible(visible: visible) }
    func updateFeatureUsageReportingEnabled(_ enabled: Bool) async throws { try await resolveFacade().updateFeatureUsageReportingEnabled(enabled: enabled) }
    func updateStartupUsesScreens(_ enabled: Bool) async throws { try await resolveFacade().updateStartupUsesScreens(enabled: enabled) }
    func updateStartupBoardSetId(_ id: String?) async throws { try await resolveFacade().updateStartupBoardSetId(id: id) }

    func speechPolicySpeaksSelection(policy: String, behavior: String) -> Bool {
        resolveFacade().speechPolicySpeaksSelection(policy: policy, behavior: behavior)
    }
}

/// Feature-scoped observable model for app settings, scanning preferences, access-input
/// tracking, and selection-highlight timing (#187).
@MainActor
final class AppSettingsModel: ObservableObject {
    // Scanning
    @Published private(set) var scanningEnabled: Bool = false
    @Published private(set) var scanPlaybackAreaEnabled: Bool = true
    @Published private(set) var scanInputFieldEnabled: Bool = true
    @Published private(set) var scanPhraseGridEnabled: Bool = true
    @Published private(set) var scanCategoryItemsEnabled: Bool = true
    @Published private(set) var scanTopBarEnabled: Bool = true
    @Published private(set) var scanPhraseGridOrder: String = "row-major"
    @Published private(set) var scanDwellTimeSeconds: Double = 1.0
    @Published private(set) var scanAutoAdvanceSeconds: Double = 1.2

    // Display
    @Published private(set) var showButtonLabels: Bool = true
    @Published private(set) var showButtonSymbols: Bool = true
    @Published private(set) var labelAtTop: Bool = false
    @Published private(set) var preferredGridColumns: Int = 3
    @Published private(set) var highContrastMode: Bool = false
    @Published private(set) var wordTypeColorScheme: String = "None"
    @Published private(set) var boardShowMessageBar: Bool = true
    @Published private(set) var boardShowSpeakButton: Bool = true

    // Timing, feedback, and pointer/switch access
    @Published private(set) var holdToSelectMillis: Double = 0
    @Published private(set) var dwellToSelectMillis: Double = 0
    @Published private(set) var selectionDebounceMillis: Double = 0
    @Published private(set) var selectionHighlightMillis: Int64 = 0 {
        didSet {
            if selectionHighlightMillis != oldValue {
                clearSelectionHighlight()
            }
        }
    }
    @Published private(set) var highlightedButtonId: String? = nil
    @Published private(set) var selectionSoundEnabled: Bool = false
    @Published private(set) var auditoryFishingEnabled: Bool = false
    @Published private(set) var speechPolicy: String = "Immediate"
    @Published private(set) var selectKeyBinding: String = ""
    @Published private(set) var restModeKeyBinding: String = ""
    @Published private(set) var pointerEmphasisStyle: String = "System"
    @Published private(set) var pointerEmphasisScale: Double = 1.5
    @Published private(set) var selectOnTouchDown: Bool = false
    @Published private(set) var swipeBetweenPages: Bool = false

    // Shared access-input policy mirrors
    @Published private(set) var inputIsPaused: Bool = false
    @Published private(set) var accessTargetId: String? = nil
    @Published private(set) var accessDwellProgress: Double = 0

    // Privacy & startup
    @Published private(set) var usageLoggingEnabled: Bool = false
    @Published private(set) var featureUsageReportingEnabled: Bool = false
    @Published private(set) var historyVisible: Bool = true
    @Published private(set) var likelyNextPageEnabled: Bool = false
    @Published private(set) var startupUsesScreens: Bool = false
    @Published private(set) var startupBoardSetId: String? = nil
    @Published private(set) var lastFailureKind: NativeFailureKind? = nil

    private let service: any AppSettingsService
    private let accessInput = AccessInputController()
    private let selectionHighlight = SelectionHighlight()
    private let nowMillisProvider: () -> Int64
    private let uptimeMillisProvider: () -> Int64
    private var accessActions: [String: () -> Void] = [:]
    private var refreshTask: Task<AppSettingsSnapshot?, Never>?
    private var pendingMutations: [String: Task<Bool, Never>] = [:]
    private var highlightTask: Task<Void, Never>?

    init(
        service: any AppSettingsService = SharedAppSettingsService(),
        nowMillis: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        uptimeMillis: @escaping () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    ) {
        self.service = service
        self.nowMillisProvider = nowMillis
        self.uptimeMillisProvider = uptimeMillis
    }

    deinit {
        refreshTask?.cancel()
        highlightTask?.cancel()
        pendingMutations.values.forEach { $0.cancel() }
    }

    func cancelPendingTasks() {
        refreshTask?.cancel()
        refreshTask = nil
        highlightTask?.cancel()
        highlightTask = nil
        pendingMutations.values.forEach { $0.cancel() }
        pendingMutations.removeAll()
    }

    @discardableResult
    func refresh(
        fallbackUseSystemTts: Bool = true,
        onEngineLoaded: ((Bool, String) -> Void)? = nil
    ) async -> AppSettingsSnapshot? {
        refreshTask?.cancel()
        let task = Task<AppSettingsSnapshot?, Never> { [weak self] in
            guard let self else { return nil }
            return await self.performRefresh(
                fallbackUseSystemTts: fallbackUseSystemTts,
                onEngineLoaded: onEngineLoaded
            )
        }
        refreshTask = task
        return await task.value
    }

    private func performRefresh(
        fallbackUseSystemTts: Bool,
        onEngineLoaded: ((Bool, String) -> Void)?
    ) async -> AppSettingsSnapshot? {
        do {
            let snapshot = try await service.loadSnapshot(fallbackUseSystemTts: fallbackUseSystemTts)
            guard !Task.isCancelled else { return nil }
            applySnapshot(snapshot)
            onEngineLoaded?(snapshot.useSystemTts, snapshot.ttsEngine)
            lastFailureKind = nil
            return snapshot
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return nil }
            // Keep safe clamped local defaults when shared settings are temporarily unavailable.
            scanPhraseGridOrder = normalizedScanGridOrder(scanPhraseGridOrder)
            scanDwellTimeSeconds = Double(clampedDwellSeconds(Float(scanDwellTimeSeconds)))
            scanAutoAdvanceSeconds = Double(clampedAutoAdvanceSeconds(Float(scanAutoAdvanceSeconds)))
            lastFailureKind = error.nativeFailure?.kind ?? .persistence
            return nil
        }
    }

    private func applySnapshot(_ snapshot: AppSettingsSnapshot) {
        scanningEnabled = snapshot.scanningEnabled
        scanPlaybackAreaEnabled = snapshot.scanPlaybackAreaEnabled
        scanInputFieldEnabled = snapshot.scanInputFieldEnabled
        scanPhraseGridEnabled = snapshot.scanPhraseGridEnabled
        scanCategoryItemsEnabled = snapshot.scanCategoryItemsEnabled
        scanTopBarEnabled = snapshot.scanTopBarEnabled
        scanPhraseGridOrder = normalizedScanGridOrder(snapshot.scanPhraseGridOrder)
        scanDwellTimeSeconds = Double(clampedDwellSeconds(Float(snapshot.scanDwellTimeSeconds)))
        scanAutoAdvanceSeconds = Double(clampedAutoAdvanceSeconds(Float(snapshot.scanAutoAdvanceSeconds)))
        showButtonLabels = snapshot.showButtonLabels
        showButtonSymbols = snapshot.showButtonSymbols
        labelAtTop = snapshot.labelAtTop
        preferredGridColumns = min(max(snapshot.preferredGridColumns, 1), 6)
        highContrastMode = snapshot.highContrastMode
        wordTypeColorScheme = snapshot.wordTypeColorScheme
        holdToSelectMillis = snapshot.holdToSelectMillis
        dwellToSelectMillis = snapshot.dwellToSelectMillis
        selectionDebounceMillis = snapshot.selectionDebounceMillis
        selectionSoundEnabled = snapshot.selectionSoundEnabled
        auditoryFishingEnabled = snapshot.auditoryFishingEnabled
        speechPolicy = snapshot.speechPolicy
        selectKeyBinding = snapshot.selectKeyBinding
        restModeKeyBinding = snapshot.restModeKeyBinding
        pointerEmphasisStyle = snapshot.pointerEmphasisStyle
        pointerEmphasisScale = snapshot.pointerEmphasisScale
        selectionHighlightMillis = snapshot.selectionHighlightMillis
        boardShowMessageBar = snapshot.boardShowMessageBar
        boardShowSpeakButton = snapshot.boardShowSpeakButton
        usageLoggingEnabled = snapshot.usageLoggingEnabled
        featureUsageReportingEnabled = snapshot.featureUsageReportingEnabled
        historyVisible = snapshot.historyVisible
        likelyNextPageEnabled = snapshot.likelyNextPageEnabled
        selectOnTouchDown = snapshot.selectOnTouchDown
        swipeBetweenPages = snapshot.swipeBetweenPages
        startupUsesScreens = snapshot.startupUsesScreens
        startupBoardSetId = snapshot.startupBoardSetId
    }

    // MARK: - Settings mutations (per-key cancellation + rollback on failure)

    @discardableResult
    func setLikelyNextPageEnabled(_ enabled: Bool, onChanged: (@MainActor () async -> Void)? = nil) -> Task<Bool, Never> {
        let previous = likelyNextPageEnabled
        likelyNextPageEnabled = enabled
        return persistSetting(
            key: "likelyNextPageEnabled",
            rollback: { self.likelyNextPageEnabled = previous },
            afterSave: onChanged
        ) { service in
            try await service.updateLikelyNextPageEnabled(enabled)
        }
    }

    @discardableResult
    func setSelectOnTouchDown(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = selectOnTouchDown
        selectOnTouchDown = enabled
        return persistSetting(key: "selectOnTouchDown", rollback: { self.selectOnTouchDown = previous }) {
            try await $0.updateSelectOnTouchDown(enabled)
        }
    }

    @discardableResult
    func setSwipeBetweenPages(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = swipeBetweenPages
        swipeBetweenPages = enabled
        return persistSetting(key: "swipeBetweenPages", rollback: { self.swipeBetweenPages = previous }) {
            try await $0.updateSwipeBetweenPages(enabled)
        }
    }

    @discardableResult
    func setScanningEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = scanningEnabled
        scanningEnabled = enabled
        return persistSetting(key: "scanningEnabled", rollback: { self.scanningEnabled = previous }) {
            try await $0.updateScanningEnabled(enabled)
        }
    }

    @discardableResult
    func setScanPlaybackAreaEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = scanPlaybackAreaEnabled
        scanPlaybackAreaEnabled = enabled
        return persistSetting(key: "scanPlaybackAreaEnabled", rollback: { self.scanPlaybackAreaEnabled = previous }) {
            try await $0.updateScanPlaybackAreaEnabled(enabled)
        }
    }

    @discardableResult
    func setScanInputFieldEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = scanInputFieldEnabled
        scanInputFieldEnabled = enabled
        return persistSetting(key: "scanInputFieldEnabled", rollback: { self.scanInputFieldEnabled = previous }) {
            try await $0.updateScanInputFieldEnabled(enabled)
        }
    }

    @discardableResult
    func setScanPhraseGridEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = scanPhraseGridEnabled
        scanPhraseGridEnabled = enabled
        return persistSetting(key: "scanPhraseGridEnabled", rollback: { self.scanPhraseGridEnabled = previous }) {
            try await $0.updateScanPhraseGridEnabled(enabled)
        }
    }

    @discardableResult
    func setScanCategoryItemsEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = scanCategoryItemsEnabled
        scanCategoryItemsEnabled = enabled
        return persistSetting(key: "scanCategoryItemsEnabled", rollback: { self.scanCategoryItemsEnabled = previous }) {
            try await $0.updateScanCategoryItemsEnabled(enabled)
        }
    }

    @discardableResult
    func setScanTopBarEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = scanTopBarEnabled
        scanTopBarEnabled = enabled
        return persistSetting(key: "scanTopBarEnabled", rollback: { self.scanTopBarEnabled = previous }) {
            try await $0.updateScanTopBarEnabled(enabled)
        }
    }

    @discardableResult
    func setScanPhraseGridOrder(_ order: String) -> Task<Bool, Never> {
        let previous = scanPhraseGridOrder
        let normalized = normalizedScanGridOrder(order)
        scanPhraseGridOrder = normalized
        return persistSetting(key: "scanPhraseGridOrder", rollback: { self.scanPhraseGridOrder = previous }) {
            try await $0.updateScanPhraseGridOrder(normalized)
        }
    }

    @discardableResult
    func setScanDwellTimeSeconds(_ value: Double) -> Task<Bool, Never> {
        let previous = scanDwellTimeSeconds
        let clamped = Double(clampedDwellSeconds(Float(value)))
        scanDwellTimeSeconds = clamped
        return persistSetting(key: "scanDwellTimeSeconds", rollback: { self.scanDwellTimeSeconds = previous }) {
            try await $0.updateScanDwellTimeSeconds(Float(clamped))
        }
    }

    @discardableResult
    func setScanAutoAdvanceSeconds(_ value: Double) -> Task<Bool, Never> {
        let previous = scanAutoAdvanceSeconds
        let clamped = Double(clampedAutoAdvanceSeconds(Float(value)))
        scanAutoAdvanceSeconds = clamped
        return persistSetting(key: "scanAutoAdvanceSeconds", rollback: { self.scanAutoAdvanceSeconds = previous }) {
            try await $0.updateScanAutoAdvanceSeconds(Float(clamped))
        }
    }

    @discardableResult
    func setShowButtonLabels(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = showButtonLabels
        showButtonLabels = enabled
        return persistSetting(key: "showButtonLabels", rollback: { self.showButtonLabels = previous }) {
            try await $0.updateShowLabels(enabled)
        }
    }

    @discardableResult
    func setShowButtonSymbols(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = showButtonSymbols
        showButtonSymbols = enabled
        return persistSetting(key: "showButtonSymbols", rollback: { self.showButtonSymbols = previous }) {
            try await $0.updateShowSymbols(enabled)
        }
    }

    @discardableResult
    func setLabelAtTop(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = labelAtTop
        labelAtTop = enabled
        return persistSetting(key: "labelAtTop", rollback: { self.labelAtTop = previous }) {
            try await $0.updateLabelAtTop(enabled)
        }
    }

    @discardableResult
    func setPreferredGridColumns(_ columns: Int) -> Task<Bool, Never> {
        let previous = preferredGridColumns
        let clamped = min(max(columns, 1), 6)
        preferredGridColumns = clamped
        return persistSetting(key: "preferredGridColumns", rollback: { self.preferredGridColumns = previous }) {
            try await $0.updateGridColumns(Int32(clamped))
        }
    }

    @discardableResult
    func setHighContrastMode(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = highContrastMode
        highContrastMode = enabled
        return persistSetting(key: "highContrastMode", rollback: { self.highContrastMode = previous }) {
            try await $0.updateHighContrastMode(enabled)
        }
    }

    @discardableResult
    func setWordTypeColorsEnabled(_ enabled: Bool, afterSave: (@MainActor () async -> Void)? = nil) -> Task<Bool, Never> {
        let previous = wordTypeColorScheme
        let scheme = enabled ? "Fitzgerald" : "None"
        wordTypeColorScheme = scheme
        return persistSetting(
            key: "wordTypeColorScheme",
            rollback: { self.wordTypeColorScheme = previous },
            afterSave: afterSave
        ) {
            try await $0.updateWordTypeColorScheme(scheme)
        }
    }

    @discardableResult
    func setHoldToSelectMillis(_ value: Double) -> Task<Bool, Never> {
        let previous = holdToSelectMillis
        let clamped = min(max(value, 0), 2_000)
        holdToSelectMillis = clamped
        return persistSetting(key: "holdToSelectMillis", rollback: { self.holdToSelectMillis = previous }) {
            try await $0.updateHoldToSelectMillis(Int64(clamped))
        }
    }

    @discardableResult
    func setDwellToSelectMillis(_ value: Double) -> Task<Bool, Never> {
        let previous = dwellToSelectMillis
        let clamped = min(max(value, 0), 5_000)
        dwellToSelectMillis = clamped
        return persistSetting(key: "dwellToSelectMillis", rollback: { self.dwellToSelectMillis = previous }) {
            try await $0.updateDwellToSelectMillis(Int64(clamped))
        }
    }

    @discardableResult
    func setSelectKeyBinding(_ value: String) -> Task<Bool, Never> {
        let previous = selectKeyBinding
        selectKeyBinding = value
        return persistSetting(key: "selectKeyBinding", rollback: { self.selectKeyBinding = previous }) {
            try await $0.updateSelectKeyBinding(value)
        }
    }

    @discardableResult
    func setRestModeKeyBinding(_ value: String) -> Task<Bool, Never> {
        let previous = restModeKeyBinding
        restModeKeyBinding = value
        return persistSetting(key: "restModeKeyBinding", rollback: { self.restModeKeyBinding = previous }) {
            try await $0.updateRestModeKeyBinding(value)
        }
    }

    @discardableResult
    func setPointerEmphasis(style: String? = nil, scale: Double? = nil) -> Task<Bool, Never> {
        let previousStyle = pointerEmphasisStyle
        let previousScale = pointerEmphasisScale
        if let style { pointerEmphasisStyle = style }
        if let scale { pointerEmphasisScale = min(max(scale, 1), 3) }
        let updatedStyle = pointerEmphasisStyle
        let updatedScale = pointerEmphasisScale
        return persistSetting(
            key: "pointerEmphasis",
            rollback: {
                self.pointerEmphasisStyle = previousStyle
                self.pointerEmphasisScale = previousScale
            }
        ) {
            try await $0.updatePointerEmphasis(style: updatedStyle, scale: Float(updatedScale))
        }
    }

    @discardableResult
    func setSelectionHighlightMillis(_ value: Double) -> Task<Bool, Never> {
        let previous = selectionHighlightMillis
        let clamped = Int64(min(max(value, 0), 2_000))
        selectionHighlightMillis = clamped
        return persistSetting(key: "selectionHighlightMillis", rollback: { self.selectionHighlightMillis = previous }) {
            try await $0.updateSelectionHighlightMillis(clamped)
        }
    }

    @discardableResult
    func setSelectionDebounceMillis(_ value: Double) -> Task<Bool, Never> {
        let previous = selectionDebounceMillis
        let clamped = min(max(value, 0), 1_000)
        selectionDebounceMillis = clamped
        return persistSetting(key: "selectionDebounceMillis", rollback: { self.selectionDebounceMillis = previous }) {
            try await $0.updateSelectionDebounceMillis(Int64(clamped))
        }
    }

    @discardableResult
    func setSelectionSoundEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = selectionSoundEnabled
        selectionSoundEnabled = enabled
        return persistSetting(key: "selectionSoundEnabled", rollback: { self.selectionSoundEnabled = previous }) {
            try await $0.updateSelectionSoundEnabled(enabled)
        }
    }

    @discardableResult
    func setAuditoryFishingEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = auditoryFishingEnabled
        auditoryFishingEnabled = enabled
        return persistSetting(key: "auditoryFishingEnabled", rollback: { self.auditoryFishingEnabled = previous }) {
            try await $0.updateAuditoryFishingEnabled(enabled)
        }
    }

    @discardableResult
    func setSpeechPolicy(_ policy: String) -> Task<Bool, Never> {
        guard policy == "Immediate" || policy == "SentenceOnly" else {
            return Task { false }
        }
        let previous = speechPolicy
        speechPolicy = policy
        return persistSetting(key: "speechPolicy", rollback: { self.speechPolicy = previous }) {
            try await $0.updateSpeechPolicy(policy)
        }
    }

    func shouldSpeakSelectionImmediately(resolvedBoardSpeechPolicy: String?, activationBehavior: String) -> Bool {
        service.speechPolicySpeaksSelection(
            policy: resolvedBoardSpeechPolicy ?? speechPolicy,
            behavior: activationBehavior
        )
    }

    @discardableResult
    func setBoardShowMessageBar(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = boardShowMessageBar
        boardShowMessageBar = enabled
        return persistSetting(key: "boardShowMessageBar", rollback: { self.boardShowMessageBar = previous }) {
            try await $0.updateBoardShowMessageBar(enabled)
        }
    }

    @discardableResult
    func setBoardShowSpeakButton(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = boardShowSpeakButton
        boardShowSpeakButton = enabled
        return persistSetting(key: "boardShowSpeakButton", rollback: { self.boardShowSpeakButton = previous }) {
            try await $0.updateBoardShowSpeakButton(enabled)
        }
    }

    @discardableResult
    func setUsageLoggingEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = usageLoggingEnabled
        usageLoggingEnabled = enabled
        return persistSetting(key: "usageLoggingEnabled", rollback: { self.usageLoggingEnabled = previous }) {
            try await $0.updateUsageLoggingEnabled(enabled)
        }
    }

    @discardableResult
    func setHistoryVisible(_ visible: Bool, afterSave: (@MainActor () async -> Void)? = nil) -> Task<Bool, Never> {
        let previous = historyVisible
        historyVisible = visible
        return persistSetting(
            key: "historyVisible",
            rollback: { self.historyVisible = previous },
            afterSave: afterSave
        ) {
            try await $0.updateHistoryVisible(visible)
        }
    }

    @discardableResult
    func setFeatureUsageReportingEnabled(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = featureUsageReportingEnabled
        featureUsageReportingEnabled = enabled
        return persistSetting(key: "featureUsageReportingEnabled", rollback: { self.featureUsageReportingEnabled = previous }) {
            try await $0.updateFeatureUsageReportingEnabled(enabled)
        }
    }

    @discardableResult
    func setStartupUsesScreens(_ enabled: Bool) -> Task<Bool, Never> {
        let previous = startupUsesScreens
        startupUsesScreens = enabled
        return persistSetting(key: "startupUsesScreens", rollback: { self.startupUsesScreens = previous }) {
            try await $0.updateStartupUsesScreens(enabled)
        }
    }

    @discardableResult
    func setStartupBoardSetId(_ id: String?) -> Task<Bool, Never> {
        let previous = startupBoardSetId
        startupBoardSetId = id
        return persistSetting(key: "startupBoardSetId", rollback: { self.startupBoardSetId = previous }) {
            try await $0.updateStartupBoardSetId(id)
        }
    }

    // MARK: - Selection highlight (#120)

    func triggerSelectionHighlight(buttonId: String) {
        guard selectionHighlightMillis > 0 else { return }
        highlightTask?.cancel()
        selectionHighlight.activate(targetId: buttonId, nowMillis: uptimeMillisProvider())
        highlightedButtonId = buttonId
        let duration = selectionHighlightMillis
        highlightTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(duration) * 1_000_000)
            } catch {
                return
            }
            guard let self, !Task.isCancelled else { return }
            self.highlightedButtonId = nil
        }
    }

    func clearSelectionHighlight() {
        highlightTask?.cancel()
        highlightTask = nil
        selectionHighlight.clear()
        highlightedButtonId = nil
    }

    // MARK: - Access input policy

    func registerAccessTarget(_ targetId: String, action: @escaping () -> Void) {
        accessActions[targetId] = action
    }

    func unregisterAccessTarget(_ targetId: String) {
        accessActions.removeValue(forKey: targetId)
        accessInput.targetExited(targetId: targetId, nowMillis: nowMillisProvider())
        accessInput.targetBlurred(targetId: targetId, nowMillis: nowMillisProvider())
        applyAccessInput()
    }

    func accessEnter(_ targetId: String) {
        accessInput.targetEntered(targetId: targetId, nowMillis: nowMillisProvider())
        applyAccessInput()
    }

    func accessExit(_ targetId: String) {
        accessInput.targetExited(targetId: targetId, nowMillis: nowMillisProvider())
        applyAccessInput()
    }

    func accessFocus(_ targetId: String) {
        accessInput.targetFocused(targetId: targetId, nowMillis: nowMillisProvider())
        applyAccessInput()
    }

    func accessBlur(_ targetId: String) {
        accessInput.targetBlurred(targetId: targetId, nowMillis: nowMillisProvider())
        applyAccessInput()
    }

    func accessKey(_ key: String, isDown: Bool) -> Bool {
        let normalized = key == " " ? "Space" : key
        guard normalized.caseInsensitiveCompare(selectKeyBinding) == .orderedSame ||
                normalized.caseInsensitiveCompare(restModeKeyBinding) == .orderedSame else { return false }
        let effect = isDown
            ? accessInput.keyDown(
                key: normalized,
                selectBinding: selectKeyBinding,
                restModeBinding: restModeKeyBinding,
                nowMillis: nowMillisProvider()
            )
            : accessInput.keyUp(key: normalized, nowMillis: nowMillisProvider())
        applyAccessInput(effect)
        return true
    }

    func tickAccessInput() {
        applyAccessInput(accessInput.tick(nowMillis: nowMillisProvider(), dwellMillis: Int64(dwellToSelectMillis)))
    }

    func toggleInputPause() {
        applyAccessInput(accessInput.togglePaused(nowMillis: nowMillisProvider()))
    }

    // MARK: - Helpers

    private func applyAccessInput(_ effect: (any AccessInputEffect)? = nil) {
        let state = accessInput.state
        if inputIsPaused != state.isPaused { inputIsPaused = state.isPaused }
        if accessTargetId != state.currentTargetId { accessTargetId = state.currentTargetId }
        let newProgress = Double(state.dwellProgress)
        if abs(accessDwellProgress - newProgress) > 0.001 { accessDwellProgress = newProgress }
        if let activation = effect as? AccessInputEffectActivate {
            accessActions[activation.targetId]?()
        }
    }

    @discardableResult
    private func persistSetting(
        key: String,
        rollback: @escaping @MainActor () -> Void,
        afterSave: (@MainActor () async -> Void)? = nil,
        operation: @escaping (any AppSettingsService) async throws -> Void
    ) -> Task<Bool, Never> {
        pendingMutations[key]?.cancel()
        lastFailureKind = nil
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            defer { self.pendingMutations[key] = nil }
            do {
                try await operation(self.service)
                guard !Task.isCancelled else { return false }
                if let afterSave {
                    await afterSave()
                }
                return true
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return false }
                rollback()
                self.lastFailureKind = error.nativeFailure?.kind ?? .persistence
                return false
            }
        }
        pendingMutations[key] = task
        return task
    }

    func normalizedScanGridOrder(_ value: String) -> String {
        switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "column-major":
            return "column-major"
        case "linear":
            return "linear"
        default:
            return "row-major"
        }
    }

    func clampedDwellSeconds(_ value: Float) -> Float {
        value.clamped(to: 0.3...2.0)
    }

    func clampedAutoAdvanceSeconds(_ value: Float) -> Float {
        value.clamped(to: 0.5...3.0)
    }
}
