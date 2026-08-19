import AppKit
import ApplicationServices
import AVFoundation
import Carbon
import NaturalLanguage
import Security
import SwiftUI

@main
struct FlowApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra("Flow", systemImage: "text.bubble") {
            FlowMenu(model: appDelegate.model)
        }
        .menuBarExtraStyle(.window)
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = FlowModel()
    private var hotKey: GlobalHotKey?
    private var popup: PlaybackPopupController?
    private var settingsWindow: SettingsWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        popup = PlaybackPopupController(model: model)
        settingsWindow = SettingsWindowController(model: model)
        model.onPopupVisibilityChanged = { [weak self] isVisible in
            guard let self else { return }
            if isVisible {
                self.popup?.show()
            } else {
                self.popup?.hide()
            }
        }
        model.onHotKeyChanged = { [weak self] preset in
            self?.installHotKey(preset)
        }
        model.onSettingsRequested = { [weak self] in
            self?.settingsWindow?.show()
        }
        installHotKey(model.settings.hotKey)
    }

    private func installHotKey(_ preset: HotKeyPreset) {
        hotKey?.invalidate()
        hotKey = GlobalHotKey(preset: preset) { [weak model] in
            Task { @MainActor in
                model?.readSelectionFromHotKey()
            }
        }
        do {
            try hotKey?.register()
            model.hotKeyError = nil
        } catch {
            model.hotKeyError = "\(preset.title) is already in use. Choose another Flow shortcut."
        }
    }
}

@MainActor
final class FlowModel: ObservableObject {
    enum PlaybackState: Equatable {
        case hidden
        case preparing
        case playing
        case paused
        case languageCheck
        case finished
        case message(String)
    }

    @Published private(set) var state: PlaybackState = .hidden
    @Published private(set) var selectedText = ""
    @Published private(set) var accessibilityTrusted: Bool
    @Published private(set) var azureEndpoint: String?
    @Published private(set) var azureVoices: [AzureVoiceCatalog.Voice] = []
    @Published private(set) var azureVoiceLoadError: String?
    @Published private(set) var languagePlan: LanguageFlow.Plan?
    @Published private(set) var pendingLanguagePlan: LanguageFlow.Plan?
    @Published var settings: FlowSettings {
        didSet {
            saveSettings()
            if oldValue.hotKey != settings.hotKey {
                onHotKeyChanged?(settings.hotKey)
            }
        }
    }
    @Published var hotKeyError: String?

    var onPopupVisibilityChanged: ((Bool) -> Void)?
    var onHotKeyChanged: ((HotKeyPreset) -> Void)?
    var onSettingsRequested: (() -> Void)?

    private let systemSpeech = SystemSpeechEngine()
    private let azureSpeech = AzureSpeechEngine()
    private var activeSpeech: FlowSpeechEngine?
    private var dismissTask: Task<Void, Never>?

    init() {
        var loadedSettings = FlowSettings.load()
        loadedSettings.ensureExplicitSystemVoices()
        settings = loadedSettings
        accessibilityTrusted = AccessibilitySelectionReader.isTrusted
        azureEndpoint = AzureCredentialsStore.load()?.endpoint
        let finished: () -> Void = { [weak self] in
            Task { @MainActor in
                self?.finishedReading()
            }
        }
        systemSpeech.onFinished = finished
        azureSpeech.onFinished = finished
        azureSpeech.onFailure = { [weak self] message in
            Task { @MainActor in self?.showMessage(message) }
        }
        saveSettings()
        refreshAzureVoices()
    }

    func readSelectionFromMenu() {
        readSelection()
    }

    func readSelectionFromHotKey() {
        readSelection()
    }

    func promptForAccessibilityPermission() {
        AccessibilitySelectionReader.promptForPermission()
        refreshAccessibilityPermission()
    }

    func refreshAccessibilityPermission() {
        accessibilityTrusted = AccessibilitySelectionReader.isTrusted
    }

    func openSettings() {
        onSettingsRequested?()
    }

    func playTestVoice() {
        dismissTask?.cancel()
        selectedText = "Flow is ready to read selected text."
        let plan = LanguageFlow.singleSentence(selectedText, settings: settings)
        guard let speech = selectedSpeechEngine() else { return }
        activeSpeech?.stop()
        activeSpeech = speech
        languagePlan = plan
        state = .preparing
        onPopupVisibilityChanged?(true)
        speech.read(plan, settings: settings)
        state = .playing
    }

    func pauseOrResume() {
        switch state {
        case .playing:
            activeSpeech?.pause()
            state = .paused
        case .paused:
            activeSpeech?.resume()
            state = .playing
        default:
            break
        }
    }

    func stop() {
        dismissTask?.cancel()
        activeSpeech?.stop()
        selectedText = ""
        languagePlan = nil
        pendingLanguagePlan = nil
        state = .hidden
        onPopupVisibilityChanged?(false)
    }

    private func readSelection() {
        dismissTask?.cancel()
        switch AccessibilitySelectionReader.readFocusedSelection() {
        case .failure(.permissionRequired):
            showMessage("Flow needs Accessibility permission to read selected text.")
        case .failure(.noSelectedText):
            showMessage("Select some text, then press \(settings.hotKey.title).")
        case .failure(.unavailable):
            showMessage("This application does not expose its selected text to macOS.")
        case .success(let text):
            let normalized = Self.normalized(text)
            if normalized.isEmpty {
                showMessage("Select some text, then press \(settings.hotKey.title).")
                return
            }
            if normalized.count > FlowSettings.maximumSelectionCharacters {
                showMessage("This selection is longer than Flow's 10-minute reading limit.")
                return
            }
            if normalized == Self.normalized(selectedText), settings.sameSelectionAction == .pauseResume,
               state == .playing || state == .paused {
                pauseOrResume()
                return
            }
            let plan = LanguageFlow.plan(text: text, settings: settings)
            if plan.needsLanguageCheck {
                selectedText = text
                pendingLanguagePlan = plan
                state = .languageCheck
                onPopupVisibilityChanged?(true)
                return
            }
            startReading(text: text, plan: plan)
        }
    }

