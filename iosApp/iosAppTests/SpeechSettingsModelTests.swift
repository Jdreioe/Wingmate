import XCTest
import Shared
@testable import Wingmate

@MainActor
final class SpeechSettingsModelTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "SpeechSettingsModelTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testSetTtsEnginePersistsAndUpdatesUserDefaults() async {
        let service = FakeSpeechSettingsService()
        var persistedEngine: String?
        service.updateTtsEngineHandler = { engine in persistedEngine = engine }
        let model = SpeechSettingsModel(service: service, defaults: defaults)

        let ok = await model.setTtsEngine("AZURE_USER_RESOURCE").value

        XCTAssertTrue(ok)
        XCTAssertEqual(persistedEngine, "AZURE_USER_RESOURCE")
        XCTAssertEqual(model.ttsEngine, "AZURE_USER_RESOURCE")
        XCTAssertFalse(model.useSystemTts)
        XCTAssertEqual(defaults.string(forKey: "tts_engine"), "AZURE_USER_RESOURCE")
        XCTAssertFalse(defaults.bool(forKey: "use_system_tts"))
        XCTAssertNil(model.lastFailureKind)
    }

    func testSetTtsEngineRollsBackStateAndDefaultsOnFailure() async {
        let service = FakeSpeechSettingsService()
        service.updateTtsEngineHandler = { _ in throw FakeSpeechError.storage }
        defaults.set("SYSTEM", forKey: "tts_engine")
        defaults.set(true, forKey: "use_system_tts")
        let model = SpeechSettingsModel(service: service, defaults: defaults)

        let ok = await model.setTtsEngine("GOOGLE_CLOUD").value

        XCTAssertFalse(ok)
        XCTAssertEqual(model.ttsEngine, "SYSTEM")
        XCTAssertTrue(model.useSystemTts)
        XCTAssertEqual(defaults.string(forKey: "tts_engine"), "SYSTEM")
        XCTAssertTrue(defaults.bool(forKey: "use_system_tts"))
        XCTAssertEqual(model.lastFailureKind, .persistence)
    }

    func testSaveAndClearGoogleApiKeyRefreshesStatusAndSelectsGoogleEngine() async throws {
        let service = FakeSpeechSettingsService()
        service.saveGoogleHandler = { _ in service.googleConfigured = true }
        service.clearGoogleHandler = { service.googleConfigured = false }
        let model = SpeechSettingsModel(service: service, defaults: defaults)

        try await model.saveGoogleApiKey("secret-key")
        XCTAssertTrue(model.googleConfigured)
        XCTAssertEqual(model.ttsEngine, "GOOGLE_CLOUD")
        XCTAssertFalse(model.useSystemTts)

        try await model.clearGoogleApiKey()
        XCTAssertFalse(model.googleConfigured)
    }

    func testSaveAndClearElevenLabsApiKeyRefreshesStatusAndSelectsElevenLabsEngine() async throws {
        let service = FakeSpeechSettingsService()
        service.saveElevenLabsHandler = { _ in service.elevenLabsConfigured = true }
        service.clearElevenLabsHandler = { service.elevenLabsConfigured = false }
        let model = SpeechSettingsModel(service: service, defaults: defaults)

        try await model.saveElevenLabsApiKey("secret-key")
        XCTAssertTrue(model.elevenLabsConfigured)
        XCTAssertEqual(model.ttsEngine, "ELEVENLABS")
        XCTAssertFalse(model.useSystemTts)

        try await model.clearElevenLabsApiKey()
        XCTAssertFalse(model.elevenLabsConfigured)
    }

    func testProviderRefreshCancellationPreservesExistingConfiguredState() async {
        let service = FakeSpeechSettingsService()
        service.azureConfigured = true
        let model = SpeechSettingsModel(service: service, defaults: defaults)
        await model.refreshAzureConfiguration()
        XCTAssertTrue(model.azureConfigured)

        service.azureError = CancellationError()
        await model.refreshAzureConfiguration()
        XCTAssertTrue(model.azureConfigured)
    }

    func testChooseVoiceUpdatesSelectedVoiceLanguagesAndPrimaryLanguage() async {
        let voice = Shared.Voice(
            id: nil,
            name: "en-US-JennyNeural",
            supportedLanguages: ["en-US", "da-DK"],
            gender: nil,
            primaryLanguage: "en-US",
            createdAt: nil,
            displayName: "Jenny",
            selectedLanguage: "da-DK",
            pitch: nil,
            rate: nil,
            pitchForSSML: nil,
            rateForSSML: nil,
            provider: .azure,
            googleModel: nil,
            providerVoiceName: nil,
            mathMode: false
        )
        let service = FakeSpeechSettingsService()
        service.storedVoice = voice
        let model = SpeechSettingsModel(service: service, defaults: defaults)

        let ok = await model.chooseVoice(voice)

        XCTAssertTrue(ok)
        XCTAssertEqual(model.selectedVoice?.name, "en-US-JennyNeural")
        XCTAssertEqual(model.availableLanguages, ["en-US", "da-DK"])
        XCTAssertEqual(model.primaryLanguage, "da-DK")
        XCTAssertTrue(model.canChangeVoiceLanguage)
        XCTAssertNil(model.lastFailureKind)
    }

    func testChooseVoiceFailureRecordsFailureKindWithoutMutatingSelection() async {
        let voice = Shared.Voice(
            id: nil,
            name: "en-US-JennyNeural",
            supportedLanguages: ["en-US"],
            gender: nil,
            primaryLanguage: "en-US",
            createdAt: nil,
            displayName: "Jenny",
            selectedLanguage: "en-US",
            pitch: nil,
            rate: nil,
            pitchForSSML: nil,
            rateForSSML: nil,
            provider: .azure,
            googleModel: nil,
            providerVoiceName: nil,
            mathMode: false
        )
        let service = FakeSpeechSettingsService()
        service.selectVoiceHandler = { _ in throw FakeSpeechError.storage }
        let model = SpeechSettingsModel(service: service, defaults: defaults)

        let ok = await model.chooseVoice(voice)

        XCTAssertFalse(ok)
        XCTAssertNil(model.selectedVoice)
        XCTAssertEqual(model.lastFailureKind, .persistence)
    }

    func testUpdateLanguageRollsBackOnFailureAndRefreshesVoiceOnSuccess() async {
        let service = FakeSpeechSettingsService()
        let model = SpeechSettingsModel(service: service, defaults: defaults)

        service.updatePrimaryLanguageHandler = { _ in throw FakeSpeechError.storage }
        let failed = await model.updateLanguage("da-DK").value
        XCTAssertFalse(failed)
        XCTAssertEqual(model.primaryLanguage, "en-US")
        XCTAssertEqual(model.lastFailureKind, .persistence)

        service.updatePrimaryLanguageHandler = { _ in }
        service.storedVoice = Shared.Voice(
            id: nil,
            name: "da-DK-ChristelNeural",
            supportedLanguages: ["da-DK", "en-US"],
            gender: nil,
            primaryLanguage: "da-DK",
            createdAt: nil,
            displayName: "Christel",
            selectedLanguage: "da-DK",
            pitch: nil,
            rate: nil,
            pitchForSSML: nil,
            rateForSSML: nil,
            provider: .azure,
            googleModel: nil,
            providerVoiceName: nil,
            mathMode: false
        )
        let succeeded = await model.updateLanguage("da-DK").value
        XCTAssertTrue(succeeded)
        XCTAssertEqual(model.primaryLanguage, "da-DK")
        XCTAssertEqual(model.selectedVoice?.name, "da-DK-ChristelNeural")
    }

    func testUpdateSecondaryLanguageInvokesCallbackOnSuccessAndRollsBackOnFailure() async {
        let service = FakeSpeechSettingsService()
        let model = SpeechSettingsModel(service: service, defaults: defaults)
        var callbackCount = 0

        let ok = await model.updateSecondaryLanguage("de-DE") {
            callbackCount += 1
        }.value
        XCTAssertTrue(ok)
        XCTAssertEqual(model.secondaryLanguage, "de-DE")
        XCTAssertEqual(callbackCount, 1)

        service.updateSecondaryLanguageHandler = { _ in throw FakeSpeechError.storage }
        let failed = await model.updateSecondaryLanguage("fr-FR") {
            callbackCount += 1
        }.value
        XCTAssertFalse(failed)
        XCTAssertEqual(model.secondaryLanguage, "de-DE")
        XCTAssertEqual(callbackCount, 1)
    }

    func testCancelPendingTasksCancelsInFlightEngineUpdateWithoutError() async {
        let service = FakeSpeechSettingsService()
        let started = expectation(description: "engine update started")
        service.updateTtsEngineHandler = { _ in
            started.fulfill()
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        let model = SpeechSettingsModel(service: service, defaults: defaults)

        let task = model.setTtsEngine("GOOGLE_CLOUD")
        await fulfillment(of: [started], timeout: 2)
        model.cancelPendingTasks()
        let ok = await task.value

        XCTAssertFalse(ok)
        XCTAssertNil(model.lastFailureKind)
    }
}

