import Foundation
import Shared

/// Pure Swift snapshot of primary/secondary language preferences from `Shared.SettingsFacade`.
struct LanguagePreferencesSnapshot: Equatable {
    let primaryLanguage: String
    let secondaryLanguage: String
}

/// Protocol seam around `Shared.SpeechFacade` and language settings on `Shared.SettingsFacade`
/// so `SpeechSettingsModel` can be unit-tested with deterministic fakes and cancellation probes.
protocol SpeechSettingsService {
    func isAzureConfigured() async throws -> Bool
    func isGoogleConfigured() async throws -> Bool
    func isElevenLabsConfigured() async throws -> Bool
    func saveGoogleApiKey(_ apiKey: String) async throws
    func clearGoogleApiKey() async throws
    func saveElevenLabsApiKey(_ apiKey: String) async throws
    func clearElevenLabsApiKey() async throws
    func selectedVoice() async throws -> Shared.Voice?
    func selectVoiceAndMaybeUpdatePrimary(_ voice: Shared.Voice) async throws
    func updateSelectedVoiceLanguage(_ lang: String) async throws
    func updateTtsEngineNamed(_ engine: String) async throws
    func languagePreferences() async throws -> LanguagePreferencesSnapshot
    func updateSecondaryLanguage(_ lang: String) async throws
}

struct SharedSpeechSettingsService: SpeechSettingsService {
    private let resolveSpeechFacade: () -> Shared.SpeechFacade
    private let resolveSettingsFacade: () -> Shared.SettingsFacade

    init(
        resolveSpeechFacade: @escaping () -> Shared.SpeechFacade = { IosDiBridge().speechFacade() },
        resolveSettingsFacade: @escaping () -> Shared.SettingsFacade = { IosDiBridge().settingsFacade() }
    ) {
        self.resolveSpeechFacade = resolveSpeechFacade
        self.resolveSettingsFacade = resolveSettingsFacade
    }

    func isAzureConfigured() async throws -> Bool {
        let cfg = try await resolveSpeechFacade().getSpeechConfig()
        let endpoint = cfg.endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        return !endpoint.isEmpty && cfg.credentialConfigured
    }

    func isGoogleConfigured() async throws -> Bool {
        try await resolveSpeechFacade().getGoogleSpeechConfig().credentialConfigured
    }

    func isElevenLabsConfigured() async throws -> Bool {
        try await resolveSpeechFacade().getElevenLabsSpeechConfig().credentialConfigured
    }

    func saveGoogleApiKey(_ apiKey: String) async throws {
        _ = try await resolveSpeechFacade().saveValidatedGoogleSpeechConfig(apiKey: apiKey)
    }

    func clearGoogleApiKey() async throws {
        try await resolveSpeechFacade().clearGoogleSpeechConfig()
    }

    func saveElevenLabsApiKey(_ apiKey: String) async throws {
        _ = try await resolveSpeechFacade().saveValidatedElevenLabsSpeechConfig(apiKey: apiKey)
    }

    func clearElevenLabsApiKey() async throws {
        try await resolveSpeechFacade().clearElevenLabsSpeechConfig()
    }

    func selectedVoice() async throws -> Shared.Voice? {
        try await resolveSpeechFacade().selectedVoice()
    }

    func selectVoiceAndMaybeUpdatePrimary(_ voice: Shared.Voice) async throws {
        try await resolveSpeechFacade().selectVoiceAndMaybeUpdatePrimary(voice: voice)
    }

    func updateSelectedVoiceLanguage(_ lang: String) async throws {
        try await resolveSpeechFacade().updateSelectedVoiceLanguage(lang: lang)
    }

    func updateTtsEngineNamed(_ engine: String) async throws {
        try await resolveSpeechFacade().updateTtsEngineNamed(engine: engine)
    }

    func languagePreferences() async throws -> LanguagePreferencesSnapshot {
        let settings = try await resolveSettingsFacade().getSettings()
        return LanguagePreferencesSnapshot(
            primaryLanguage: settings.primaryLanguage,
            secondaryLanguage: settings.secondaryLanguage
        )
    }

    func updateSecondaryLanguage(_ lang: String) async throws {
        try await resolveSettingsFacade().updateSecondaryLanguage(lang: lang)
    }
}

