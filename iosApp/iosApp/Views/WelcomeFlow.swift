import SwiftUI
import Shared

struct WelcomeFlow: View {
    @ObservedObject var model: IosViewModel
    @State private var currentStep = 0
    @State private var showAzureSettings = false
    @State private var showVoicePicker = false
    @State private var showLanguagePicker = false
    @State private var selectedVoice: Shared.Voice? = nil
    @State private var hasConfiguredTts = false
    @State private var selectedUseSystemTts: Bool? = nil
    @State private var pendingLanguageSelection = false
    @State private var languageOptions: [String] = []
    @State private var previewText: String = NSLocalizedString("welcome_flow.test_text", comment: "")
    
    let onComplete: () -> Void
    let onSkip: () -> Void
    
    private let totalSteps = 3
    
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
                
                VStack(spacing: 24) {
                    // Progress indicator
                    HStack {
                        ForEach(0..<totalSteps, id: \.self) { step in
                            Circle()
                                .frame(width: 12, height: 12)
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
                            ttsSetupStep
                        case 2:
                            voiceSelectionStep
                        default:
                            completionStep
                        }
                    }
                    .transition(.asymmetric(
                        insertion: .move(edge: .trailing).combined(with: .opacity),
                        removal: .move(edge: .leading).combined(with: .opacity)
                    ))
                    
                    Spacer()
                    
                    // Navigation buttons
                    navigationButtons
                        .padding(.bottom, 32)
                }
                .padding(.horizontal, 32)
            }
            .navigationBarHidden(true)
        }
        .sheet(isPresented: $showAzureSettings) {
            AzureSettingsSheet(onClose: { 
                showAzureSettings = false
                Task {
                    await model.refreshAzureConfiguration()
                    checkTtsConfiguration()
                    if currentStep == 1 && selectedUseSystemTts == false && model.azureConfigured {
                        withAnimation(.easeInOut(duration: 0.4)) {
                            currentStep = 2
                        }
                    }
                }
            })
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
                            showLanguagePicker = true
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
        .onAppear {
            checkTtsConfiguration()
            selectedVoice = model.selectedVoice
            selectedUseSystemTts = model.useSystemTts
        }
    }
    
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
        }
    }
    
    @ViewBuilder
    private var ttsSetupStep: some View {
        VStack(spacing: 24) {
            Image(systemName: "gear")
                .font(.system(size: 60))
                .foregroundColor(.blue)
            
            Text(NSLocalizedString("welcome_flow.tts_setup.title", comment: ""))
                .font(.title)
                .fontWeight(.bold)
            
            Text(NSLocalizedString("welcome_flow.tts_setup.subtitle", comment: ""))
                .font(.title3)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
            
            VStack(spacing: 16) {
                // System TTS Option
                TtsOptionCard(
                    title: NSLocalizedString("welcome_flow.system_tts.title", comment: ""),
                    subtitle: NSLocalizedString("welcome_flow.system_tts.subtitle", comment: ""),
                    icon: "speaker.wave.2.fill",
                    pros: [
                        NSLocalizedString("welcome_flow.system_tts.pro1", comment: ""),
                        NSLocalizedString("welcome_flow.system_tts.pro2", comment: ""),
                        NSLocalizedString("welcome_flow.system_tts.pro3", comment: "")
                    ],
                    cons: [
                        NSLocalizedString("welcome_flow.system_tts.con1", comment: ""),
                        NSLocalizedString("welcome_flow.system_tts.con2", comment: "")
                    ],
                    isSelected: model.useSystemTts,
                    action: {
                        model.setUseSystemTts(true)
                        selectedUseSystemTts = true
                        hasConfiguredTts = true
                        withAnimation(.easeInOut(duration: 0.4)) {
                            currentStep = 2
                        }
                    }
                )
                
                // Azure Option
                TtsOptionCard(
                    title: NSLocalizedString("welcome_flow.azure_tts.title", comment: ""),
                    subtitle: NSLocalizedString("welcome_flow.azure_tts.subtitle", comment: ""),
                    icon: "cloud.fill",
                    pros: [
                        NSLocalizedString("welcome_flow.azure_tts.pro1", comment: ""),
                        NSLocalizedString("welcome_flow.azure_tts.pro2", comment: ""),
                        NSLocalizedString("welcome_flow.azure_tts.pro3", comment: "")
                    ],
                    cons: [
                        NSLocalizedString("welcome_flow.azure_tts.con1", comment: ""),
                        NSLocalizedString("welcome_flow.azure_tts.con2", comment: ""),
                        NSLocalizedString("welcome_flow.azure_tts.con3", comment: "")
                    ],
                    isSelected: !model.useSystemTts,
                    action: {
                        model.setUseSystemTts(false)
                        selectedUseSystemTts = false
                        if model.azureConfigured {
                            hasConfiguredTts = true
                            withAnimation(.easeInOut(duration: 0.4)) {
                                currentStep = 2
                            }
                        } else {
                            showAzureSettings = true
                        }
                    }
                )
            }
        }
    }
    
    @ViewBuilder
    private var voiceSelectionStep: some View {
        VStack(spacing: 24) {
            Image(systemName: "person.wave.2.fill")
                .font(.system(size: 60))
                .foregroundColor(.blue)
            
            Text(NSLocalizedString("welcome_flow.voice_setup.title", comment: ""))
                .font(.title)
                .fontWeight(.bold)
            
            if model.useSystemTts {
                VStack(spacing: 16) {
                    Text(NSLocalizedString("welcome_flow.system_tts_info", comment: ""))
                        .font(.title3)
                        .foregroundColor(.secondary)
                    
                    Text(NSLocalizedString("welcome_flow.system_tts_settings_info", comment: ""))
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
                    
                    // Voice preview
                    VoicePreviewCard(
                        title: NSLocalizedString("welcome_flow.test_voice", comment: ""),
                        previewText: $previewText,
                        onSpeak: { model.speak(previewText) },
                        onStop: { model.stopTts() }
                    )
                }
            } else {
                VStack(spacing: 16) {
                    Text(NSLocalizedString("welcome_flow.azure_voices_subtitle", comment: ""))
                        .font(.title3)
                        .foregroundColor(.secondary)
                    
                    if let voice = selectedVoice {
                        SelectedVoiceCard(voice: voice, onSpeak: { model.speak(previewText) })
                    }

                    if pendingLanguageSelection {
                        VStack(spacing: 8) {
                            Text("welcome_flow.choose_primary_language")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Button("welcome_flow.select_language") {
                                showLanguagePicker = true
                            }
                            .buttonStyle(.borderedProminent)
                        }
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
                    
                    if selectedVoice != nil {
                        VoicePreviewCard(
                            title: NSLocalizedString("welcome_flow.test_voice", comment: ""),
                            previewText: $previewText,
                            onSpeak: { model.speak(previewText) },
                            onStop: { model.stopTts() }
                        )
                    }
                }
            }
        }
    }
    
    @ViewBuilder
    private var completionStep: some View {
        VStack(spacing: 24) {
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
                    Text(NSLocalizedString("welcome_flow.help.add_phrases", comment: ""))
                    Spacer()
                }
                HStack {
                    Image(systemName: "play.circle.fill")
                    Text(NSLocalizedString("welcome_flow.help.play_phrases", comment: ""))
                    Spacer()
                }
                HStack {
                    Image(systemName: "folder.fill")
                    Text(NSLocalizedString("welcome_flow.help.organize", comment: ""))
                    Spacer()
                }
            }
            .font(.subheadline)
            .foregroundColor(.secondary)
            .padding(.horizontal)
        }
    }

    @ViewBuilder
    private var navigationButtons: some View {
        HStack(spacing: 16) {
            if currentStep > 0 {
                Button(NSLocalizedString("welcome_flow.back", comment: "")) {
                    withAnimation(.easeInOut(duration: 0.4)) {
                        currentStep -= 1
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
                } else if currentStep == 1 {
                    if selectedUseSystemTts == true {
                        withAnimation(.easeInOut(duration: 0.4)) {
                            currentStep = 2
                        }
                    } else if selectedUseSystemTts == false {
                        if model.azureConfigured {
                            withAnimation(.easeInOut(duration: 0.4)) {
                                currentStep = 2
                            }
                        } else {
                            showAzureSettings = true
                        }
                    }
                } else if canProceed {
                    withAnimation(.easeInOut(duration: 0.4)) {
                        currentStep += 1
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
        case 1: return selectedUseSystemTts != nil
        case 2: return model.useSystemTts || (selectedVoice != nil && !pendingLanguageSelection)
        default: return true
        }
    }
    
    private func checkTtsConfiguration() {
        hasConfiguredTts = model.useSystemTts || model.azureConfigured
        selectedUseSystemTts = model.useSystemTts
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
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Image(systemName: icon)
                        .font(.title2)
                        .foregroundColor(.blue)
                    
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title)
                            .font(.headline)
                            .foregroundColor(.primary)
                        Text(subtitle)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                    
                    if isSelected {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                            .font(.title2)
                    }
                }
                
                HStack(alignment: .top, spacing: 16) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("welcome_flow.pros")
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(.green)
                        ForEach(pros, id: \.self) { pro in
                            HStack {
                                Image(systemName: "plus.circle")
                                    .font(.caption)
                                    .foregroundColor(.green)
                                Text(pro)
                                    .font(.caption)
                            }
                        }
                    }
                    
                    VStack(alignment: .leading, spacing: 4) {
                        Text("welcome_flow.cons")
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(.orange)
                        ForEach(cons, id: \.self) { con in
                            HStack {
                                Image(systemName: "minus.circle")
                                    .font(.caption)
                                    .foregroundColor(.orange)
                                Text(con)
                                    .font(.caption)
                            }
                        }
                    }
                    
                    Spacer()
                }
            }
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.systemBackground))
                    .shadow(color: .black.opacity(0.1), radius: 4, x: 0, y: 2)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 2)
                    )
            )
        }
        .buttonStyle(PlainButtonStyle())
    }
}