    func chooseLanguageRoute(_ routeID: UUID, for sentenceID: UUID) {
        guard var plan = pendingLanguagePlan,
              let route = settings.allLanguageRoutes.first(where: { $0.id == routeID }),
              let index = plan.sentences.firstIndex(where: { $0.id == sentenceID }) else { return }
        plan.sentences[index].route = route
        plan.sentences[index].needsReview = false
        pendingLanguagePlan = plan
    }

    func chooseLanguageRoute(_ routeID: UUID, forAllDetectedLanguage languageTag: String) {
        guard var plan = pendingLanguagePlan,
              let route = settings.allLanguageRoutes.first(where: { $0.id == routeID }) else { return }
        for index in plan.sentences.indices where plan.sentences[index].detectedLanguageTag == languageTag {
            plan.sentences[index].route = route
            plan.sentences[index].needsReview = false
        }
        pendingLanguagePlan = plan
    }

    func enableDetectedLanguage(for sentenceID: UUID) {
        guard let sentence = pendingLanguagePlan?.sentences.first(where: { $0.id == sentenceID }),
              let languageTag = sentence.detectedLanguageTag,
              !settings.languageRoutes.contains(where: { $0.languageTag == languageTag }) else { return }
        settings.languageRoutes.append(.init(
            languageTag: languageTag,
            systemVoiceIdentifier: SystemSpeechEngine.defaultVoice(for: languageTag)?.identifier,
            azureVoiceName: settings.azureVoiceName,
            azureSpeechRate: settings.azureSpeechRate,
        ))
        openSettings()
    }

    func confirmLanguageCheck() {
        guard let plan = pendingLanguagePlan else { return }
        pendingLanguagePlan = nil
        startReading(text: selectedText, plan: plan)
    }

    private func startReading(text: String, plan: LanguageFlow.Plan) {
        guard let speech = selectedSpeechEngine() else { return }
        activeSpeech?.stop()
        activeSpeech = speech
        selectedText = text
        languagePlan = plan
        state = .preparing
        onPopupVisibilityChanged?(true)
        speech.read(plan, settings: settings)
        state = .playing
    }

    private func showMessage(_ message: String) {
        selectedText = ""
        languagePlan = nil
        pendingLanguagePlan = nil
        state = .message(message)
        onPopupVisibilityChanged?(true)
        dismissAfterDelay()
    }

    private func finishedReading() {
        guard state == .playing || state == .paused else { return }
        state = .finished
        dismissAfterDelay()
    }

    private func dismissAfterDelay() {
        let delay = settings.popupDismissSeconds
        dismissTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            self?.state = .hidden
            self?.selectedText = ""
            self?.onPopupVisibilityChanged?(false)
        }
    }

    private func saveSettings() {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        UserDefaults.standard.set(data, forKey: FlowSettings.storageKey)
    }

    func saveAzureConfiguration(endpoint: String, subscriptionKey: String) throws {
        let credentials = try AzureSpeechCredentials(endpoint: endpoint, subscriptionKey: subscriptionKey)
        try AzureCredentialsStore.save(credentials)
        azureEndpoint = credentials.endpoint
        refreshAzureVoices()
    }

    func clearAzureConfiguration() {
        AzureCredentialsStore.clear()
        azureEndpoint = nil
        azureVoices = []
        azureVoiceLoadError = nil
        if settings.speechSource == .azure { settings.speechSource = .system }
    }

    func refreshAzureVoices() {
        guard let credentials = AzureCredentialsStore.load() else { return }
        azureVoiceLoadError = nil
        Task { [weak self] in
            do {
                let voices = try await AzureVoiceCatalog.list(credentials: credentials)
                guard !Task.isCancelled else { return }
                self?.azureVoices = voices
            } catch {
                guard !Task.isCancelled else { return }
                self?.azureVoiceLoadError = "Flow could not load Azure voices. Check the endpoint and key."
            }
        }
    }

    private func selectedSpeechEngine() -> FlowSpeechEngine? {
        switch settings.speechSource {
        case .system:
            return systemSpeech
        case .azure:
            guard azureEndpoint != nil else {
                showMessage("Set up Azure Speech before choosing Azure voice.")
                return nil
            }
            return azureSpeech
        }
    }

    private static func normalized(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}

private enum AccessibilitySelectionError: Error {
    case permissionRequired
    case noSelectedText
    case unavailable
}

private enum AccessibilitySelectionReader {
    static var isTrusted: Bool { AXIsProcessTrusted() }

    static func promptForPermission() {
        let prompt = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        AXIsProcessTrustedWithOptions([prompt: true] as CFDictionary)
    }

    static func readFocusedSelection() -> Result<String, AccessibilitySelectionError> {
        guard isTrusted else { return .failure(.permissionRequired) }

        let system = AXUIElementCreateSystemWide()
        var focusedValue: CFTypeRef?
        guard AXUIElementCopyAttributeValue(system, kAXFocusedUIElementAttribute as CFString, &focusedValue) == .success,
              let focusedValue,
              CFGetTypeID(focusedValue) == AXUIElementGetTypeID() else {
            return .failure(.unavailable)
        }

        let focused = unsafeBitCast(focusedValue, to: AXUIElement.self)
        var selectedValue: CFTypeRef?
        let result = AXUIElementCopyAttributeValue(
            focused,
            kAXSelectedTextAttribute as CFString,
            &selectedValue,
        )
        guard result == .success else { return .failure(.unavailable) }
        guard let text = selectedValue as? String else { return .failure(.noSelectedText) }
        return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? .failure(.noSelectedText)
            : .success(text)
    }
}

private protocol FlowSpeechEngine: AnyObject {
    func read(_ plan: LanguageFlow.Plan, settings: FlowSettings)
    func pause()
    func resume()
    func stop()
}

final class SystemSpeechEngine: NSObject, AVSpeechSynthesizerDelegate, FlowSpeechEngine {
    struct Voice: Identifiable, Hashable {
        let id: String
        let name: String
        let language: String
    }