/// Feature-scoped observable model for speech engine selection, cloud TTS provider setup,
/// selected voice, and primary/secondary language preferences (#187).
@MainActor
final class SpeechSettingsModel: ObservableObject {
    @Published private(set) var ttsEngine: String
    @Published private(set) var useSystemTts: Bool
    @Published private(set) var azureConfigured: Bool = false
    @Published private(set) var googleConfigured: Bool = false
    @Published private(set) var elevenLabsConfigured: Bool = false
    @Published private(set) var selectedVoice: Shared.Voice? = nil
    @Published private(set) var availableLanguages: [String] = []
    @Published private(set) var primaryLanguage: String = "en-US"
    @Published private(set) var secondaryLanguage: String = "en-US"
    @Published private(set) var lastFailureKind: NativeFailureKind? = nil

    private let service: any SpeechSettingsService
    private let defaults: UserDefaults
    private var voiceRefreshTask: Task<Void, Never>?
    private var engineUpdateTask: Task<Bool, Never>?
    private var primaryLanguageTask: Task<Bool, Never>?
    private var secondaryLanguageTask: Task<Bool, Never>?

    init(
        service: any SpeechSettingsService = SharedSpeechSettingsService(),
        defaults: UserDefaults = .standard
    ) {
        self.service = service
        self.defaults = defaults
        let storedSystemTts = defaults.bool(forKey: "use_system_tts")
        let storedEngine = defaults.string(forKey: "tts_engine") ?? "SYSTEM"
        self.useSystemTts = storedSystemTts
        self.ttsEngine = storedEngine
    }

    deinit {
        voiceRefreshTask?.cancel()
        engineUpdateTask?.cancel()
        primaryLanguageTask?.cancel()
        secondaryLanguageTask?.cancel()
    }

    func cancelPendingTasks() {
        voiceRefreshTask?.cancel()
        voiceRefreshTask = nil
        engineUpdateTask?.cancel()
        engineUpdateTask = nil
        primaryLanguageTask?.cancel()
        primaryLanguageTask = nil
        secondaryLanguageTask?.cancel()
        secondaryLanguageTask = nil
    }

    var canChangeVoiceLanguage: Bool {
        let languages = availableLanguages
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return Set(languages).count > 1
    }

