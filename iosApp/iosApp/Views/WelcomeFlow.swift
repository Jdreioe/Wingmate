import SwiftUI
import Shared
import UniformTypeIdentifiers

enum WelcomeTtsProvider: String, CaseIterable, Identifiable {
    case system = "SYSTEM"
    case azure = "AZURE_USER_RESOURCE"
    case google = "GOOGLE_CLOUD"
    case elevenLabs = "ELEVENLABS"

    var id: String { rawValue }
    var engineId: String { rawValue }
    var requiresSetup: Bool { self != .system }

    static func from(engineId: String?) -> WelcomeTtsProvider {
        switch engineId {
        case "AZURE_USER_RESOURCE", "AZURE_MANAGED":
            return .azure
        case "GOOGLE_CLOUD":
            return .google
        case "ELEVENLABS":
            return .elevenLabs
        default:
            return .system
        }
    }

    func matches(engineId: String?) -> Bool {
        switch self {
        case .azure:
            return engineId == "AZURE_USER_RESOURCE" || engineId == "AZURE_MANAGED"
        default:
            return engineId == rawValue
        }
    }

    @MainActor
    func isConfigured(in model: IosViewModel) -> Bool {
        switch self {
        case .system:
            return true
        case .azure:
            return model.azureConfigured
        case .google:
            return model.googleConfigured
        case .elevenLabs:
            return model.elevenLabsConfigured
        }
    }

    var icon: String {
        switch self {
        case .system:
            return "speaker.wave.2.fill"
        case .azure:
            return "cloud.fill"
        case .google:
            return "cloud.sun.fill"
        case .elevenLabs:
            return "waveform.circle.fill"
        }
    }

    var title: String {
        switch self {
        case .system:
            return NSLocalizedString("welcome_flow.system_tts.title", comment: "")
        case .azure:
            return NSLocalizedString("welcome_flow.azure_tts.title", comment: "")
        case .google:
            return NSLocalizedString("welcome_flow.google_tts.title", comment: "")
        case .elevenLabs:
            return NSLocalizedString("welcome_flow.elevenlabs_tts.title", comment: "")
        }
    }

    var subtitle: String {
        switch self {
        case .system:
            return NSLocalizedString("welcome_flow.system_tts.subtitle", comment: "")
        case .azure:
            return NSLocalizedString("welcome_flow.azure_tts.subtitle", comment: "")
        case .google:
            return NSLocalizedString("welcome_flow.google_tts.subtitle", comment: "")
        case .elevenLabs:
            return NSLocalizedString("welcome_flow.elevenlabs_tts.subtitle", comment: "")
        }
    }

    var pros: [String] {
        switch self {
        case .system:
            return [
                NSLocalizedString("welcome_flow.system_tts.pro1", comment: ""),
                NSLocalizedString("welcome_flow.system_tts.pro2", comment: ""),
                NSLocalizedString("welcome_flow.system_tts.pro3", comment: "")
            ]
        case .azure:
            return [
                NSLocalizedString("welcome_flow.azure_tts.pro1", comment: ""),
                NSLocalizedString("welcome_flow.azure_tts.pro2", comment: ""),
                NSLocalizedString("welcome_flow.azure_tts.pro3", comment: "")
            ]
        case .google:
            return [
                NSLocalizedString("welcome_flow.google_tts.pro1", comment: ""),
                NSLocalizedString("welcome_flow.google_tts.pro2", comment: ""),
                NSLocalizedString("welcome_flow.google_tts.pro3", comment: "")
            ]
        case .elevenLabs:
            return [
                NSLocalizedString("welcome_flow.elevenlabs_tts.pro1", comment: ""),
                NSLocalizedString("welcome_flow.elevenlabs_tts.pro2", comment: ""),
                NSLocalizedString("welcome_flow.elevenlabs_tts.pro3", comment: "")
            ]
        }
    }

