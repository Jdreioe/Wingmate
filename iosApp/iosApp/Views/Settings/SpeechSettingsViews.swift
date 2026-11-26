import SwiftUI
import Shared

struct SpeechSettingsView: View {
    @ObservedObject var settings: AppSettingsModel
    @ObservedObject var speech: SpeechSettingsModel
    let onSecondaryLanguageChanged: () -> Void
    @State private var showVoicePicker = false
    @State private var showAzureSetup = false
    @State private var showGoogleSetup = false
    @State private var showElevenLabsSetup = false

    private var languages: [String] {
        speech.availableLanguages.isEmpty ? [speech.primaryLanguage] : speech.availableLanguages
    }

    var body: some View {
        Form {
            Section("settings.interaction.title") {
                Picker("settings.interaction.select_key", selection: Binding(
                    get: { settings.selectKeyBinding }, set: { settings.setSelectKeyBinding($0) }
                )) {
                    Text("common.off").tag("")
                    ForEach(["Space", "Enter", "F8", "F9"], id: \.self) { Text($0).tag($0) }
                }
                Picker("settings.interaction.rest_key", selection: Binding(
                    get: { settings.restModeKeyBinding }, set: { settings.setRestModeKeyBinding($0) }
                )) {
                    Text("common.off").tag("")
                    ForEach(["Space", "Enter", "F8", "F9"], id: \.self) { Text($0).tag($0) }
                }
                Text("settings.interaction.description").font(.footnote).foregroundStyle(.secondary)
            }

            Section("settings.tts.title") {
                Picker("settings.speech.engine", selection: Binding(
                    get: { speech.ttsEngine },
                    set: { speech.setTtsEngine($0) }
                )) {
                    Text("settings.speech.engine.system").tag("SYSTEM")
                    Text("settings.speech.engine.azure").tag("AZURE_USER_RESOURCE")
                    Text("settings.speech.engine.google").tag("GOOGLE_CLOUD")
                    Text("ElevenLabs").tag("ELEVENLABS")
                }
                .pickerStyle(.menu)

                if speech.ttsEngine == "AZURE_USER_RESOURCE" || speech.ttsEngine == "AZURE_MANAGED" {
                    Button {
                        showAzureSetup = true
                    } label: {
                        SettingsNavigationLabel(
                            title: "settings.speech.azure.configure",
                            value: speech.azureConfigured ? "settings.speech.azure.configured" : "settings.speech.azure.not_configured"
                        )
                    }
                }
                if speech.ttsEngine == "ELEVENLABS" {
                    Button("elevenlabs.setup") { showElevenLabsSetup = true }
                }
                if speech.ttsEngine == "GOOGLE_CLOUD" {
                    Button { showGoogleSetup = true } label: {
                        SettingsNavigationLabel(
                            title: "settings.speech.google.configure",
                            value: speech.googleConfigured ? "settings.speech.google.configured" : "settings.speech.google.not_configured"
                        )
                    }
                }
            }

            Section("toolbar.voice") {
                Button { showVoicePicker = true } label: {
                    SettingsNavigationLabel(
                        title: "settings.speech.voice.choose",
                        value: (speech.selectedVoice?.displayName ?? speech.selectedVoice?.name) ?? "—"
                    )
                }

                Picker("toolbar.language", selection: Binding(
                    get: { speech.primaryLanguage },
                    set: { speech.updateLanguage($0) }
                )) {
                    ForEach(languages, id: \.self) { Text($0).tag($0) }
                }

                if speech.canChangeVoiceLanguage {
                    Picker("toolbar.second_language", selection: Binding(
                        get: { speech.secondaryLanguage },
                        set: { lang in
                            speech.updateSecondaryLanguage(lang, onApplied: onSecondaryLanguageChanged)
                        }
                    )) {
                        Text("settings.speech.language.none").tag("")
                        ForEach(languages.filter { $0 != speech.primaryLanguage }, id: \.self) { Text($0).tag($0) }
                    }
                }
            }
        }
        .navigationTitle(Text("settings.category.speech"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showVoicePicker) {
            VoiceSelectionSheet(selected: speech.selectedVoice, primaryLanguage: speech.primaryLanguage, onClose: { showVoicePicker = false }) { voice in
                Task {
                    await speech.chooseVoice(voice)
                    showVoicePicker = false
                }
            }
        }
        .sheet(isPresented: $showAzureSetup) {
            F0SetupView(
                onDone: {
                    showAzureSetup = false
                    Task { await speech.refreshAzureConfiguration() }
                },
                onBack: { showAzureSetup = false }
            )
        }
        .sheet(isPresented: $showElevenLabsSetup) {
            ElevenLabsSetupView(speech: speech, onClose: { showElevenLabsSetup = false })
        }
        .sheet(isPresented: $showGoogleSetup) {
            GoogleTtsSetupView(speech: speech, onClose: { showGoogleSetup = false })
        }
    }
}

enum GoogleTtsSetupStep {
    case welcome
    case cloudProject
    case apiKey
    case success
}

/// Google refusing the key, being unreachable, or the Keychain failing each need a different next step.
private func googleSetupFailureKey(_ error: Error) -> String {
    let kind = error.nativeFailure?.kind
    if kind == NativeFailureKind.network { return "settings.speech.google.setup.network_failed" }
    if kind == NativeFailureKind.persistence { return "settings.speech.google.setup.save_failed" }
    return "settings.speech.google.setup.validation_failed"
}

struct GoogleTtsSetupView: View {
    @ObservedObject var speech: SpeechSettingsModel
    let onClose: () -> Void
    let onSaved: (() -> Void)?
    @State private var step = GoogleTtsSetupStep.welcome
    @State private var apiKey = ""
    @State private var saving = false
    @State private var errorMessage: String?

