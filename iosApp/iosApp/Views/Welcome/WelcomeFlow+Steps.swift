import SwiftUI
import Shared

extension WelcomeFlow {
    // MARK: - Step 0: Welcome Intro
    @ViewBuilder
    var welcomeStep: some View {
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
    var startupModeStep: some View {
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
    var ttsSetupStep: some View {
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
    func providerCard(_ provider: WelcomeTtsProvider) -> some View {
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
    var voiceSelectionStep: some View {
        let activeProvider = WelcomeTtsProvider.from(engineId: speech.ttsEngine)

        VStack(spacing: 20) {
            Image(systemName: "person.wave.2.fill")
                .font(.system(size: 50))
                .foregroundColor(.blue)

            Text(NSLocalizedString("welcome_flow.voice_setup.title", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            if speech.useSystemTts {
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
    var languageSelectionStep: some View {
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
                            speech.updateLanguage(lang)
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
                                if lang == speech.primaryLanguage {
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
                    Text(speech.primaryLanguage)
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
    var testVoiceStep: some View {
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
    var analyticsConsentStep: some View {
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
    var completionStep: some View {
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
    var navigationButtons: some View {
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

    var canProceed: Bool {
        switch currentStep {
        case 0: return true
        case 1: return true
        case 2: return selectedTtsEngine != nil
        case 3: return speech.useSystemTts || selectedVoice != nil
        case 4: return true
        case 5: return true
        case 6: return true
        default: return true
        }
    }

    func checkTtsConfiguration() {
        // On a brand-new first run, leave no provider pre-highlighted so all four choices
        // start with equal visual weight. When re-running setup, highlight the saved choice.
        if UserDefaults.standard.bool(forKey: "welcome_flow_completed") {
            selectedTtsEngine = speech.ttsEngine
        }
    }

    func refreshAllProviderConfigurations() async {
        await speech.refreshAllProviderConfigurations()
    }

    func selectProvider(_ provider: WelcomeTtsProvider) {
        let changedEngine = !provider.matches(engineId: selectedTtsEngine)
        selectedTtsEngine = provider.engineId
        if changedEngine {
            selectedVoice = nil
            pendingLanguageSelection = false
            languageOptions = []
        }
        proceed(with: provider)
    }

    func proceedFromEngineStep() {
        let provider = WelcomeTtsProvider.from(engineId: selectedTtsEngine)
        proceed(with: provider)
    }

    func proceed(with provider: WelcomeTtsProvider) {
        if !provider.requiresSetup || provider.isConfigured(in: speech) {
            speech.setTtsEngine(provider.engineId)
            withAnimation(.easeInOut(duration: 0.4)) {
                currentStep = 3
            }
            return
        }
        openSetup(for: provider)
    }

    func openSetup(for provider: WelcomeTtsProvider) {
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

    func completeProviderSetup(_ provider: WelcomeTtsProvider) {
        let changedEngine = !provider.matches(engineId: speech.ttsEngine)
        selectedTtsEngine = provider.engineId
        speech.setTtsEngine(provider.engineId)
        if changedEngine {
            selectedVoice = nil
            pendingLanguageSelection = false
            languageOptions = []
        }
        withAnimation(.easeInOut(duration: 0.4)) {
            currentStep = 3
        }
    }

    func availablePrimaryLanguages(for voice: Shared.Voice) -> [String] {
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