    var cons: [String] {
        switch self {
        case .system:
            return [
                NSLocalizedString("welcome_flow.system_tts.con1", comment: ""),
                NSLocalizedString("welcome_flow.system_tts.con2", comment: "")
            ]
        case .azure:
            return [
                NSLocalizedString("welcome_flow.azure_tts.con1", comment: ""),
                NSLocalizedString("welcome_flow.azure_tts.con2", comment: ""),
                NSLocalizedString("welcome_flow.azure_tts.con3", comment: "")
            ]
        case .google:
            return [
                NSLocalizedString("welcome_flow.google_tts.con1", comment: ""),
                NSLocalizedString("welcome_flow.google_tts.con2", comment: ""),
                NSLocalizedString("welcome_flow.google_tts.con3", comment: "")
            ]
        case .elevenLabs:
            return [
                NSLocalizedString("welcome_flow.elevenlabs_tts.con1", comment: ""),
                NSLocalizedString("welcome_flow.elevenlabs_tts.con2", comment: ""),
                NSLocalizedString("welcome_flow.elevenlabs_tts.con3", comment: "")
            ]
        }
    }

    var voicesSubtitle: String {
        switch self {
        case .system:
            return NSLocalizedString("welcome_flow.system_tts_info", comment: "")
        case .azure:
            return NSLocalizedString("welcome_flow.azure_voices_subtitle", comment: "")
        case .google:
            return NSLocalizedString("welcome_flow.google_voices_subtitle", comment: "")
        case .elevenLabs:
            return NSLocalizedString("welcome_flow.elevenlabs_voices_subtitle", comment: "")
        }
    }

    var configureTitle: String? {
        switch self {
        case .system:
            return nil
        case .azure:
            return NSLocalizedString("settings.speech.azure.configure", comment: "")
        case .google:
            return NSLocalizedString("settings.speech.google.configure", comment: "")
        case .elevenLabs:
            return NSLocalizedString("elevenlabs.setup", comment: "")
        }
    }
}

struct WelcomeFlow: View {
    @ObservedObject var model: IosViewModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var currentStep = 0
    @State private var modeTour: String? = nil // "keyboard" or "screens"
    @State private var showVoicePicker = false
    @State private var showLanguagePicker = false
    @State private var showAzureSetup = false
    @State private var showGoogleSetup = false
    @State private var showElevenLabsSetup = false
    @State private var selectedVoice: Shared.Voice? = nil
    @State private var selectedTtsEngine: String? = nil
    @State private var pendingLanguageSelection = false
    @State private var languageOptions: [String] = []
    @State private var previewText: String = NSLocalizedString("welcome_flow.test_text", comment: "")
    @State private var importingBackup = false
    @State private var confirmRestoreURL: URL? = nil
    @State private var restoreStatus: String? = nil
    @State private var isRestoring = false

    let onComplete: () -> Void
    let onSkip: () -> Void
    let onRestoreComplete: () -> Void

    private let totalSteps = 7

    private var isTabletLayout: Bool { horizontalSizeClass == .regular }

    private var stepMaxWidth: CGFloat {
        (isTabletLayout && currentStep == 2) ? 900 : 560
    }