    func effectiveLanguage(for voice: Shared.Voice) -> String {
        func nonEmpty(_ value: String?) -> String? {
            let trimmed = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        if let selected = nonEmpty(voice.selectedLanguage) { return selected }
        if let primary = nonEmpty(voice.primaryLanguage) { return primary }
        return primaryLanguage
    }

    func applyEngineFromSettings(useSystemTts: Bool, ttsEngine: String) {
        self.useSystemTts = useSystemTts
        self.ttsEngine = ttsEngine
        defaults.set(useSystemTts, forKey: "use_system_tts")
        defaults.set(ttsEngine, forKey: "tts_engine")
    }

    func refreshAzureConfiguration() async {
        do {
            let configured = try await service.isAzureConfigured()
            guard !Task.isCancelled else { return }
            azureConfigured = configured
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            azureConfigured = false
        }
    }

    func refreshGoogleConfiguration() async {
        do {
            let configured = try await service.isGoogleConfigured()
            guard !Task.isCancelled else { return }
            googleConfigured = configured
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            googleConfigured = false
        }
    }

    func refreshElevenLabsConfiguration() async {
        do {
            let configured = try await service.isElevenLabsConfigured()
            guard !Task.isCancelled else { return }
            elevenLabsConfigured = configured
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            elevenLabsConfigured = false
        }
    }

    func refreshAllProviderConfigurations() async {
        await refreshAzureConfiguration()
        await refreshGoogleConfiguration()
        await refreshElevenLabsConfiguration()
    }

    func saveGoogleApiKey(_ apiKey: String) async throws {
        try await service.saveGoogleApiKey(apiKey)
        await refreshGoogleConfiguration()
        _ = await setTtsEngine("GOOGLE_CLOUD").value
    }

    func clearGoogleApiKey() async throws {
        try await service.clearGoogleApiKey()
        await refreshGoogleConfiguration()
    }

    func saveElevenLabsApiKey(_ apiKey: String) async throws {
        try await service.saveElevenLabsApiKey(apiKey)
        await refreshElevenLabsConfiguration()
        _ = await setTtsEngine("ELEVENLABS").value
    }

    func clearElevenLabsApiKey() async throws {
        try await service.clearElevenLabsApiKey()
        await refreshElevenLabsConfiguration()
    }

    func refreshLanguagePreferences() async {
        do {
            let snapshot = try await service.languagePreferences()
            guard !Task.isCancelled else { return }
            primaryLanguage = snapshot.primaryLanguage
            secondaryLanguage = snapshot.secondaryLanguage
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            lastFailureKind = error.nativeFailure?.kind ?? .persistence
        }
    }

    @discardableResult
    func setTtsEngine(_ engine: String) -> Task<Bool, Never> {
        engineUpdateTask?.cancel()
        let previousEngine = ttsEngine
        let previousUseSystemTts = useSystemTts
        applyEngineFromSettings(useSystemTts: engine == "SYSTEM", ttsEngine: engine)
        lastFailureKind = nil
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            do {
                try await self.service.updateTtsEngineNamed(engine)
                guard !Task.isCancelled else { return false }
                return true
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return false }
                self.applyEngineFromSettings(useSystemTts: previousUseSystemTts, ttsEngine: previousEngine)
                self.lastFailureKind = error.nativeFailure?.kind ?? .persistence
                return false
            }
        }
        engineUpdateTask = task
        return task
    }

    @discardableResult
    func chooseVoice(_ voice: Shared.Voice) async -> Bool {
        lastFailureKind = nil
        do {
            try await service.selectVoiceAndMaybeUpdatePrimary(voice)
            guard !Task.isCancelled else { return false }
            applyVoice(voice)
            do {
                if let persisted = try await service.selectedVoice() {
                    guard !Task.isCancelled else { return true }
                    applyVoice(persisted)
                }
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return true }
                // The selection already succeeded; keep the locally applied voice.
            }
            return true
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return false }
            lastFailureKind = error.nativeFailure?.kind ?? .persistence
            return false
        }
    }

    @discardableResult
    func updateLanguage(_ lang: String) -> Task<Bool, Never> {
        primaryLanguageTask?.cancel()
        let previousLanguage = primaryLanguage
        primaryLanguage = lang
        lastFailureKind = nil
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            do {
                try await self.service.updateSelectedVoiceLanguage(lang)
                guard !Task.isCancelled else { return false }
                await self.performVoiceRefresh()
                return true
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return false }
                self.primaryLanguage = previousLanguage
                self.lastFailureKind = error.nativeFailure?.kind ?? .persistence
                return false
            }
        }
        primaryLanguageTask = task
        return task
    }

    @discardableResult
    func updateSecondaryLanguage(
        _ lang: String,
        onApplied: (@MainActor () -> Void)? = nil
    ) -> Task<Bool, Never> {
        secondaryLanguageTask?.cancel()
        let previousLanguage = secondaryLanguage
        secondaryLanguage = lang
        lastFailureKind = nil
        let task = Task<Bool, Never> { [weak self] in
            guard let self else { return false }
            do {
                try await self.service.updateSecondaryLanguage(lang)
                guard !Task.isCancelled else { return false }
                onApplied?()
                return true
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return false }
                self.secondaryLanguage = previousLanguage
                self.lastFailureKind = error.nativeFailure?.kind ?? .persistence
                return false
            }
        }
        secondaryLanguageTask = task
        return task
    }

    @discardableResult
    func refreshVoiceAndLanguages() -> Task<Void, Never> {
        voiceRefreshTask?.cancel()
        let task = Task<Void, Never> { [weak self] in
            guard let self else { return }
            await self.performVoiceRefresh()
        }
        voiceRefreshTask = task
        return task
    }

    private func performVoiceRefresh() async {
        do {
            let voice = try await service.selectedVoice()
            guard !Task.isCancelled else { return }
            selectedVoice = voice
            availableLanguages = voice?.supportedLanguages ?? []
            if let voice {
                primaryLanguage = effectiveLanguage(for: voice)
            }
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            lastFailureKind = error.nativeFailure?.kind ?? .persistence
        }
    }

    private func applyVoice(_ voice: Shared.Voice) {
        selectedVoice = voice
        availableLanguages = voice.supportedLanguages ?? []
        primaryLanguage = effectiveLanguage(for: voice)
    }
}