    var onFinished: (() -> Void)?
    private let synthesizer = AVSpeechSynthesizer()
    private var queuedUtterances = 0

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    static var voices: [Voice] {
        AVSpeechSynthesisVoice.speechVoices()
            .filter { !$0.identifier.contains(".siri.") }
            .map { voice in
                let localeName = Locale.current.localizedString(forIdentifier: voice.language) ?? voice.language
                return Voice(id: voice.identifier, name: "\(voice.name) — \(localeName)", language: voice.language)
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    static func defaultVoice(for languageTag: String) -> AVSpeechSynthesisVoice? {
        if let voice = AVSpeechSynthesisVoice(language: languageTag), !voice.identifier.contains(".siri.") {
            return voice
        }
        let base = languageTag.split(separator: "-").first?.lowercased()
        return AVSpeechSynthesisVoice.speechVoices().first {
            !$0.identifier.contains(".siri.") &&
                $0.language.split(separator: "-").first?.lowercased() == base
        }
    }

    func read(_ plan: LanguageFlow.Plan, settings: FlowSettings) {
        synthesizer.stopSpeaking(at: .immediate)
        queuedUtterances = plan.sentences.count
        for sentence in plan.sentences {
            let utterance = AVSpeechUtterance(string: sentence.text)
            utterance.voice = sentence.route.systemVoiceIdentifier.flatMap(AVSpeechSynthesisVoice.init(identifier:))
                ?? SystemSpeechEngine.defaultVoice(for: sentence.route.languageTag)
            utterance.rate = sentence.route.systemSpeechRate
            synthesizer.speak(utterance)
        }
    }

    func pause() {
        synthesizer.pauseSpeaking(at: .immediate)
    }

    func resume() {
        synthesizer.continueSpeaking()
    }

    func stop() {
        queuedUtterances = 0
        synthesizer.stopSpeaking(at: .immediate)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        queuedUtterances -= 1
        if queuedUtterances == 0 { onFinished?() }
    }
}

enum LanguageFlow {
    static let uncertainConfidence = 0.75
    static let uncertainLead = 0.15

    struct Sentence: Identifiable {
        let id = UUID()
        let text: String
        let detectedLanguageTag: String?
        var route: FlowSettings.LanguageRoute
        var needsReview: Bool
        let detectedButUnconfigured: Bool
    }

    struct Plan {
        var sentences: [Sentence]
        var needsLanguageCheck: Bool { sentences.contains(where: \.needsReview) }
    }

    static func singleSentence(_ text: String, settings: FlowSettings) -> Plan {
        Plan(sentences: [Sentence(
            text: text,
            detectedLanguageTag: settings.defaultLanguageTag,
            route: settings.defaultLanguageRoute,
            needsReview: false,
            detectedButUnconfigured: false,
        )])
    }

    static func plan(text: String, settings: FlowSettings) -> Plan {
        let tokenizer = NLTokenizer(unit: .sentence)
        tokenizer.string = text
        var sentences: [Sentence] = []
        tokenizer.enumerateTokens(in: text.startIndex..<text.endIndex) { range, _ in
            let sentence = String(text[range]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !sentence.isEmpty else { return true }
            let detection = detect(sentence)
            // Detect from the first reading. A detected, unconfigured language must
            // open Language check so the person can enable it; otherwise the first
            // added route can never be discovered.
            let switchingIsActive = settings.languageSwitchingEnabled
            let route = switchingIsActive
                ? detection.tag.flatMap { settings.languageRoute(for: $0) }
                : nil
            let configured = route != nil
            let uncertain = detection.confidence < uncertainConfidence || detection.lead < uncertainLead
            let shouldCheck = switchingIsActive && (uncertain || !configured)
            sentences.append(Sentence(
                text: sentence,
                detectedLanguageTag: detection.tag,
                route: route ?? settings.defaultLanguageRoute,
                needsReview: shouldCheck,
                detectedButUnconfigured: detection.tag != nil && !configured,
            ))
            return true
        }
        return Plan(sentences: sentences.isEmpty ? singleSentence(text, settings: settings).sentences : sentences)
    }

    private static func detect(_ text: String) -> (tag: String?, confidence: Double, lead: Double) {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        let hypotheses = recognizer.languageHypotheses(withMaximum: 2)
            .sorted { $0.value > $1.value }
        guard let first = hypotheses.first else { return (nil, 0, 0) }
        let second = hypotheses.dropFirst().first?.value ?? 0
        return (FlowLanguageOption.defaultTag(for: first.key.rawValue), first.value, first.value - second)
    }
}

struct AzureSpeechCredentials: Codable, Equatable {
    let endpoint: String
    let subscriptionKey: String

    init(endpoint rawEndpoint: String, subscriptionKey rawKey: String) throws {
        guard let endpoint = AzureSpeechEndpoint.normalize(rawEndpoint) else {
            throw AzureConfigurationError.invalidEndpoint
        }
        let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, !key.contains(where: \.isWhitespace) else {
            throw AzureConfigurationError.invalidKey
        }
        self.endpoint = endpoint
        subscriptionKey = key
    }
}

private enum AzureConfigurationError: LocalizedError {
    case invalidEndpoint
    case invalidKey
    case invalidVoice
    case keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .invalidEndpoint: "Enter an Azure Speech region or HTTPS endpoint."
        case .invalidKey: "Enter a valid Azure Speech subscription key."
        case .invalidVoice: "Enter a valid Azure neural voice name."
        case .keychain: "Flow could not save the Azure credential in your Keychain."
        }
    }
}

private enum AzureSpeechEndpoint {
    static func normalize(_ rawValue: String) -> String? {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !value.isEmpty, !value.contains(where: \.isWhitespace) else { return nil }
        if !value.contains(".") && !value.contains("://") {
            return value.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" }
                ? "https://\(value).tts.speech.microsoft.com"
                : nil
        }
        let candidate = value.contains("://") ? value : "https://\(value)"
        guard let components = URLComponents(string: candidate),
              components.scheme == "https",
              let host = components.host,
              components.user == nil,
              components.password == nil,
              components.port == nil || components.port == 443,
              components.path.isEmpty || components.path == "/",
              components.query == nil,
              components.fragment == nil else { return nil }
        let supported = host.hasSuffix(".tts.speech.microsoft.com") ||
            host.hasSuffix(".tts.speech.azure.com") ||
            host.hasSuffix(".cognitiveservices.azure.com")
        guard supported, host.split(separator: ".").count >= 4 else { return nil }
        return "https://\(host)"
    }
}

enum AzureVoiceCatalog {
    struct Voice: Decodable, Identifiable, Hashable {
        let shortName: String
        let locale: String
        let secondaryLocales: [String]

