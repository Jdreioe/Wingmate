import Foundation
import Shared

extension IosViewModel {
    func refreshEditingAccess() async {
        guard let state = try? await settingsFacade.editingAccessState() else { return }
        editingAccessEnabled = state.enabled
        editingAccessUnlocked = state.unlocked
        editingAccessSupported = state.supported
        editingAccessRetryAt = TimeInterval(state.retryNotBeforeMillis) / 1_000
    }

    func unlockEditingAccess(_ code: String) async -> Bool {
        let success = (try? await settingsFacade.unlockEditing(code: code))?.boolValue ?? false
        await refreshEditingAccess()
        return success
    }

    func configureEditingAccess(_ code: String) async -> Bool {
        do {
            try await settingsFacade.configureEditingAccess(code: code)
            await refreshEditingAccess()
            return true
        } catch {
            return false
        }
    }

    func disableEditingAccess(_ code: String) async -> Bool {
        let success = (try? await settingsFacade.disableEditingAccess(code: code))?.boolValue ?? false
        await refreshEditingAccess()
        return success
    }

    func lockEditingAccess() {
        settingsFacade.lockEditingAccess()
        editingAccessUnlocked = !editingAccessEnabled
    }

    func editingIsAuthorized() async -> Bool {
        await refreshEditingAccess()
        return !editingAccessEnabled || editingAccessUnlocked
    }

    func shareCompleteBackup() async throws -> Shared.BackupOperationResult {
        try await backupFacade.shareBackup()
    }

    func restoreCompleteBackup(path: String) async throws -> Shared.BackupOperationResult {
        let result = try await backupFacade.restoreBackup(path: path)
        if result.isSuccess {
            communicationFacade.refreshPhrases()
            try? await session.reloadAfterRestore()
            await loadTypingTray()
            await loadHistory()
        }
        return result
    }