    private let billingURL = URL(string: "https://console.cloud.google.com/billing")!
    private let apiURL = URL(string: "https://console.cloud.google.com/apis/library/texttospeech.googleapis.com")!
    private let credentialsURL = URL(string: "https://console.cloud.google.com/apis/credentials")!

    init(speech: SpeechSettingsModel, onClose: @escaping () -> Void, onSaved: (() -> Void)? = nil) {
        self.speech = speech
        self.onClose = onClose
        self.onSaved = onSaved
    }

    var body: some View {
        NavigationStack {
            Form {
                switch step {
                case .welcome:
                    Section {
                        Text("settings.speech.google.setup.welcome_description")
                        Button {
                            step = .cloudProject
                        } label: {
                            Label("settings.speech.google.setup.guided", systemImage: "list.number")
                        }
                        Button("settings.speech.google.setup.have_key") {
                            step = .apiKey
                        }
                        if speech.googleConfigured {
                            Label("settings.speech.google.configured", systemImage: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                            Button("common.continue", action: onClose)
                        }
                    } header: {
                        Text("settings.speech.google.setup.welcome_title")
                    }

                case .cloudProject:
                    Section {
                        Text("settings.speech.google.setup.cloud_steps")
                        Link(destination: billingURL) {
                            Label("settings.speech.google.setup.billing", systemImage: "creditcard")
                        }
                        Link(destination: apiURL) {
                            Label("settings.speech.google.setup.enable_api", systemImage: "checkmark.circle")
                        }
                        Link(destination: credentialsURL) {
                            Label("settings.speech.google.setup.create_key", systemImage: "key")
                        }
                        Button("settings.speech.google.setup.have_created_key") {
                            step = .apiKey
                        }
                    } header: {
                        Text("settings.speech.google.setup.cloud_title")
                    }

                case .apiKey:
                    Section {
                        Text("settings.speech.google.setup.restrict_api")
                        Text(
                            String(
                                format: NSLocalizedString("settings.speech.google.setup.ios_restriction", comment: ""),
                                Bundle.main.bundleIdentifier ?? "—"
                            )
                        )
                        .textSelection(.enabled)
                        .font(.footnote)
                        Text("settings.speech.google.help")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        SecureField("settings.speech.google.api_key", text: $apiKey)
                            .textContentType(.password)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        if let errorMessage { Text(errorMessage).foregroundStyle(.red) }
                        Button("settings.speech.google.setup.save_validate") {
                            Task {
                                saving = true
                                errorMessage = nil
                                defer { saving = false }
                                do {
                                    try await speech.saveGoogleApiKey(apiKey)
                                    apiKey = ""
                                    step = .success
                                    onSaved?()
                                } catch {
                                    if error.isNativeCancellation { return }
                                    errorMessage = NSLocalizedString(googleSetupFailureKey(error), comment: "")
                                }
                            }
                        }
                        .disabled(apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || saving)
                    } header: {
                        Text("settings.speech.google.setup.key_title")
                    }

                    if speech.googleConfigured {
                        Section {
                            Button("settings.speech.google.clear", role: .destructive) {
                                Task {
                                    do {
                                        try await speech.clearGoogleApiKey()
                                    } catch {
                                        errorMessage = NSLocalizedString("settings.speech.google.clear_failed", comment: "")
                                    }
                                }
                            }
                        }
                    }

                case .success:
                    Section {
                        Label("settings.speech.google.setup.complete_title", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Text("settings.speech.google.setup.complete_description")
                        Button("common.continue", action: onClose)
                    }
                }
            }
            .navigationTitle(Text("settings.speech.google.configure"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        switch step {
                        case .welcome:
                            onClose()
                        case .cloudProject, .apiKey:
                            step = .welcome
                        case .success:
                            onClose()
                        }
                    } label: {
                        Text(LocalizedStringKey(step == .welcome ? "common.cancel" : "common.back"))
                    }
                }
            }
        }
    }
}

struct ElevenLabsSetupView: View {
    @ObservedObject var speech: SpeechSettingsModel
    let onClose: () -> Void
    let onSaved: (() -> Void)?
    @State private var apiKey = ""
    @State private var busy = false
    @State private var failed = false