        var id: String { shortName }
        var isMultilingual: Bool {
            shortName.localizedCaseInsensitiveContains("multilingual") || !secondaryLocales.isEmpty
        }

        private enum CodingKeys: String, CodingKey {
            case shortName = "ShortName"
            case locale = "Locale"
            case secondaryLocales = "SecondaryLocaleList"
        }

        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            shortName = try values.decode(String.self, forKey: .shortName)
            locale = try values.decode(String.self, forKey: .locale)
            secondaryLocales = try values.decodeIfPresent([String].self, forKey: .secondaryLocales) ?? []
        }

        func supports(languageTag: String) -> Bool {
            let base = languageTag.split(separator: "-").first?.lowercased()
            return ([locale] + secondaryLocales).contains {
                $0.split(separator: "-").first?.lowercased() == base
            }
        }
    }

    static func list(credentials: AzureSpeechCredentials) async throws -> [Voice] {
        let suffix = credentials.endpoint.contains(".cognitiveservices.azure.com")
            ? "/tts/cognitiveservices/voices/list"
            : "/cognitiveservices/voices/list"
        var request = URLRequest(url: URL(string: credentials.endpoint + suffix)!)
        request.setValue(credentials.subscriptionKey, forHTTPHeaderField: "Ocp-Apim-Subscription-Key")
        request.setValue("Flow", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode([Voice].self, from: data)
            .sorted { $0.shortName.localizedCaseInsensitiveCompare($1.shortName) == .orderedAscending }
    }
}

private enum AzureCredentialsStore {
    private static let service = "io.github.jdreioe.flow.azure-speech"
    private static let account = "byok"

    static func load() -> AzureSpeechCredentials? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(AzureSpeechCredentials.self, from: data)
    }

    static func save(_ credentials: AzureSpeechCredentials) throws {
        let data = try JSONEncoder().encode(credentials)
        clear()
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw AzureConfigurationError.keychain(status) }
    }

    static func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

private enum AzurePortalURLs {
    // The same F0 ARM template Wingmate offers to people who need a free Speech resource.
    static let createSpeechResource = URL(string: "https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjdreioe%2Fwingmate%2Fmain%2Finfra%2Fazure-user-f0%2Fazuredeploy.json")!
    static let speechResources = URL(string: "https://portal.azure.com/#view/HubsExtension/BrowseResource/resourceType/Microsoft.CognitiveServices%2Faccounts")!
}

final class AzureSpeechEngine: NSObject, AVAudioPlayerDelegate, FlowSpeechEngine {
    var onFinished: (() -> Void)?
    var onFailure: ((String) -> Void)?
    private var player: AVAudioPlayer?
    private var synthesisTask: Task<Void, Never>?

    func read(_ plan: LanguageFlow.Plan, settings: FlowSettings) {
        stop()
        guard let credentials = AzureCredentialsStore.load() else {
            onFailure?("Set up Azure Speech before choosing Azure voice.")
            return
        }
        synthesisTask = Task { [weak self] in
            do {
                let audio = try await Self.synthesize(plan: plan, settings: settings, credentials: credentials)
                guard !Task.isCancelled else { return }
                await MainActor.run { self?.play(audio) }
            } catch is CancellationError {
            } catch {
                await MainActor.run { self?.onFailure?("Azure could not synthesize this selection. Check the endpoint, key, and voice.") }
            }
        }
    }

    func pause() { player?.pause() }
    func resume() { player?.play() }
    func stop() {
        synthesisTask?.cancel()
        synthesisTask = nil
        player?.stop()
        player = nil
    }

    private func play(_ data: Data) {
        do {
            let player = try AVAudioPlayer(data: data)
            player.delegate = self
            self.player = player
            guard player.play() else { throw CocoaError(.fileReadCorruptFile) }
        } catch {
            onFailure?("Azure returned audio that Flow could not play.")
        }
    }

    private static func synthesize(plan: LanguageFlow.Plan, settings: FlowSettings, credentials: AzureSpeechCredentials) async throws -> Data {
        let body = try plan.sentences.map { sentence in
            let voiceName = settings.azureVoiceMode == .multilingual
                ? settings.azureVoiceName
                : (sentence.route.azureVoiceName ?? settings.azureVoiceName)
            let voice = voiceName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !voice.isEmpty, voice.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else {
                throw AzureConfigurationError.invalidVoice
            }
            // A Language check choice changes the route. Azure must receive
            // that chosen language rather than the detector's original guess.
            let languageTag = sentence.route.languageTag
            let escaped = sentence.text
                .replacingOccurrences(of: "&", with: "&amp;")
                .replacingOccurrences(of: "<", with: "&lt;")
                .replacingOccurrences(of: ">", with: "&gt;")
            let rate = settings.azureVoiceMode == .multilingual
                ? settings.azureSpeechRate
                : sentence.route.azureSpeechRate
            return "<voice name=\"\(voice)\"><lang xml:lang=\"\(languageTag)\"><prosody rate=\"\(azureRate(rate))%\">\(escaped)</prosody></lang></voice>"
        }.joined()
        let ssml = "<speak version=\"1.0\" xml:lang=\"\(settings.defaultLanguageTag)\">\(body)</speak>"
        var request = URLRequest(url: URL(string: credentials.endpoint + "/cognitiveservices/v1")!)
        request.httpMethod = "POST"
        request.setValue(credentials.subscriptionKey, forHTTPHeaderField: "Ocp-Apim-Subscription-Key")
        request.setValue("application/ssml+xml", forHTTPHeaderField: "Content-Type")
        request.setValue("audio-24khz-160kbitrate-mono-mp3", forHTTPHeaderField: "X-Microsoft-OutputFormat")
        request.setValue("Flow", forHTTPHeaderField: "User-Agent")
        request.httpBody = Data(ssml.utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode), !data.isEmpty else {
            throw URLError(.badServerResponse)
        }
        return data
    }