    var body: some View {
        NavigationStack {
            ZStack {
                // Background gradient
                LinearGradient(
                    colors: [Color.blue.opacity(0.1), Color.purple.opacity(0.1)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()

                VStack(spacing: 20) {
                    // Progress indicator
                    HStack {
                        ForEach(0...totalSteps, id: \.self) { step in
                            Circle()
                                .frame(width: 10, height: 10)
                                .foregroundColor(step <= currentStep ? .blue : .gray.opacity(0.3))
                        }
                    }
                    .padding(.top, 16)

                    Spacer()

                    // Step content
                    Group {
                        switch currentStep {
                        case 0:
                            welcomeStep
                        case 1:
                            startupModeStep
                        case 2:
                            ttsSetupStep
                        case 3:
                            voiceSelectionStep
                        case 4:
                            languageSelectionStep
                        case 5:
                            testVoiceStep
                        case 6:
                            analyticsConsentStep
                        default:
                            completionStep
                        }
                    }
                    .transition(.asymmetric(
                        insertion: .move(edge: .trailing).combined(with: .opacity),
                        removal: .move(edge: .leading).combined(with: .opacity)
                    ))

                    Spacer()

                    navigationButtons
                        .padding(.bottom, 24)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: stepMaxWidth)
                .frame(maxWidth: .infinity)
            }
            .navigationBarHidden(true)
        }
        .sheet(isPresented: $showVoicePicker) {
            VoiceSelectionSheet(selected: selectedVoice, onClose: { showVoicePicker = false }) { voice in
                Task {
                    await model.chooseVoice(voice)
                    let langs = availablePrimaryLanguages(for: voice)

                    await MainActor.run {
                        selectedVoice = voice
                        showVoicePicker = false

                        if langs.count > 1 {
                            languageOptions = langs
                            pendingLanguageSelection = true
                        } else {
                            pendingLanguageSelection = false
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $showLanguagePicker) {
            LanguageSelectionSheet(
                languages: languageOptions,
                selected: model.primaryLanguage,
                onClose: { showLanguagePicker = false },
                onSelect: { lang in
                    model.updateLanguage(lang)
                    pendingLanguageSelection = false
                    showLanguagePicker = false
                }
            )
        }
        .sheet(isPresented: $showAzureSetup) {
            F0SetupView(
                onDone: {
                    showAzureSetup = false
                    Task {
                        await model.refreshAzureConfiguration()
                        await MainActor.run {
                            completeProviderSetup(.azure)
                        }
                    }
                },
                onBack: { showAzureSetup = false }
            )
            .padding(.horizontal, 24)
        }
        .sheet(isPresented: $showGoogleSetup) {
            GoogleTtsSetupView(
                model: model,
                onClose: { showGoogleSetup = false },
                onSaved: {
                    showGoogleSetup = false
                    completeProviderSetup(.google)
                }
            )
        }
        .sheet(isPresented: $showElevenLabsSetup) {
            ElevenLabsSetupView(
                model: model,
                onClose: { showElevenLabsSetup = false },
                onSaved: {
                    showElevenLabsSetup = false
                    completeProviderSetup(.elevenLabs)
                }
            )
        }
        .onAppear {
            checkTtsConfiguration()
            selectedVoice = model.selectedVoice
        }
        .task {
            await refreshAllProviderConfigurations()
        }
        .fileImporter(isPresented: $importingBackup, allowedContentTypes: [.zip, .data]) { result in
            if case .success(let url) = result { confirmRestoreURL = url }
        }
        .confirmationDialog(
            "backup.replace_title",
            isPresented: Binding(
                get: { confirmRestoreURL != nil },
                set: { if !$0 { confirmRestoreURL = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("backup.replace_action", role: .destructive) {
                guard let url = confirmRestoreURL else { return }
                confirmRestoreURL = nil
                isRestoring = true
                restoreStatus = nil
                Task {
                    let accessed = url.startAccessingSecurityScopedResource()
                    defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                    do {
                        let result = try await model.restoreCompleteBackup(path: url.path)
                        if !result.isSuccess {
                            restoreStatus = result.message ?? NSLocalizedString("backup.failed", comment: "")
                            isRestoring = false
                            return
                        }
                        await model.refreshParitySettings()
                        await refreshAllProviderConfigurations()
                        model.refreshVoiceAndLanguages()
                        await model.loadBoardSets()
                        isRestoring = false
                        onRestoreComplete()
                    } catch is CancellationError {
                        isRestoring = false
                    } catch {
                        restoreStatus = NSLocalizedString("backup.failed", comment: "")
                        isRestoring = false
                    }
                }
            }
            Button("common.cancel", role: .cancel) { confirmRestoreURL = nil }
        } message: {
            Text("backup.replace_warning")
        }
    }

    // MARK: - Step 0: Welcome Intro
    @ViewBuilder
    private var welcomeStep: some View {
        VStack(spacing: 20) {
            Image(systemName: "waveform.path")
                .font(.system(size: 80))
                .foregroundColor(.blue)

            Text(NSLocalizedString("welcome_flow.title", comment: ""))
                .font(.largeTitle)
                .fontWeight(.bold)

            Text(NSLocalizedString("welcome_flow.subtitle", comment: ""))
                .font(.title3)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal)

            Button("backup.restore") { importingBackup = true }
                .buttonStyle(.bordered)
                .disabled(isRestoring)

            if isRestoring {
                ProgressView()
            }

            if let restoreStatus {
                Text(restoreStatus)
                    .font(.footnote)
                    .foregroundColor(.red)
                    .multilineTextAlignment(.center)
            }
        }
    }

    // MARK: - Step 1: Startup Mode Selection & Tour
    @ViewBuilder
    private var startupModeStep: some View {
        if let mode = modeTour {
            StartupModeTourView(
                mode: mode,
                onContinue: {
                    if mode == "keyboard" {
                        modeTour = "screens"
                    } else {
                        modeTour = nil
                    }
                },
                onBack: { modeTour = nil }
            )
        } else {
            VStack(spacing: 20) {
                Text(NSLocalizedString("welcome_mode.title", comment: ""))
                    .font(.title2)
                    .fontWeight(.bold)

                Text(NSLocalizedString("welcome_mode.subtitle", comment: ""))
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)

                VStack(spacing: 16) {
                    StartupModeCard(
                        title: NSLocalizedString("welcome_mode.keyboard.title", comment: ""),
                        description: NSLocalizedString("welcome_mode.keyboard.desc", comment: ""),
                        icon: "keyboard",
                        isSelected: !model.boardModeEnabled,
                        action: {
                            model.boardModeEnabled = false
                            withAnimation(.easeInOut(duration: 0.4)) {
                                currentStep = 2
                            }
                        }
                    )

                    StartupModeCard(
                        title: NSLocalizedString("welcome_mode.screens.title", comment: ""),
                        description: NSLocalizedString("welcome_mode.screens.desc", comment: ""),
                        icon: "square.grid.3x3.fill",
                        isSelected: model.boardModeEnabled,
                        action: {
                            model.boardModeEnabled = true
                            withAnimation(.easeInOut(duration: 0.4)) {
                                currentStep = 2
                            }
                        }
                    )
                }
                .padding(.top, 8)

                Button(action: { modeTour = "keyboard" }) {
                    HStack {
                        Image(systemName: "info.circle")
                        Text(NSLocalizedString("welcome_mode.tour.title", comment: ""))
                    }
                    .font(.subheadline)
                    .foregroundColor(.blue)
                }
            }
        }
    }

    // MARK: - Step 2: Voice Engine Selector
    @ViewBuilder
    private var ttsSetupStep: some View {
        ScrollView {
            VStack(spacing: isTabletLayout ? 14 : 16) {
                VStack(spacing: isTabletLayout ? 6 : 10) {
                    Image(systemName: "gear")
                        .font(.system(size: isTabletLayout ? 34 : 44))
                        .foregroundColor(.blue)

                    Text(NSLocalizedString("welcome_flow.tts_setup.title", comment: ""))
                        .font(.title2)
                        .fontWeight(.bold)

                    Text(NSLocalizedString("welcome_flow.tts_setup.subtitle", comment: ""))
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .foregroundColor(.secondary)
                }

                if isTabletLayout {
                    // On iPad / regular-width tablets, show all four speech providers in a
                    // compact 2×2 grid so every choice is immediately visible with equal weight.
                    Grid(alignment: .topLeading, horizontalSpacing: 16, verticalSpacing: 14) {
                        GridRow {
                            providerCard(.system)
                            providerCard(.azure)
                        }
                        GridRow {
                            providerCard(.google)
                            providerCard(.elevenLabs)
                        }
                    }
                    .padding(.vertical, 4)
                } else {
                    VStack(spacing: 12) {
                        ForEach(WelcomeTtsProvider.allCases) { provider in
                            providerCard(provider)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    @ViewBuilder
    private func providerCard(_ provider: WelcomeTtsProvider) -> some View {
        TtsOptionCard(
            title: provider.title,
            subtitle: provider.subtitle,
            icon: provider.icon,
            pros: provider.pros,
            cons: provider.cons,
            isSelected: provider.matches(engineId: selectedTtsEngine),
            action: { selectProvider(provider) }
        )
    }

    // MARK: - Step 3: Voice Selection Page
    @ViewBuilder
    private var voiceSelectionStep: some View {
        let activeProvider = WelcomeTtsProvider.from(engineId: model.ttsEngine)

        VStack(spacing: 20) {
            Image(systemName: "person.wave.2.fill")
                .font(.system(size: 50))
                .foregroundColor(.blue)

            Text(NSLocalizedString("welcome_flow.voice_setup.title", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            if model.useSystemTts {
                VStack(spacing: 16) {
                    Text(NSLocalizedString("welcome_flow.system_tts_info", comment: ""))
                        .font(.headline)
                        .foregroundColor(.secondary)

                    Text(NSLocalizedString("welcome_flow.system_tts_settings_info", comment: ""))
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .foregroundColor(.secondary)
                        .padding(.horizontal)

                    Button(action: {
                        if let settingsUrl = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(settingsUrl)
                        }
                    }) {
                        HStack {
                            Image(systemName: "gear")
                            Text(NSLocalizedString("welcome_flow.open_settings", comment: ""))
                        }
                        .font(.headline)
                        .foregroundColor(.white)
                        .padding()
                        .background(Color.blue)
                        .cornerRadius(12)
                    }
                }
            } else {
                VStack(spacing: 16) {
                    Text(activeProvider.voicesSubtitle)
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    if let voice = selectedVoice {
                        SelectedVoiceCard(voice: voice, onSpeak: { model.speak(previewText) })
                    }

                    Button(action: { showVoicePicker = true }) {
                        HStack {
                            Image(systemName: "person.2.fill")
                            Text(selectedVoice == nil ? NSLocalizedString("welcome_flow.choose_voice", comment: "") : NSLocalizedString("welcome_flow.change_voice", comment: ""))
                        }
                        .font(.headline)
                        .foregroundColor(.white)
                        .padding()
                        .background(Color.blue)
                        .cornerRadius(12)
                    }

                    if let configureTitle = activeProvider.configureTitle {
                        Button(configureTitle) {
                            openSetup(for: activeProvider)
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    // MARK: - Step 4: Primary Language Selection
    @ViewBuilder
    private var languageSelectionStep: some View {
        VStack(spacing: 20) {
            Image(systemName: "globe")
                .font(.system(size: 50))
                .foregroundColor(.blue)

            Text(NSLocalizedString("welcome_flow.choose_primary_language", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            if pendingLanguageSelection && !languageOptions.isEmpty {
                VStack(spacing: 12) {
                    ForEach(languageOptions, id: \.self) { lang in
                        Button(action: {
                            model.updateLanguage(lang)
                            pendingLanguageSelection = false
                            withAnimation(.easeInOut(duration: 0.4)) {
                                currentStep = 5
                            }
                        }) {
                            HStack {
                                Text(lang)
                                    .font(.headline)
                                    .foregroundColor(.primary)
                                Spacer()
                                if lang == model.primaryLanguage {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(.blue)
                                }
                            }
                            .padding()
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(Color(.secondarySystemBackground))
                            )
                        }
                        .buttonStyle(PlainButtonStyle())
                    }
                }
            } else {
                VStack(spacing: 12) {
                    Text(model.primaryLanguage)
                        .font(.title3)
                        .fontWeight(.semibold)

                    Button(action: { showLanguagePicker = true }) {
                        Text(NSLocalizedString("welcome_flow.select_language", comment: ""))
                            .font(.headline)
                            .foregroundColor(.blue)
                    }
                }
            }
        }
    }

    // MARK: - Step 5: Test Voice Screen
    @ViewBuilder
    private var testVoiceStep: some View {
        VStack(spacing: 20) {
            Image(systemName: "speaker.wave.3.fill")
                .font(.system(size: 50))
                .foregroundColor(.blue)

            Text(NSLocalizedString("welcome_flow.test_voice", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            VoicePreviewCard(
                title: NSLocalizedString("welcome_flow.test_voice", comment: ""),
                previewText: $previewText,
                onSpeak: { model.speak(previewText) },
                onStop: { model.stopSpeech() }
            )
        }
    }

    // MARK: - Step 6: Analytics Consent Screen
    @ViewBuilder
    private var analyticsConsentStep: some View {
        VStack(spacing: 20) {
            Image(systemName: "chart.bar.fill")
                .font(.system(size: 50))
                .foregroundColor(.blue)

            Text(NSLocalizedString("welcome_analytics.title", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            Text(NSLocalizedString("welcome_analytics.desc", comment: ""))
                .font(.body)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            // Bound to the shared setting, like Settings, so both screens show the same consent.
            Toggle(isOn: Binding(
                get: { model.featureUsageReportingEnabled },
                set: { model.setFeatureUsageReportingEnabled($0) }
            )) {
                Text(NSLocalizedString("welcome_analytics.opt_in", comment: ""))
                    .font(.headline)
            }
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemBackground))
            )
        }
    }

    // MARK: - Step 7: Completion Step
    @ViewBuilder
    private var completionStep: some View {
        VStack(spacing: 20) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 80))
                .foregroundColor(.green)

            Text(NSLocalizedString("welcome_flow.finish", comment: ""))
                .font(.title)
                .fontWeight(.bold)

            Text(NSLocalizedString("welcome_flow.setup_complete_subtitle", comment: ""))
                .font(.title3)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal)

            VStack(spacing: 12) {
                HStack {
                    Image(systemName: "plus.circle.fill")
                        .foregroundColor(.blue)
                    Text(NSLocalizedString("welcome_flow.help.add_phrases", comment: ""))
                    Spacer()
                }
                HStack {
                    Image(systemName: "play.circle.fill")
                        .foregroundColor(.blue)
                    Text(NSLocalizedString("welcome_flow.help.play_phrases", comment: ""))
                    Spacer()
                }
                HStack {
                    Image(systemName: "folder.fill")
                        .foregroundColor(.blue)
                    Text(NSLocalizedString("welcome_flow.help.organize", comment: ""))
                    Spacer()
                }
            }
            .font(.subheadline)
            .foregroundColor(.secondary)
            .padding(.horizontal)
        }
    }

    // MARK: - Navigation Buttons
    @ViewBuilder
    private var navigationButtons: some View {
        HStack(spacing: 16) {
            if currentStep > 0 {
                Button(NSLocalizedString("welcome_flow.back", comment: "")) {
                    withAnimation(.easeInOut(duration: 0.4)) {
                        switch currentStep {
                        case 1:
                            if modeTour != nil { modeTour = nil } else { currentStep = 0 }
                        case 3:
                            currentStep = 2
                        case 4:
                            currentStep = 3
                        case 5:
                            currentStep = pendingLanguageSelection ? 4 : 3
                        default:
                            currentStep -= 1
                        }
                    }
                }
                .font(.headline)
                .foregroundColor(.blue)
            }

            Spacer()

            if currentStep < totalSteps {
                Button(NSLocalizedString("welcome_flow.skip", comment: "")) {
                    onSkip()
                }
                .font(.headline)
                .foregroundColor(.secondary)
            }

            Button(currentStep >= totalSteps ? NSLocalizedString("welcome_flow.get_started", comment: "") : NSLocalizedString("welcome_flow.next", comment: "")) {
                if currentStep >= totalSteps {
                    onComplete()
                } else if canProceed {
                    switch currentStep {
                    case 2:
                        proceedFromEngineStep()
                    case 3:
                        withAnimation(.easeInOut(duration: 0.4)) {
                            currentStep = pendingLanguageSelection ? 4 : 5
                        }
                    default:
                        withAnimation(.easeInOut(duration: 0.4)) {
                            currentStep += 1
                        }
                    }
                }
            }
            .font(.headline)
            .foregroundColor(.white)
            .padding()
            .background(canProceed ? Color.blue : Color.gray)
            .cornerRadius(12)
            .disabled(!canProceed)
        }
    }

    private var canProceed: Bool {
        switch currentStep {
        case 0: return true
        case 1: return true
        case 2: return selectedTtsEngine != nil
        case 3: return model.useSystemTts || selectedVoice != nil
        case 4: return true
        case 5: return true
        case 6: return true
        default: return true
        }
    }

    private func checkTtsConfiguration() {
        // On a brand-new first run, leave no provider pre-highlighted so all four choices
        // start with equal visual weight. When re-running setup, highlight the saved choice.
        if UserDefaults.standard.bool(forKey: "welcome_flow_completed") {
            selectedTtsEngine = model.ttsEngine
        }
    }

    private func refreshAllProviderConfigurations() async {
        await model.refreshAzureConfiguration()
        await model.refreshGoogleConfiguration()
        await model.refreshElevenLabsConfiguration()
    }

    private func selectProvider(_ provider: WelcomeTtsProvider) {
        let changedEngine = !provider.matches(engineId: selectedTtsEngine)
        selectedTtsEngine = provider.engineId
        if changedEngine {
            selectedVoice = nil
            pendingLanguageSelection = false
            languageOptions = []
        }
        proceed(with: provider)
    }

    private func proceedFromEngineStep() {
        let provider = WelcomeTtsProvider.from(engineId: selectedTtsEngine)
        proceed(with: provider)
    }

    private func proceed(with provider: WelcomeTtsProvider) {
        if !provider.requiresSetup || provider.isConfigured(in: model) {
            model.setTtsEngine(provider.engineId)
            withAnimation(.easeInOut(duration: 0.4)) {
                currentStep = 3
            }
            return
        }
        openSetup(for: provider)
    }

    private func openSetup(for provider: WelcomeTtsProvider) {
        switch provider {
        case .system:
            break
        case .azure:
            showAzureSetup = true
        case .google:
            showGoogleSetup = true
        case .elevenLabs:
            showElevenLabsSetup = true
        }
    }

    private func completeProviderSetup(_ provider: WelcomeTtsProvider) {
        let changedEngine = !provider.matches(engineId: model.ttsEngine)
        selectedTtsEngine = provider.engineId
        model.setTtsEngine(provider.engineId)
        if changedEngine {
            selectedVoice = nil
            pendingLanguageSelection = false
            languageOptions = []
        }
        withAnimation(.easeInOut(duration: 0.4)) {
            currentStep = 3
        }
    }

    private func availablePrimaryLanguages(for voice: Shared.Voice) -> [String] {
        var langs: [String] = []
        let primary = (voice.primaryLanguage ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !primary.isEmpty { langs.append(primary) }

        if let supported = voice.supportedLanguages {
            for lang in supported {
                let trimmed = lang.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty && !langs.contains(trimmed) {
                    langs.append(trimmed)
                }
            }
        }
        return langs
    }
}

// MARK: - TTS Option Card Component
struct TtsOptionCard: View {
    let title: String
    let subtitle: String
    let icon: String
    let pros: [String]
    let cons: [String]
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: icon)
                        .font(.title2)
                        .foregroundColor(.blue)
                        .frame(width: 28, alignment: .center)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.headline)
                            .foregroundColor(.primary)
                            .multilineTextAlignment(.leading)
                        Text(subtitle)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(isSelected ? .blue : .secondary)
                }
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(pros, id: \.self) { item in
                        Label(item, systemImage: "checkmark")
                            .font(.caption)
                            .foregroundColor(.primary)
                            .multilineTextAlignment(.leading)
                    }
                    ForEach(cons, id: \.self) { item in
                        Label(item, systemImage: "minus")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.leading)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? Color.blue : Color(.separator).opacity(0.6), lineWidth: isSelected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
    }
}

struct SelectedVoiceCard: View {
    let voice: Shared.Voice
    let onSpeak: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(voice.displayName ?? voice.name ?? NSLocalizedString("welcome_flow.choose_voice", comment: ""))
                    .font(.headline)
                Text(voice.selectedLanguage ?? voice.primaryLanguage ?? "")
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Button(action: onSpeak) { Image(systemName: "speaker.wave.2.fill") }
                .buttonStyle(.bordered)
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}

struct VoicePreviewCard: View {
    let title: String
    @Binding var previewText: String
    let onSpeak: () -> Void
    let onStop: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            TextField(title, text: $previewText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
            HStack {
                Button(action: onSpeak) { Label(NSLocalizedString("common.play", comment: ""), systemImage: "play.fill") }
                    .buttonStyle(.borderedProminent)
                Button(action: onStop) { Label(NSLocalizedString("common.stop", comment: ""), systemImage: "stop.fill") }
                    .buttonStyle(.bordered)
            }
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}

struct StartupModeCard: View {
    let title: String
    let description: String
    let icon: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.title)
                    .foregroundColor(.blue)

                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .foregroundColor(.primary)
                    Text(description)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                        .font(.title2)
                }
            }
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemBackground))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 2)
                    )
            )
        }
        .buttonStyle(PlainButtonStyle())
    }
}

// MARK: - Startup Mode Tour Component
struct StartupModeTourView: View {
    let mode: String
    let onContinue: () -> Void
    let onBack: () -> Void

    var isKeyboard: Bool { mode == "keyboard" }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: isKeyboard ? "keyboard" : "square.grid.3x3.fill")
                    .font(.system(size: 50))
                    .foregroundColor(.blue)

                Text(NSLocalizedString("welcome_mode.tour.title", comment: ""))
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundColor(.blue)

                Text(isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.title", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.title", comment: ""))
                    .font(.title2)
                    .fontWeight(.bold)

                Text(isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.subtitle", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.subtitle", comment: ""))
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)

                if isKeyboard {
                    TourPhrasePreviewCard()
                }

                VStack(spacing: 12) {
                    TourStepCard(stepNumber: 1, text: isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.step1", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.step1", comment: ""))
                    TourStepCard(stepNumber: 2, text: isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.step2", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.step2", comment: ""))
                    TourStepCard(stepNumber: 3, text: isKeyboard ? NSLocalizedString("welcome_mode.tour.keyboard.step3", comment: "") : NSLocalizedString("welcome_mode.tour.boardsets.step3", comment: ""))
                }