private enum FakeSpeechError: Error {
    case storage
}

private final class FakeSpeechSettingsService: SpeechSettingsService {
    var azureConfigured = false
    var googleConfigured = false
    var elevenLabsConfigured = false
    var azureError: Error? = nil
    var storedVoice: Shared.Voice? = nil
    var languagePrefs = LanguagePreferencesSnapshot(primaryLanguage: "en-US", secondaryLanguage: "en-US")

    var saveGoogleHandler: (String) async throws -> Void = { _ in }
    var clearGoogleHandler: () async throws -> Void = {}
    var saveElevenLabsHandler: (String) async throws -> Void = { _ in }
    var clearElevenLabsHandler: () async throws -> Void = {}
    var selectVoiceHandler: (Shared.Voice) async throws -> Void = { _ in }
    var updatePrimaryLanguageHandler: (String) async throws -> Void = { _ in }
    var updateTtsEngineHandler: (String) async throws -> Void = { _ in }
    var updateSecondaryLanguageHandler: (String) async throws -> Void = { _ in }

    func isAzureConfigured() async throws -> Bool {
        if let azureError { throw azureError }
        return azureConfigured
    }

    func isGoogleConfigured() async throws -> Bool { googleConfigured }
    func isElevenLabsConfigured() async throws -> Bool { elevenLabsConfigured }
    func saveGoogleApiKey(_ apiKey: String) async throws { try await saveGoogleHandler(apiKey) }
    func clearGoogleApiKey() async throws { try await clearGoogleHandler() }
    func saveElevenLabsApiKey(_ apiKey: String) async throws { try await saveElevenLabsHandler(apiKey) }
    func clearElevenLabsApiKey() async throws { try await clearElevenLabsHandler() }
    func selectedVoice() async throws -> Shared.Voice? { storedVoice }
    func selectVoiceAndMaybeUpdatePrimary(_ voice: Shared.Voice) async throws { try await selectVoiceHandler(voice) }
    func updateSelectedVoiceLanguage(_ lang: String) async throws { try await updatePrimaryLanguageHandler(lang) }
    func updateTtsEngineNamed(_ engine: String) async throws { try await updateTtsEngineHandler(engine) }
    func languagePreferences() async throws -> LanguagePreferencesSnapshot { languagePrefs }
    func updateSecondaryLanguage(_ lang: String) async throws { try await updateSecondaryLanguageHandler(lang) }
}