    private static func azureRate(_ rate: Float) -> Int {
        let minimum = AVSpeechUtteranceMinimumSpeechRate
        let maximum = AVSpeechUtteranceMaximumSpeechRate
        let position = (rate - minimum) / (maximum - minimum)
        return Int((position * 100 - 50).rounded())
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        if flag { onFinished?() } else { onFailure?("Azure playback ended unexpectedly.") }
    }
}

struct FlowSettings: Codable, Equatable {
    static let storageKey = "io.github.jdreioe.flow.settings"
    static let maximumSelectionCharacters = 45_000

    enum SameSelectionAction: String, Codable, CaseIterable, Identifiable {
        case pauseResume
        case restart

        var id: String { rawValue }
        var title: String {
            switch self {
            case .pauseResume: "Pause or resume"
            case .restart: "Restart reading"
            }
        }
    }

    enum SpeechSource: String, Codable, CaseIterable, Identifiable {
        case system
        case azure

        var id: String { rawValue }
        var title: String {
            switch self {
            case .system: "System voice"
            case .azure: "Azure voice"
            }
        }
    }

    struct LanguageRoute: Codable, Equatable, Identifiable {
        let id: UUID
        var languageTag: String
        var systemVoiceIdentifier: String?
        var systemSpeechRate: Float
        var azureVoiceName: String?
        var azureSpeechRate: Float

        init(
            id: UUID = UUID(),
            languageTag: String,
            systemVoiceIdentifier: String? = nil,
            systemSpeechRate: Float = AVSpeechUtteranceDefaultSpeechRate,
            azureVoiceName: String? = nil,
            azureSpeechRate: Float = AVSpeechUtteranceDefaultSpeechRate,
        ) {
            self.id = id
            self.languageTag = languageTag
            self.systemVoiceIdentifier = systemVoiceIdentifier
            self.systemSpeechRate = systemSpeechRate
            self.azureVoiceName = azureVoiceName
            self.azureSpeechRate = azureSpeechRate
        }

        var displayName: String {
            Locale.current.localizedString(forIdentifier: languageTag) ?? languageTag
        }
    }

    enum AzureVoiceMode: String, Codable, CaseIterable, Identifiable {
        case multilingual
        case perLanguage

        var id: String { rawValue }
        var title: String { self == .multilingual ? "One multilingual voice" : "A voice per language" }
    }

    var speechSource: SpeechSource = .system
    var hotKey: HotKeyPreset = .optionCommandR
    var voiceIdentifier: String?
    var speechRate: Float = AVSpeechUtteranceDefaultSpeechRate
    var popupDismissSeconds: Double = 8
    var sameSelectionAction: SameSelectionAction = .pauseResume
    var azureVoiceName = "en-US-AvaMultilingualNeural"
    var azureSpeechRate: Float = AVSpeechUtteranceDefaultSpeechRate
    var azureVoiceMode: AzureVoiceMode = .multilingual
    var defaultLanguageTag = "en-US"
    var languageSwitchingEnabled = true
    var languageRoutes: [LanguageRoute] = []

    init() {}

    private enum CodingKeys: String, CodingKey {
        case speechSource, hotKey, voiceIdentifier, speechRate, popupDismissSeconds, sameSelectionAction
        case azureVoiceName, azureSpeechRate, azureVoiceMode
        case defaultLanguageTag, languageSwitchingEnabled, languageRoutes
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        // Older experimental settings stored a removed Siri source. Treat it
        // as the normal on-device source rather than discarding all settings.
        speechSource = (try? values.decodeIfPresent(SpeechSource.self, forKey: .speechSource)) ?? .system
        hotKey = try values.decodeIfPresent(HotKeyPreset.self, forKey: .hotKey) ?? .optionCommandR
        voiceIdentifier = try values.decodeIfPresent(String.self, forKey: .voiceIdentifier)
        speechRate = try values.decodeIfPresent(Float.self, forKey: .speechRate) ?? AVSpeechUtteranceDefaultSpeechRate
        popupDismissSeconds = try values.decodeIfPresent(Double.self, forKey: .popupDismissSeconds) ?? 8
        sameSelectionAction = try values.decodeIfPresent(SameSelectionAction.self, forKey: .sameSelectionAction) ?? .pauseResume
        azureVoiceName = try values.decodeIfPresent(String.self, forKey: .azureVoiceName) ?? "en-US-AvaMultilingualNeural"
        azureSpeechRate = try values.decodeIfPresent(Float.self, forKey: .azureSpeechRate) ?? AVSpeechUtteranceDefaultSpeechRate
        azureVoiceMode = try values.decodeIfPresent(AzureVoiceMode.self, forKey: .azureVoiceMode) ?? .multilingual
        defaultLanguageTag = try values.decodeIfPresent(String.self, forKey: .defaultLanguageTag) ?? "en-US"
        languageSwitchingEnabled = try values.decodeIfPresent(Bool.self, forKey: .languageSwitchingEnabled) ?? true
        languageRoutes = try values.decodeIfPresent([LanguageRoute].self, forKey: .languageRoutes) ?? []
    }

    var defaultLanguageRoute: LanguageRoute {
        LanguageRoute(
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
            languageTag: defaultLanguageTag,
            systemVoiceIdentifier: voiceIdentifier,
            systemSpeechRate: speechRate,
            azureVoiceName: azureVoiceName,
            azureSpeechRate: azureSpeechRate,
        )
    }

    var allLanguageRoutes: [LanguageRoute] { [defaultLanguageRoute] + languageRoutes }