    init(speech: SpeechSettingsModel, onClose: @escaping () -> Void, onSaved: (() -> Void)? = nil) {
        self.speech = speech
        self.onClose = onClose
        self.onSaved = onSaved
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("elevenlabs.setup") {
                    Text("elevenlabs.description")
                    Text("elevenlabs.help")
                    Link("elevenlabs.key", destination: URL(string: "https://elevenlabs.io/app/settings/api-keys")!)
                    if speech.elevenLabsConfigured { Text("elevenlabs.configured") }
                    SecureField("elevenlabs.key", text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(busy)
                    if failed { Text("elevenlabs.error").foregroundStyle(.red) }
                    if busy { ProgressView() }
                    Button("elevenlabs.save") {
                        busy = true; failed = false
                        Task { @MainActor in
                            defer { busy = false }
                            do {
                                try await speech.saveElevenLabsApiKey(apiKey)
                                apiKey = ""
                                if let onSaved {
                                    onSaved()
                                } else {
                                    onClose()
                                }
                            } catch { failed = true }
                        }
                    }
                    .disabled(busy || apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if speech.elevenLabsConfigured {
                        Button("elevenlabs.clear", role: .destructive) {
                            busy = true; failed = false
                            Task { @MainActor in
                                defer { busy = false }
                                do { try await speech.clearElevenLabsApiKey(); apiKey = "" }
                                catch { failed = true }
                            }
                        }.disabled(busy)
                    }
                }
            }
            .navigationTitle("elevenlabs.setup")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("common.done", action: onClose).disabled(busy) } }
            .interactiveDismissDisabled(busy)
            .task { await speech.refreshElevenLabsConfiguration() }
        }
    }
}