    func effectiveLanguage(for v: Shared.Voice) -> String {
        func nonEmpty(_ s: String?) -> String? {
            let t = (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        if let s = nonEmpty(v.selectedLanguage) { return s }
        if let p = nonEmpty(v.primaryLanguage) { return p }
        return self.primaryLanguage
    }

    var canChangeVoiceLanguage: Bool {
        let languages = availableLanguages
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return Set(languages).count > 1
    }

    func refreshAzureConfiguration() async {
        do {
            let cfg = try await speechFacade.getSpeechConfig()
            let ep = cfg.endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            azureConfigured = !ep.isEmpty && cfg.credentialConfigured
        } catch {
            azureConfigured = false
        }
    }

    func refreshGoogleConfiguration() async {
        do {
            let status = try await speechFacade.getGoogleSpeechConfig()
            googleConfigured = status.credentialConfigured
        } catch {
            googleConfigured = false
        }
    }

    func saveGoogleApiKey(_ apiKey: String) async throws {
        _ = try await speechFacade.saveValidatedGoogleSpeechConfig(apiKey: apiKey)
        await refreshGoogleConfiguration()
        setTtsEngine("GOOGLE_CLOUD")
    }

    func clearGoogleApiKey() async throws {
        try await speechFacade.clearGoogleSpeechConfig()
        await refreshGoogleConfiguration()
    }

    func refreshElevenLabsConfiguration() async {
        do {
            let status = try await speechFacade.getElevenLabsSpeechConfig()
            elevenLabsConfigured = status.credentialConfigured
        } catch {
            elevenLabsConfigured = false
        }
    }

    func saveElevenLabsApiKey(_ apiKey: String) async throws {
        _ = try await speechFacade.saveValidatedElevenLabsSpeechConfig(apiKey: apiKey)
        await refreshElevenLabsConfiguration()
        setTtsEngine("ELEVENLABS")
    }

    func clearElevenLabsApiKey() async throws {
        try await speechFacade.clearElevenLabsSpeechConfig()
        await refreshElevenLabsConfiguration()
    }

    func refreshLanguagePreferences() async {
        do {
            let settings = try await settingsFacade.getSettings()
            await MainActor.run {
                self.primaryLanguage = settings.primaryLanguage
                self.secondaryLanguage = settings.secondaryLanguage
            }
        } catch {
            await MainActor.run {
                self.primaryLanguage = self.primaryLanguage
                self.secondaryLanguage = self.secondaryLanguage
            }
        }
    }

    func refreshScanningSettings() async {
        do {
            let settings = try await settingsFacade.getSettings()
            await MainActor.run {
                self.scanningEnabled = settings.scanningEnabled
                self.scanPlaybackAreaEnabled = settings.scanPlaybackAreaEnabled
                self.scanInputFieldEnabled = settings.scanInputFieldEnabled
                self.scanPhraseGridEnabled = settings.scanPhraseGridEnabled
                self.scanCategoryItemsEnabled = settings.scanCategoryItemsEnabled
                self.scanTopBarEnabled = settings.scanTopBarEnabled
                self.scanPhraseGridOrder = self.normalizedScanGridOrder(settings.scanPhraseGridOrder)
                self.scanDwellTimeSeconds = Double(self.clampedDwellSeconds(settings.scanDwellTimeSeconds))
                self.scanAutoAdvanceSeconds = Double(self.clampedAutoAdvanceSeconds(settings.scanAutoAdvanceSeconds))
            }
        } catch {
            await MainActor.run {
                self.scanPhraseGridOrder = self.normalizedScanGridOrder(self.scanPhraseGridOrder)
                self.scanDwellTimeSeconds = Double(self.clampedDwellSeconds(Float(self.scanDwellTimeSeconds)))
                self.scanAutoAdvanceSeconds = Double(self.clampedAutoAdvanceSeconds(Float(self.scanAutoAdvanceSeconds)))
            }
        }
    }

    func refreshParitySettings() async {
        do {
            let settings = try await settingsFacade.getSettings()
            let flags = try? await settingsFacade.iosSettingsFlags()
            let systemTts = flags?.usesSystemTts ?? useSystemTts
            let engine = flags?.ttsEngine ?? (systemTts ? "SYSTEM" : "AZURE_USER_RESOURCE")
            let opensScreens = flags?.startupUsesScreens ?? false
            await MainActor.run {
                self.useSystemTts = systemTts
                self.ttsEngine = engine
                UserDefaults.standard.set(systemTts, forKey: "use_system_tts")
                UserDefaults.standard.set(engine, forKey: "tts_engine")
                self.showButtonLabels = settings.showLabels
                self.showButtonSymbols = settings.showSymbols
                self.labelAtTop = settings.labelAtTop
                self.preferredGridColumns = min(max(Int(settings.gridColumns), 1), 6)
                self.highContrastMode = settings.highContrastMode
                self.wordTypeColorScheme = settings.wordTypeColorScheme.name
                self.holdToSelectMillis = Double(settings.holdToSelectMillis)
                self.dwellToSelectMillis = Double(settings.dwellToSelectMillis)
                self.dwellRearmDelayMillis = Double(settings.dwellRearmDelayMillis)
                self.accessInput.rearmDelayMillis = settings.dwellRearmDelayMillis
                self.selectionDebounceMillis = Double(settings.selectionDebounceMillis)
                self.selectionSoundEnabled = settings.selectionSoundEnabled
                self.auditoryFishingEnabled = settings.auditoryFishingEnabled
                self.speechPolicy = settings.speechPolicy.name
                self.selectKeyBinding = settings.selectKeyBinding
                self.restModeKeyBinding = settings.restModeKeyBinding
                self.pointerEmphasisStyle = settings.pointerEmphasisStyle.name
                self.pointerEmphasisScale = Double(settings.pointerEmphasisScale)
                self.selectionHighlightMillis = settings.selectionHighlightMillis
                self.boardShowMessageBar = settings.boardShowMessageBar
                self.boardShowSpeakButton = settings.boardShowSpeakButton
                self.usageLoggingEnabled = settings.usageLoggingEnabled
                self.featureUsageReportingEnabled = settings.featureUsageReportingEnabled
                self.historyVisible = settings.historyVisible
                self.likelyNextPageEnabled = settings.likelyNextPageEnabled
                self.selectOnTouchDown = settings.selectOnTouchDown
                self.selectOnTouchExit = settings.selectOnTouchExit
                self.swipeBetweenPages = settings.swipeBetweenPages
                self.startupUsesScreens = opensScreens
                self.startupBoardSetId = settings.startupBoardSetId
            }
            await loadHistory()
        } catch {
            // Keep the native defaults when shared settings are unavailable.
        }
    }

    func setLikelyNextPageEnabled(_ enabled: Bool) {
        likelyNextPageEnabled = enabled
        if !enabled { likelyNextPhrases = [] }
        Task {
            try? await settingsFacade.updateLikelyNextPageEnabled(enabled: enabled)
            await loadHistory()
        }
    }

    func setSelectOnTouchDown(_ enabled: Bool) {
        selectOnTouchDown = enabled
        if enabled { selectOnTouchExit = false }
        Task { try? await settingsFacade.updateSelectOnTouchDown(enabled: enabled) }
    }

    func setSelectOnTouchExit(_ enabled: Bool) {
        selectOnTouchExit = enabled
        if enabled { selectOnTouchDown = false }
        Task { try? await settingsFacade.updateSelectOnTouchExit(enabled: enabled) }
    }

    func setSwipeBetweenPages(_ enabled: Bool) {
        swipeBetweenPages = enabled
        Task { try? await settingsFacade.updateSwipeBetweenPages(enabled: enabled) }
    }

    func setTtsEngine(_ engine: String) {
        ttsEngine = engine
        useSystemTts = engine == "SYSTEM"
        UserDefaults.standard.set(engine, forKey: "tts_engine")
        UserDefaults.standard.set(useSystemTts, forKey: "use_system_tts")
        Task { try? await speechFacade.updateTtsEngineNamed(engine: engine) }
    }

    func setScanningEnabled(_ enabled: Bool) {
        self.scanningEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanningEnabled(enabled: enabled) }
    }

    func setScanPlaybackAreaEnabled(_ enabled: Bool) {
        self.scanPlaybackAreaEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanPlaybackAreaEnabled(enabled: enabled) }
    }