    func languageRoute(for detectedTag: String) -> LanguageRoute? {
        let base = detectedTag.split(separator: "-").first?.lowercased()
        return allLanguageRoutes.first { route in
            route.languageTag.lowercased() == detectedTag.lowercased() ||
                route.languageTag.split(separator: "-").first?.lowercased() == base
        }
    }

    mutating func ensureExplicitSystemVoices() {
        if voiceIdentifier == nil || voiceIdentifier?.contains(".siri.") == true {
            voiceIdentifier = SystemSpeechEngine.defaultVoice(for: defaultLanguageTag)?.identifier
        }
        for index in languageRoutes.indices {
            if languageRoutes[index].systemVoiceIdentifier == nil || languageRoutes[index].systemVoiceIdentifier?.contains(".siri.") == true {
                languageRoutes[index].systemVoiceIdentifier = SystemSpeechEngine.defaultVoice(for: languageRoutes[index].languageTag)?.identifier
            }
        }
    }

    static func load() -> FlowSettings {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let settings = try? JSONDecoder().decode(FlowSettings.self, from: data) else {
            return FlowSettings()
        }
        return settings
    }
}

enum HotKeyPreset: String, Codable, CaseIterable, Identifiable {
    case optionCommandR
    case optionCommandSpace
    case controlOptionR

    var id: String { rawValue }
    var title: String {
        switch self {
        case .optionCommandR: "Option-Command-R"
        case .optionCommandSpace: "Option-Command-Space"
        case .controlOptionR: "Control-Option-R"
        }
    }

    var keyCode: UInt32 {
        switch self {
        case .optionCommandR, .controlOptionR: UInt32(kVK_ANSI_R)
        case .optionCommandSpace: UInt32(kVK_Space)
        }
    }

    var modifiers: UInt32 {
        switch self {
        case .optionCommandR, .optionCommandSpace: UInt32(optionKey | cmdKey)
        case .controlOptionR: UInt32(optionKey | controlKey)
        }
    }
}

final class GlobalHotKey {
    private static let signature: OSType = 0x464C4F57 // FLOW
    private let preset: HotKeyPreset
    private let action: () -> Void
    private var eventHandler: EventHandlerRef?
    private var hotKey: EventHotKeyRef?

    init(preset: HotKeyPreset, action: @escaping () -> Void) {
        self.preset = preset
        self.action = action
    }

    deinit { invalidate() }

    func register() throws {
        var eventType = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let handlerStatus = InstallEventHandler(
            GetApplicationEventTarget(),
            { _, _, userData in
                guard let userData else { return noErr }
                let hotKey = Unmanaged<GlobalHotKey>.fromOpaque(userData).takeUnretainedValue()
                hotKey.action()
                return noErr
            },
            1,
            &eventType,
            Unmanaged.passUnretained(self).toOpaque(),
            &eventHandler,
        )
        guard handlerStatus == noErr else { throw RegistrationError.failed }

        let id = EventHotKeyID(signature: Self.signature, id: 1)
        let registrationStatus = RegisterEventHotKey(
            preset.keyCode,
            preset.modifiers,
            id,
            GetApplicationEventTarget(),
            0,
            &hotKey,
        )
        guard registrationStatus == noErr else {
            invalidate()
            throw RegistrationError.failed
        }
    }

    func invalidate() {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        if let eventHandler { RemoveEventHandler(eventHandler) }
        hotKey = nil
        eventHandler = nil
    }

    private enum RegistrationError: Error { case failed }
}

final class PlaybackPopupController {
    private let panel: NSPanel

    init(model: FlowModel) {
        panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 210),
            styleMask: [.nonactivatingPanel, .fullSizeContentView],
            backing: .buffered,
            defer: false,
        )
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.contentView = NSHostingView(rootView: PlaybackPopupView(model: model))
    }

    func show() {
        let cursor = NSEvent.mouseLocation
        panel.setFrameOrigin(NSPoint(x: cursor.x - 230, y: cursor.y - 240))
        panel.orderFrontRegardless()
    }

    func hide() {
        panel.orderOut(nil)
    }
}

@MainActor
final class SettingsWindowController {
    private let window: NSWindow

    init(model: FlowModel) {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 440),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false,
        )
        window.title = "Flow Settings"
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: FlowSettingsView(model: model))
    }

    func show() {
        NSApplication.shared.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }
}

private struct FlowMenu: View {
    @ObservedObject var model: FlowModel

    var body: some View {
        Button("Read selected text") { model.readSelectionFromMenu() }
        Text(model.settings.hotKey.title)
            .foregroundStyle(.secondary)
        if let error = model.hotKeyError {
            Text(error)
                .foregroundStyle(.red)
        }
        Divider()
        Button("Settings…") { model.openSettings() }
        Button("Quit Flow") { NSApplication.shared.terminate(nil) }
    }
}

private struct PlaybackPopupView: View {
    @ObservedObject var model: FlowModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if model.state == .languageCheck {
                LanguageCheckView(model: model)
            } else {
                HStack {
                    Text(title)
                        .font(.headline)
                    Spacer()
                    Button("Stop", action: model.stop)
                        .keyboardShortcut(.escape, modifiers: [])
                        .accessibilityLabel("Stop reading")
                }
                if case let .message(message) = model.state {
                    Text(message)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    Text(model.selectedText)
                        .lineLimit(4)
                        .textSelection(.enabled)
                        .accessibilityLabel("Selected text being read")
                }
                if model.state == .playing || model.state == .paused {
                    Button(model.state == .paused ? "Resume" : "Pause", action: model.pauseOrResume)
                        .buttonStyle(.borderedProminent)
                        .accessibilityLabel(model.state == .paused ? "Resume reading" : "Pause reading")
                }
            }
        }
        .padding(20)
    }

    private var title: String {
        switch model.state {
        case .preparing: "Preparing playback"
        case .playing: "Reading"
        case .paused: "Paused"
        case .languageCheck: "Language check"
        case .finished: "Finished"
        case .message: "Flow"
        case .hidden: "Flow"
        }
    }
}

