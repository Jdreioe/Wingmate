import SwiftUI
import Shared
import UniformTypeIdentifiers

struct WelcomeFlow: View {
    @ObservedObject var model: IosViewModel
    @ObservedObject var speech: SpeechSettingsModel
    @ObservedObject var backup: BackupModel
    @Environment(\.horizontalSizeClass) var horizontalSizeClass
    @State var currentStep = 0
    @State var modeTour: String? = nil // "keyboard" or "screens"
    @State var showVoicePicker = false
    @State var showLanguagePicker = false
    @State var showAzureSetup = false
    @State var showGoogleSetup = false
    @State var showElevenLabsSetup = false
    @State var selectedVoice: Shared.Voice? = nil
    /// The per-language system voice choices, reloaded when the voice picker closes.
    @State var systemVoiceChoices: [String: String] = SystemVoicePreferences.all
    @State var selectedTtsEngine: String? = nil
    @State var pendingLanguageSelection = false
    @State var languageOptions: [String] = []
    @State var previewText: String = NSLocalizedString("welcome_flow.test_text", comment: "")
    @State var importingBackup = false
    @State var confirmRestoreURL: URL? = nil

    var restoreStatus: String? { backup.state.statusMessage }
    var isRestoring: Bool { backup.state.isRestoring }

    let onComplete: () -> Void
    let onSkip: () -> Void
    let onRestoreComplete: () -> Void

    let totalSteps = 7

    var isTabletLayout: Bool { horizontalSizeClass == .regular }

    var stepMaxWidth: CGFloat {
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
        .sheet(isPresented: $showVoicePicker, onDismiss: { systemVoiceChoices = SystemVoicePreferences.all }) {
            VoiceSelectionSheet(
                selected: selectedVoice,
                primaryLanguage: speech.primaryLanguage,
                onClose: { showVoicePicker = false }
            ) { voice in
                Task {
                    await speech.chooseVoice(voice)
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
                selected: speech.primaryLanguage,
                onClose: { showLanguagePicker = false },
                onSelect: { lang in
                    speech.updateLanguage(lang)
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
                        await speech.refreshAzureConfiguration()
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
                speech: speech,
                onClose: { showGoogleSetup = false },
                onSaved: {
                    showGoogleSetup = false
                    completeProviderSetup(.google)
                }
            )
        }
        .sheet(isPresented: $showElevenLabsSetup) {
            ElevenLabsSetupView(
                speech: speech,
                onClose: { showElevenLabsSetup = false },
                onSaved: {
                    showElevenLabsSetup = false
                    completeProviderSetup(.elevenLabs)
                }
            )
        }
        .onAppear {
            checkTtsConfiguration()
            selectedVoice = speech.selectedVoice
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
                Task {
                    let restored = await backup.restoreBackup(from: url) {
                        try await model.reloadAfterBackupRestore()
                        await refreshAllProviderConfigurations()
                    }.value
                    if restored {
                        onRestoreComplete()
                    }
                }
            }
            Button("common.cancel", role: .cancel) { confirmRestoreURL = nil }
        } message: {
            Text("backup.replace_warning")
        }
    }
}