struct SelectedVoiceCard: View {
    let voice: Shared.Voice
    let onSpeak: () -> Void
    
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(voice.displayName ?? voice.name ?? NSLocalizedString("common.unknown", comment: ""))
                    .font(.headline)
                Text(voice.primaryLanguage ?? NSLocalizedString("common.unknown_language", comment: ""))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            Button(action: onSpeak) {
                Image(systemName: "play.circle.fill")
                    .font(.title2)
                    .foregroundColor(.blue)
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

struct VoicePreviewCard: View {
    let title: String
    @Binding var previewText: String
    let onSpeak: () -> Void
    let onStop: () -> Void
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
            
            TextField("welcome_flow.preview_text.placeholder", text: $previewText)
                .textFieldStyle(RoundedBorderTextFieldStyle())
            
            HStack {
                Button(action: onSpeak) {
                    HStack {
                        Image(systemName: "play.fill")
                        Text("playback.play")
                    }
                    .font(.subheadline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color.green)
                    .cornerRadius(8)
                }
                
                Button(action: onStop) {
                    HStack {
                        Image(systemName: "stop.fill")
                        Text("playback.stop")
                    }
                    .font(.subheadline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color.red)
                    .cornerRadius(8)
                }
                
                Spacer()
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.secondarySystemBackground))
        )
    }
}