private struct LanguageCheckView: View {
    @ObservedObject var model: FlowModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Language check")
                .font(.headline)
            Text("Choose how Flow should read these sentences before playback starts.")
                .font(.caption)
                .foregroundStyle(.secondary)
            ForEach(model.pendingLanguagePlan?.sentences.filter(\.needsReview) ?? []) { sentence in
                VStack(alignment: .leading, spacing: 6) {
                    Text(sentence.text)
                        .lineLimit(2)
                    if sentence.detectedButUnconfigured, let tag = sentence.detectedLanguageTag {
                        Text("Flow detected \(Locale.current.localizedString(forIdentifier: tag) ?? tag), but it is not enabled.")
                            .font(.caption)
                        Button("Enable \(Locale.current.localizedString(forIdentifier: tag) ?? tag) in Settings") {
                            model.enableDetectedLanguage(for: sentence.id)
                        }
                    }
                    Picker("Read as", selection: Binding(
                        get: { sentence.route.id },
                        set: { model.chooseLanguageRoute($0, for: sentence.id) },
                    )) {
                        ForEach(model.settings.allLanguageRoutes) { route in
                            Text(route.displayName).tag(route.id)
                        }
                    }
                    if let tag = sentence.detectedLanguageTag {
                        Button("Use this choice for all \(Locale.current.localizedString(forIdentifier: tag) ?? tag) sentences") {
                            model.chooseLanguageRoute(sentence.route.id, forAllDetectedLanguage: tag)
                        }
                        .font(.caption)
                    }
                }
                .padding(.vertical, 4)
            }
            HStack {
                Button("Cancel", action: model.stop)
                Spacer()
                Button("Start reading", action: model.confirmLanguageCheck)
                    .buttonStyle(.borderedProminent)
            }
        }
    }
}

private struct FlowSettingsView: View {
    @ObservedObject var model: FlowModel
    @State private var languageToAdd = "da-DK"

    private var voices: [SystemSpeechEngine.Voice] { SystemSpeechEngine.voices }