                Button(action: onContinue) {
                    Text(NSLocalizedString("common.continue", comment: ""))
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.blue)
                        .cornerRadius(12)
                }
                .padding(.top, 8)

                Button(action: onBack) {
                    Text(NSLocalizedString("welcome_mode.tour.back", comment: ""))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
        }
    }
}

struct TourStepCard: View {
    let stepNumber: Int
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Text("\(stepNumber)")
                .font(.headline)
                .foregroundColor(.blue)
                .frame(width: 28, height: 28)
                .background(Circle().fill(Color.blue.opacity(0.1)))

            Text(text)
                .font(.subheadline)
                .foregroundColor(.primary)

            Spacer()
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

struct TourPhrasePreviewCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(NSLocalizedString("welcome_mode.tour.phrase_preview_title", comment: ""))
                .font(.caption)
                .foregroundColor(.secondary)

            Text(NSLocalizedString("welcome_mode.tour.phrase_preview_message", comment: ""))
                .font(.body)
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color(.systemBackground)))

            HStack(spacing: 8) {
                Text("welcome_mode.tour.sample.hello").font(.caption).bold().padding(.vertical, 6).padding(.horizontal, 12).background(Color.blue.opacity(0.15)).cornerRadius(6)
                Text("welcome_mode.tour.sample.yes").font(.caption).bold().padding(.vertical, 6).padding(.horizontal, 12).background(Color.blue.opacity(0.15)).cornerRadius(6)
                Text("welcome_mode.tour.sample.help").font(.caption).bold().padding(.vertical, 6).padding(.horizontal, 12).background(Color.blue.opacity(0.15)).cornerRadius(6)
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.tertiarySystemBackground))
        )
    }
}