    func setScanInputFieldEnabled(_ enabled: Bool) {
        self.scanInputFieldEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanInputFieldEnabled(enabled: enabled) }
    }

    func setScanPhraseGridEnabled(_ enabled: Bool) {
        self.scanPhraseGridEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanPhraseGridEnabled(enabled: enabled) }
    }

    func setScanCategoryItemsEnabled(_ enabled: Bool) {
        self.scanCategoryItemsEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanCategoryItemsEnabled(enabled: enabled) }
    }

    func setScanTopBarEnabled(_ enabled: Bool) {
        self.scanTopBarEnabled = enabled
        Task { _ = try? await settingsFacade.updateScanTopBarEnabled(enabled: enabled) }
    }

    func setScanPhraseGridOrder(_ order: String) {
        let normalized = normalizedScanGridOrder(order)
        self.scanPhraseGridOrder = normalized
        Task { _ = try? await settingsFacade.updateScanPhraseGridOrder(order: normalized) }
    }

    func setScanDwellTimeSeconds(_ value: Double) {
        let clamped = Double(clampedDwellSeconds(Float(value)))
        self.scanDwellTimeSeconds = clamped
        Task { _ = try? await settingsFacade.updateScanDwellTimeSeconds(seconds: Float(clamped)) }
    }

    func setScanAutoAdvanceSeconds(_ value: Double) {
        let clamped = Double(clampedAutoAdvanceSeconds(Float(value)))
        self.scanAutoAdvanceSeconds = clamped
        Task { _ = try? await settingsFacade.updateScanAutoAdvanceSeconds(seconds: Float(clamped)) }
    }

    func setShowButtonLabels(_ enabled: Bool) {
        showButtonLabels = enabled
        Task { _ = try? await settingsFacade.updateShowLabels(enabled: enabled) }
    }

    func setShowButtonSymbols(_ enabled: Bool) {
        showButtonSymbols = enabled
        Task { _ = try? await settingsFacade.updateShowSymbols(enabled: enabled) }
    }

    func setLabelAtTop(_ enabled: Bool) {
        labelAtTop = enabled
        Task { _ = try? await settingsFacade.updateLabelAtTop(enabled: enabled) }
    }

    func setPreferredGridColumns(_ columns: Int) {
        preferredGridColumns = min(max(columns, 1), 6)
        Task { _ = try? await settingsFacade.updateGridColumns(columns: Int32(preferredGridColumns)) }
    }

    func setHighContrastMode(_ enabled: Bool) {
        highContrastMode = enabled
        Task { _ = try? await settingsFacade.updateHighContrastMode(enabled: enabled) }
    }

    func setWordTypeColorsEnabled(_ enabled: Bool) {
        wordTypeColorScheme = enabled ? "Fitzgerald" : "None"
        Task {
            _ = try? await settingsFacade.updateWordTypeColorScheme(scheme: wordTypeColorScheme)
            await refreshBoardCells()
        }
    }

    func setHoldToSelectMillis(_ value: Double) {
        holdToSelectMillis = min(max(value, 0), 2_000)
        Task { _ = try? await settingsFacade.updateHoldToSelectMillis(millis: Int64(holdToSelectMillis)) }
    }

    func setDwellToSelectMillis(_ value: Double) {
        dwellToSelectMillis = min(max(value, 0), 5_000)
        Task { _ = try? await settingsFacade.updateDwellToSelectMillis(millis: Int64(dwellToSelectMillis)) }
    }

    func setDwellRearmDelayMillis(_ value: Double) {
        dwellRearmDelayMillis = min(max(value, 0), 500)
        accessInput.rearmDelayMillis = Int64(dwellRearmDelayMillis)
        Task { _ = try? await settingsFacade.updateDwellRearmDelayMillis(millis: Int64(dwellRearmDelayMillis)) }
    }

    func setSelectKeyBinding(_ value: String) {
        selectKeyBinding = value
        Task { _ = try? await settingsFacade.updateSelectKeyBinding(binding: value) }
    }

    func setRestModeKeyBinding(_ value: String) {
        restModeKeyBinding = value
        Task { _ = try? await settingsFacade.updateRestModeKeyBinding(binding: value) }
    }

    func setPointerEmphasis(style: String? = nil, scale: Double? = nil) {
        if let style { pointerEmphasisStyle = style }
        if let scale { pointerEmphasisScale = min(max(scale, 1), 3) }
        Task { _ = try? await settingsFacade.updatePointerEmphasis(style: pointerEmphasisStyle, scale: Float(pointerEmphasisScale)) }
    }

    func registerAccessTarget(_ targetId: String, action: @escaping () -> Void) {
        accessActions[targetId] = action
    }

    func unregisterAccessTarget(_ targetId: String) {
        accessActions.removeValue(forKey: targetId)
        accessInput.targetExited(targetId: targetId, nowMillis: nowMillis())
        accessInput.targetBlurred(targetId: targetId, nowMillis: nowMillis())
        applyAccessInput()
    }

    func accessEnter(_ targetId: String) {
        accessInput.targetEntered(targetId: targetId, nowMillis: nowMillis())
        applyAccessInput()
    }
    func accessExit(_ targetId: String) {
        accessInput.targetExited(targetId: targetId, nowMillis: nowMillis())
        applyAccessInput()
    }
    func accessFocus(_ targetId: String) {
        accessInput.targetFocused(targetId: targetId, nowMillis: nowMillis())
        applyAccessInput()
    }
    func accessBlur(_ targetId: String) {
        accessInput.targetBlurred(targetId: targetId, nowMillis: nowMillis())
        applyAccessInput()
    }

    func accessKey(_ key: String, isDown: Bool) -> Bool {
        let normalized = key == " " ? "Space" : key
        guard normalized.caseInsensitiveCompare(selectKeyBinding) == .orderedSame ||
                normalized.caseInsensitiveCompare(restModeKeyBinding) == .orderedSame else { return false }
        let effect = isDown
            ? accessInput.keyDown(
                key: normalized, selectBinding: selectKeyBinding, restModeBinding: restModeKeyBinding, nowMillis: nowMillis()
            )
            : accessInput.keyUp(key: normalized, nowMillis: nowMillis())
        applyAccessInput(effect)
        return true
    }

    func tickAccessInput() {
        accessInput.rearmDelayMillis = Int64(dwellRearmDelayMillis)
        applyAccessInput(accessInput.tick(nowMillis: nowMillis(), dwellMillis: Int64(dwellToSelectMillis)))
    }
    func toggleInputPause() { applyAccessInput(accessInput.togglePaused(nowMillis: nowMillis())) }

    /// Mirrors the shared access state and runs the Button the policy activated, if any.
    func applyAccessInput(_ effect: (any AccessInputEffect)? = nil) {
        let state = accessInput.state
        if inputIsPaused != state.isPaused { inputIsPaused = state.isPaused }
        if accessTargetId != state.currentTargetId { accessTargetId = state.currentTargetId }
        let newProgress = Double(state.dwellProgress)
        if abs(accessDwellProgress - newProgress) > 0.001 { accessDwellProgress = newProgress }
        if let activation = effect as? AccessInputEffectActivate {
            AccessHaptics.perform(.confirm)
            accessActions[activation.targetId]?()
        } else if effect is AccessInputEffectPauseChanged {
            AccessHaptics.perform(.tick)
        }
    }

    func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func setSelectionHighlightMillis(_ value: Double) {
        selectionHighlightMillis = Int64(min(max(value, 0), 2_000))
        Task { _ = try? await settingsFacade.updateSelectionHighlightMillis(millis: selectionHighlightMillis) }
    }

    func setSelectionDebounceMillis(_ value: Double) {
        selectionDebounceMillis = min(max(value, 0), 1_000)
        Task { _ = try? await settingsFacade.updateSelectionDebounceMillis(millis: Int64(selectionDebounceMillis)) }
    }

    func setSelectionSoundEnabled(_ enabled: Bool) {
        selectionSoundEnabled = enabled
        Task { _ = try? await settingsFacade.updateSelectionSoundEnabled(enabled: enabled) }
    }

    func setAuditoryFishingEnabled(_ enabled: Bool) {
        auditoryFishingEnabled = enabled
        Task { _ = try? await settingsFacade.updateAuditoryFishingEnabled(enabled: enabled) }
    }

    func setSpeechPolicy(_ policy: String) {
        guard policy == "Immediate" || policy == "SentenceOnly" else { return }
        speechPolicy = policy
        Task { _ = try? await settingsFacade.updateSpeechPolicy(policy: policy) }
    }

    /// Whether a single board/button selection speaks immediately, honoring the
    /// effective Screen speech policy and the resolved board activation behavior.
    var shouldSpeakSelectionImmediately: Bool {
        settingsFacade.speechPolicySpeaksSelection(policy: resolvedBoardSpeechPolicy ?? speechPolicy, behavior: boardActivationBehavior)
    }

    func setBoardShowMessageBar(_ enabled: Bool) {
        boardShowMessageBar = enabled
        Task { _ = try? await settingsFacade.updateBoardShowMessageBar(enabled: enabled) }
    }

    func setBoardShowSpeakButton(_ enabled: Bool) {
        boardShowSpeakButton = enabled
        Task { _ = try? await settingsFacade.updateBoardShowSpeakButton(enabled: enabled) }
    }

    func setUsageLoggingEnabled(_ enabled: Bool) {
        usageLoggingEnabled = enabled
        Task { _ = try? await settingsFacade.updateUsageLoggingEnabled(enabled: enabled) }
    }

    func setHistoryVisible(_ visible: Bool) {
        historyVisible = visible
        if visible {
            Task { await loadHistory() }
        } else {
            historyPhrases = []
            likelyNextPhrases = []
        }
        Task { _ = try? await settingsFacade.updateHistoryVisible(visible: visible) }
    }

    func setFeatureUsageReportingEnabled(_ enabled: Bool) {
        featureUsageReportingEnabled = enabled
        Task { _ = try? await settingsFacade.updateFeatureUsageReportingEnabled(enabled: enabled) }
    }

    func setStartupUsesScreens(_ enabled: Bool) {
        startupUsesScreens = enabled
        Task { _ = try? await settingsFacade.updateStartupUsesScreens(enabled: enabled) }
    }

    func setStartupBoardSetId(_ id: String?) {
        startupBoardSetId = id
        Task { _ = try? await settingsFacade.updateStartupBoardSetId(id: id) }
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

    func chooseVoice(_ v: Shared.Voice) async {
        do {
            try await speechFacade.selectVoiceAndMaybeUpdatePrimary(voice: v)
            await MainActor.run {
                self.selectedVoice = v
                if let langs = v.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
                self.primaryLanguage = effectiveLanguage(for: v)
            }
            let persisted = try? await speechFacade.selectedVoice()
            if let pv = persisted {
                self.selectedVoice = pv
                if let langs = pv.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
                self.primaryLanguage = effectiveLanguage(for: pv)
            }
        } catch {
            // swallow for now
        }
    }

    func updateLanguage(_ lang: String) {
        Task {
            _ = try? await speechFacade.updateSelectedVoiceLanguage(lang: lang)
            self.primaryLanguage = lang
            refreshVoiceAndLanguages()
        }
    }

    func updateSecondaryLanguage(_ lang: String) {
        Task {
            _ = try? await settingsFacade.updateSecondaryLanguage(lang: lang)
            self.secondaryLanguage = lang
            // Highlight the spans tagged with the new secondary language.
            self.applySessionState(self.session.state())
        }
    }

    func clampedSelectionRange(_ range: NSRange, maxLength: Int) -> NSRange {
        if range.location == NSNotFound {
            return NSRange(location: maxLength, length: 0)
        }
        let safeLocation = min(max(0, range.location), maxLength)
        let safeLength = min(max(0, range.length), max(0, maxLength - safeLocation))
        return NSRange(location: safeLocation, length: safeLength)
    }

    func refreshVoiceAndLanguages() {
        Task {
            let v = try? await speechFacade.selectedVoice()
            self.selectedVoice = v
            if let langs = v?.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
            if let v = v { self.primaryLanguage = effectiveLanguage(for: v) }
        }
    }

    // MARK: - Pronunciations
    func loadPronunciations() async {
        // Cancellation leaves the dictionary and its notice as they were.
        guard let result = try? await pronunciationFacade.list() else { return }
        await MainActor.run {
            // A failed read keeps the entries already shown.
            if result.isSuccess { self.pronunciations = result.entries }
            self.pronunciationError = result.isSuccess ? nil : NSLocalizedString("pronunciation.error.load_failed", comment: "")
        }
    }

    func addPronunciation(word: String, phoneme: String, alphabet: String) {
        Task {
            guard let result = try? await pronunciationFacade.add(word: word, phoneme: phoneme, alphabet: alphabet) else { return }
            await applyPronunciationChange(result)
        }
    }

    func deletePronunciation(word: String) {
        Task {
            guard let result = try? await pronunciationFacade.delete(word: word) else { return }
            await applyPronunciationChange(result)
        }
    }

    func applyPronunciationChange(_ result: Shared.PronunciationResult) async {
        await loadPronunciations()
        if !result.isSuccess {
            await MainActor.run {
                self.pronunciationError = NSLocalizedString("pronunciation.error.save_failed", comment: "")
            }
        }
    }
}