    var body: some View {
        Form {
            Section("Access") {
                Picker("Global hotkey", selection: $model.settings.hotKey) {
                    ForEach(HotKeyPreset.allCases) { preset in
                        Text(preset.title).tag(preset)
                    }
                }
                Button("Allow Accessibility access") {
                    model.promptForAccessibilityPermission()
                }
                HStack {
                    Label(
                        model.accessibilityTrusted ? "Accessibility access allowed" : "Accessibility access not allowed",
                        systemImage: model.accessibilityTrusted ? "checkmark.circle.fill" : "exclamationmark.triangle.fill",
                    )
                    .foregroundStyle(model.accessibilityTrusted ? .green : .orange)
                    Spacer()
                    Button("Refresh") { model.refreshAccessibilityPermission() }
                }
                Text("Flow reads only the selection that macOS accessibility exposes when you trigger it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Language Flow") {
                Toggle("Let Flow switch languages", isOn: $model.settings.languageSwitchingEnabled)
                Text("The default voice reads your default language and is the fallback. Add another language below to give it its own voice.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Picker("Default language", selection: Binding(
                    get: { model.settings.defaultLanguageTag },
                    set: { languageTag in
                        model.settings.defaultLanguageTag = languageTag
                        model.settings.voiceIdentifier = SystemSpeechEngine.defaultVoice(for: languageTag)?.identifier
                    },
                )) {
                    ForEach(FlowLanguageOption.allCases) { language in
                        Text(language.title).tag(language.tag)
                    }
                }
                LanguageRouteEditor(
                    route: Binding(
                        get: { model.settings.defaultLanguageRoute },
                        set: { route in
                            model.settings.voiceIdentifier = route.systemVoiceIdentifier
                            model.settings.speechRate = route.systemSpeechRate
                            model.settings.azureVoiceName = route.azureVoiceName ?? model.settings.azureVoiceName
                            model.settings.azureSpeechRate = route.azureSpeechRate
                        },
                    ),
                    voices: voices,
                    azureVoices: model.azureVoices,
                    showSystemRoute: model.settings.speechSource == .system,
                    showAzureRoute: model.settings.speechSource == .azure && model.settings.azureVoiceMode == .perLanguage,
                    isDefault: true,
                    remove: {},
                )
                ForEach($model.settings.languageRoutes) { $route in
                        LanguageRouteEditor(
                            route: $route,
                            voices: voices,
                            azureVoices: model.azureVoices,
                            showSystemRoute: model.settings.speechSource == .system,
                            showAzureRoute: model.settings.speechSource == .azure && model.settings.azureVoiceMode == .perLanguage,
                            isDefault: false,
                    ) {
                        model.settings.languageRoutes.removeAll { $0.id == route.id }
                    }
                }
                HStack {
                    Picker("Language", selection: $languageToAdd) {
                        ForEach(FlowLanguageOption.allCases.filter { option in
                            option.tag != model.settings.defaultLanguageTag &&
                                !model.settings.languageRoutes.contains(where: { $0.languageTag == option.tag })
                        }) { language in
                            Text(language.title).tag(language.tag)
                        }
                    }
                    Button("Add language") {
                        model.settings.languageRoutes.append(.init(
                            languageTag: languageToAdd,
                            systemVoiceIdentifier: SystemSpeechEngine.defaultVoice(for: languageToAdd)?.identifier,
                            azureVoiceName: model.settings.azureVoiceName,
                            azureSpeechRate: model.settings.azureSpeechRate,
                        ))
                    }
                }
            }
            Section("Azure Speech") {
                AzureConfigurationView(model: model)
            }
            Section("Playback") {
                Button("Play test voice") { model.playTestVoice() }
                Picker("Same selection hotkey", selection: $model.settings.sameSelectionAction) {
                    ForEach(FlowSettings.SameSelectionAction.allCases) { action in
                        Text(action.title).tag(action)
                    }
                }
                Stepper(
                    "Popup dismisses after \(Int(model.settings.popupDismissSeconds)) seconds",
                    value: $model.settings.popupDismissSeconds,
                    in: 3...30,
                )
                Text("Selections longer than about ten minutes are not read.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Privacy") {
                Text("Flow keeps selected text only while the playback popup is visible. System language detection and voices are on-device. Azure receives text only when Azure is selected.")
                    .font(.caption)
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}

private enum FlowLanguageOption: String, CaseIterable, Identifiable {
    case english = "en-US"
    case danish = "da-DK"
    case swedish = "sv-SE"
    case norwegian = "nb-NO"
    case german = "de-DE"
    case french = "fr-FR"
    case spanish = "es-ES"
    case italian = "it-IT"
    case dutch = "nl-NL"
    case portuguese = "pt-PT"

    var id: String { rawValue }
    var tag: String { rawValue }
    var title: String { Locale.current.localizedString(forIdentifier: rawValue) ?? rawValue }

    static func defaultTag(for detectedTag: String) -> String {
        let base = detectedTag.split(separator: "-").first?.lowercased()
        return allCases.first { $0.tag.split(separator: "-").first?.lowercased() == base }?.tag ?? detectedTag
    }
}

private struct LanguageRouteEditor: View {
    @Binding var route: FlowSettings.LanguageRoute
    let voices: [SystemSpeechEngine.Voice]
    let azureVoices: [AzureVoiceCatalog.Voice]
    let showSystemRoute: Bool
    let showAzureRoute: Bool
    let isDefault: Bool
    let remove: () -> Void

    private var matchingVoices: [SystemSpeechEngine.Voice] {
        let base = route.languageTag.split(separator: "-").first?.lowercased()
        return voices.filter { $0.language.split(separator: "-").first?.lowercased() == base }
    }

    private var matchingAzureVoices: [AzureVoiceCatalog.Voice] {
        azureVoices.filter { $0.supports(languageTag: route.languageTag) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(isDefault ? "Default voice" : route.displayName)
                    .font(.headline)
                Spacer()
                if !isDefault {
                    Button("Remove", role: .destructive, action: remove)
                }
            }
            if showSystemRoute {
                Picker("Voice", selection: $route.systemVoiceIdentifier) {
                    ForEach(matchingVoices) { voice in
                        Text(voice.name).tag(String?.some(voice.id))
                    }
                }
                Slider(value: $route.systemSpeechRate, in: AVSpeechUtteranceMinimumSpeechRate...AVSpeechUtteranceMaximumSpeechRate) {
                    Text("Speech rate")
                }
            }
            if showAzureRoute {
                Picker("Azure voice", selection: Binding(
                    get: { route.azureVoiceName ?? "" },
                    set: { route.azureVoiceName = $0 },
                )) {
                    ForEach(matchingAzureVoices) { voice in
                        Text(voice.shortName).tag(voice.shortName)
                    }
                }
                Slider(value: $route.azureSpeechRate, in: AVSpeechUtteranceMinimumSpeechRate...AVSpeechUtteranceMaximumSpeechRate) {
                    Text("Azure speech rate")
                }
                if matchingAzureVoices.isEmpty {
                    Text("No Azure voices support (route.displayName) in this resource's region.")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }
            if showSystemRoute && matchingVoices.isEmpty {
                Text("No installed \(route.displayName) voice was found.")
                    .font(.caption)
                    .foregroundStyle(.orange)
                Link("Open macOS voice downloads", destination: URL(string: "x-apple.systempreferences:com.apple.Accessibility-Settings.extension")!)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct AzureConfigurationView: View {
    @ObservedObject var model: FlowModel
    @State private var endpoint = ""
    @State private var subscriptionKey = ""
    @State private var error: String?

    private var multilingualVoices: [AzureVoiceCatalog.Voice] {
        model.azureVoices.filter(\.isMultilingual)
    }

    var body: some View {
        Picker("Reading source", selection: $model.settings.speechSource) {
            ForEach(FlowSettings.SpeechSource.allCases) { source in
                Text(source.title).tag(source)
            }
        }
        if let configuredEndpoint = model.azureEndpoint {
            Text("Configured for \(configuredEndpoint)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Picker("Azure voice mode", selection: $model.settings.azureVoiceMode) {
                ForEach(FlowSettings.AzureVoiceMode.allCases) { mode in
                    Text(mode.title).tag(mode)
                }
            }
            if model.settings.azureVoiceMode == .multilingual {
                if multilingualVoices.isEmpty {
                    if let message = model.azureVoiceLoadError {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(.orange)
                    } else {
                        Text("Loading Azure voices…")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Picker("Azure voice", selection: $model.settings.azureVoiceName) {
                        ForEach(multilingualVoices) { voice in
                            Text(voice.shortName).tag(voice.shortName)
                        }
                    }
                }
                Slider(value: $model.settings.azureSpeechRate, in: AVSpeechUtteranceMinimumSpeechRate...AVSpeechUtteranceMaximumSpeechRate) {
                    Text("Azure speech rate")
                }
            } else {
                Text("Set each language's Azure voice and rate in Language Flow.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Button("Refresh Azure voices") { model.refreshAzureVoices() }
            Link("View your Azure Speech resources", destination: AzurePortalURLs.speechResources)
            Button("Remove Azure configuration", role: .destructive) {
                model.clearAzureConfiguration()
            }
        } else {
            Text("Azure sends selected text to your Speech resource to synthesize it. The subscription key stays in this Mac's Keychain.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Link("Create a free Azure Speech resource", destination: AzurePortalURLs.createSpeechResource)
            Link("View your Azure Speech resources", destination: AzurePortalURLs.speechResources)
            TextField("Region or HTTPS endpoint", text: $endpoint)
            SecureField("Azure Speech subscription key", text: $subscriptionKey)
            if let error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            Button("Save Azure configuration") {
                do {
                    try model.saveAzureConfiguration(endpoint: endpoint, subscriptionKey: subscriptionKey)
                    subscriptionKey = ""
                    error = nil
                } catch {
                    self.error = error.localizedDescription
                }
            }
            .disabled(endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || subscriptionKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }
}
