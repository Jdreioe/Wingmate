import AppKit
import ApplicationServices
import AVFoundation
import Carbon
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
        case finished
        case message(String)
    }

    @Published private(set) var state: PlaybackState = .hidden
    @Published private(set) var selectedText = ""
    @Published private(set) var accessibilityTrusted: Bool
    @Published private(set) var azureEndpoint: String?
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
        settings = FlowSettings.load()
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
        guard let speech = selectedSpeechEngine() else { return }
        activeSpeech?.stop()
        activeSpeech = speech
        state = .preparing
        onPopupVisibilityChanged?(true)
        speech.read(selectedText, settings: settings)
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
            guard let speech = selectedSpeechEngine() else { return }
            activeSpeech?.stop()
            activeSpeech = speech
            selectedText = text
            state = .preparing
            onPopupVisibilityChanged?(true)
            speech.read(text, settings: settings)
            state = .playing
        }
    }

    private func showMessage(_ message: String) {
        selectedText = ""
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
    }

    func clearAzureConfiguration() {
        AzureCredentialsStore.clear()
        azureEndpoint = nil
        if settings.speechSource == .azure { settings.speechSource = .system }
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
    func read(_ text: String, settings: FlowSettings)
    func pause()
    func resume()
    func stop()
}

final class SystemSpeechEngine: NSObject, AVSpeechSynthesizerDelegate, FlowSpeechEngine {
    struct Voice: Identifiable, Hashable {
        let id: String
        let name: String
    }

    var onFinished: (() -> Void)?
    private let synthesizer = AVSpeechSynthesizer()

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    static var voices: [Voice] {
        AVSpeechSynthesisVoice.speechVoices()
            .map { voice in
                Voice(id: voice.identifier, name: "\(voice.name) (\(voice.language))")
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    func read(_ text: String, settings: FlowSettings) {
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = settings.voiceIdentifier.flatMap(AVSpeechSynthesisVoice.init(identifier:))
        utterance.rate = settings.speechRate
        synthesizer.speak(utterance)
    }

    func pause() {
        synthesizer.pauseSpeaking(at: .immediate)
    }

    func resume() {
        synthesizer.continueSpeaking()
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        onFinished?()
    }
}

private struct AzureSpeechCredentials: Codable, Equatable {
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

final class AzureSpeechEngine: NSObject, AVAudioPlayerDelegate, FlowSpeechEngine {
    var onFinished: (() -> Void)?
    var onFailure: ((String) -> Void)?
    private var player: AVAudioPlayer?
    private var synthesisTask: Task<Void, Never>?

    func read(_ text: String, settings: FlowSettings) {
        stop()
        guard let credentials = AzureCredentialsStore.load() else {
            onFailure?("Set up Azure Speech before choosing Azure voice.")
            return
        }
        synthesisTask = Task { [weak self] in
            do {
                let audio = try await Self.synthesize(text: text, voiceName: settings.azureVoiceName, credentials: credentials)
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

    private static func synthesize(text: String, voiceName: String, credentials: AzureSpeechCredentials) async throws -> Data {
        let voice = voiceName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !voice.isEmpty, voice.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else {
            throw AzureConfigurationError.invalidVoice
        }
        let locale = voice.split(separator: "-").prefix(2).joined(separator: "-")
        let escaped = text
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
        let ssml = "<speak version=\"1.0\" xml:lang=\"\(locale)\"><voice name=\"\(voice)\">\(escaped)</voice></speak>"
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
        var title: String { self == .system ? "System voice" : "Azure voice" }
    }

    var speechSource: SpeechSource = .system
    var hotKey: HotKeyPreset = .optionCommandR
    var voiceIdentifier: String?
    var speechRate: Float = AVSpeechUtteranceDefaultSpeechRate
    var popupDismissSeconds: Double = 8
    var sameSelectionAction: SameSelectionAction = .pauseResume
    var azureVoiceName = "en-US-AvaMultilingualNeural"

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
        .padding(20)
    }

    private var title: String {
        switch model.state {
        case .preparing: "Preparing playback"
        case .playing: "Reading"
        case .paused: "Paused"
        case .finished: "Finished"
        case .message: "Flow"
        case .hidden: "Flow"
        }
    }
}

private struct FlowSettingsView: View {
    @ObservedObject var model: FlowModel

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
            Section("Speech") {
                Picker("Voice source", selection: $model.settings.speechSource) {
                    ForEach(FlowSettings.SpeechSource.allCases) { source in
                        Text(source.title).tag(source)
                    }
                }
                Picker("Voice", selection: $model.settings.voiceIdentifier) {
                    Text("System default").tag(String?.none)
                    ForEach(voices) { voice in
                        Text(voice.name).tag(String?.some(voice.id))
                    }
                }
                Slider(value: Binding(
                    get: { model.settings.speechRate },
                    set: { model.settings.speechRate = $0 },
                ), in: AVSpeechUtteranceMinimumSpeechRate...AVSpeechUtteranceMaximumSpeechRate) {
                    Text("Speech rate")
                }
                Button("Play test voice") {
                    model.playTestVoice()
                }
            }
            Section("Azure Speech") {
                AzureConfigurationView(model: model)
            }
            Section("Playback") {
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
                Text("Flow keeps selected text only while the playback popup is visible. System voices are on-device. Azure voices are not part of this version.")
                    .font(.caption)
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}

private struct AzureConfigurationView: View {
    @ObservedObject var model: FlowModel
    @State private var endpoint = ""
    @State private var subscriptionKey = ""
    @State private var error: String?

    var body: some View {
        if let configuredEndpoint = model.azureEndpoint {
            Text("Configured for \(configuredEndpoint)")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField("Azure neural voice", text: $model.settings.azureVoiceName)
            Button("Remove Azure configuration", role: .destructive) {
                model.clearAzureConfiguration()
            }
        } else {
            Text("Azure sends selected text to your Speech resource to synthesize it. The subscription key stays in this Mac's Keychain.")
                .font(.caption)
                .foregroundStyle(.secondary)
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
