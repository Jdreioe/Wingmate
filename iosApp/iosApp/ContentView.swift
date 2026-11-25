import SwiftUI
import Shared
import AVFoundation
import UIKit

struct ContentView: View {
    @StateObject private var model = IosViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showVoiceSheet = false
    @State private var showLanguageSheet = false
    @State private var showSecondaryLanguageSheet = false
    @State private var showWelcomeFlow = false
    @State private var hasCompletedWelcome = UserDefaults.standard.bool(forKey: "welcome_flow_completed")
    @State private var showAddCategory = false
    @State private var showAddPhrase = false
    @State private var showUiSizeSheet = false
    @State private var showPronunciationSheet = false
    @State private var showSettingsPanel = false
    @State private var editingPhrase: Shared.Phrase? = nil
    @State private var restBannerVisible = false
    @State private var isEditingScreen = false
    @State private var isMessageFullscreen = false
    @State private var partnerDisplayAvailable = false
    @State private var partnerDisplayMuted = false
    @State private var hingePosture: DuoHingePosture = .unavailable
    @State private var restingWindowHeight: CGFloat = 0

    @State private var recorder = AudioRecorder()
    @State private var recordingForPhraseId: String? = nil

    @AppStorage("ui_textFieldHeight") private var uiTextFieldHeight: Double = 72
    @AppStorage("ui_inputFontSize") private var uiInputFontSize: Double = 20
    @AppStorage("ui_chipFontSize") private var uiChipFontSize: Double = 18
    @AppStorage("ui_playIconSize") private var uiPlayIconSize: Double = 36
    @AppStorage("partner_display_mode") private var partnerDisplayModeRaw: String = PartnerDisplayMode.whileCommunicating.rawValue

    private var shouldShowWelcomeFlow: Bool {
        showWelcomeFlow || !hasCompletedWelcome
    }

    private var partnerDisplayMode: PartnerDisplayMode {
        PartnerDisplayMode(rawValue: partnerDisplayModeRaw) ?? .whileCommunicating
    }

    private var isConfiguringOrUnlocking: Bool {
        showSettingsPanel || showVoiceSheet || showLanguageSheet || showSecondaryLanguageSheet
            || showAddCategory || showAddPhrase || showUiSizeSheet || showPronunciationSheet
            || editingPhrase != nil
    }

    private var shouldShowPartnerDisplay: Bool {
        guard !partnerDisplayMuted else { return false }
        let isSpeakingOrActive = model.playback.name != "Idle"
        return PartnerDisplayPolicy.shouldShow(
            mode: partnerDisplayMode,
            inWelcomeFlow: shouldShowWelcomeFlow,
            isConfiguringOrUnlocking: isConfiguringOrUnlocking,
            isEditingScreen: isEditingScreen,
            inputIsPaused: model.inputIsPaused,
            hingePosture: hingePosture,
            messageText: model.input,
            hasMessageParts: !model.messageParts.isEmpty,
            isSpeakingOrActive: isSpeakingOrActive,
            isFullscreenMessage: isMessageFullscreen
        )
    }

    @ViewBuilder
    private var mainContent: some View {
        if model.boardModeEnabled {
            SymbolBoardWorkspaceView(
                model: model,
                isEditingScreen: $isEditingScreen
            ) {
                withAnimation(.spring(response: 0.45, dampingFraction: 0.85, blendDuration: 0.1)) {
                    showSettingsPanel = true
                }
            }
        } else {
            TypingWorkspaceView(
                model: model,
                recorder: recorder,
                editingPhrase: $editingPhrase,
                showAddCategory: $showAddCategory,
                showAddPhrase: $showAddPhrase,
                isMessageFullscreen: $isMessageFullscreen,
                uiInputFontSize: uiInputFontSize,
                uiTextFieldHeight: uiTextFieldHeight,
                uiChipFontSize: uiChipFontSize,
                uiPlayIconSize: uiPlayIconSize,
                restingWindowHeight: restingWindowHeight,
                requestMicAndStart: requestMicAndStart
            )
        }
    }

    var body: some View {
        ContentViewSheetsAndEvents(
            model: model,
            showWelcomeFlow: $showWelcomeFlow,
            showVoiceSheet: $showVoiceSheet,
            showLanguageSheet: $showLanguageSheet,
            showSecondaryLanguageSheet: $showSecondaryLanguageSheet,
            showAddCategory: $showAddCategory,
            showAddPhrase: $showAddPhrase,
            showUiSizeSheet: $showUiSizeSheet,
            showPronunciationSheet: $showPronunciationSheet,
            showSettingsPanel: $showSettingsPanel,
            editingPhrase: $editingPhrase,
            uiTextFieldHeight: $uiTextFieldHeight,
            uiInputFontSize: $uiInputFontSize,
            uiChipFontSize: $uiChipFontSize,
            uiPlayIconSize: $uiPlayIconSize,
            recorder: recorder,
            saveRecordingPath: { phraseId, path in
                model.setRecordingPath(path, for: phraseId)
            }
        ) {
            NavigationStack {
                Group {
                    if shouldShowWelcomeFlow {
                        WelcomeFlow(
                            model: model,
                            onComplete: {
                                model.setStartupUsesScreens(model.boardModeEnabled)
                                hasCompletedWelcome = true
                                showWelcomeFlow = false
                                UserDefaults.standard.set(true, forKey: "welcome_flow_completed")
                            },
                            onSkip: {
                                model.setStartupUsesScreens(model.boardModeEnabled)
                                hasCompletedWelcome = true
                                showWelcomeFlow = false
                                UserDefaults.standard.set(true, forKey: "welcome_flow_completed")
                            },
                            onRestoreComplete: {
                                hasCompletedWelcome = true
                                showWelcomeFlow = false
                                UserDefaults.standard.set(true, forKey: "welcome_flow_completed")
                            }
                        )
                    } else {
                        GeometryReader { proxy in
                            let widePadding = WindowLayoutClass.of(width: proxy.size.width).isLarge

                            VStack(spacing: model.boardModeEnabled ? 0 : 8) {
                                CommunicationNoticeBanner(model: model)
                                    .padding(.horizontal, (model.boardModeEnabled || widePadding) ? 16 : 0)
                                    .padding(.top, model.boardModeEnabled ? 8 : 0)
                                mainContent
                            }
                            // Screens owns its full-screen edge-to-edge padding; Typing adapts to window size.
                            .padding(model.boardModeEnabled ? 0 : (widePadding ? 12 : 16))
                            .frame(width: proxy.size.width, height: proxy.size.height)
                        }
                        // Measure resting window height outside the keyboard-inset GeometryReader so
                        // raising the on-screen keyboard on iPad or unfolded Duo never shrinks it.
                        .background {
                            GeometryReader { restingProxy in
                                Color.clear
                                    .onAppear { restingWindowHeight = restingProxy.size.height }
                                    .onChange(of: restingProxy.size.height) { _, h in restingWindowHeight = h }
                            }
                            .ignoresSafeArea(.keyboard)
                        }
                    }
                }
                .background(Color(.systemBackground))
                .navigationTitle(Text(LocalizedStringKey(model.boardModeEnabled ? "symbol.workspace.title" : "app.title")))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    if !shouldShowWelcomeFlow {
                        if partnerDisplayAvailable && partnerDisplayMode != .off {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button(action: { partnerDisplayMuted.toggle() }) {
                                    Image(systemName: partnerDisplayMuted ? "rectangle.on.rectangle.slash" : "rectangle.on.rectangle")
                                        .accessibilityLabel(Text(LocalizedStringKey(
                                            partnerDisplayMuted ? "toolbar.partner_display.resume" : "toolbar.partner_display.pause"
                                        )))
                                }
                                .accessibilityHidden(model.scanningEnabled && !model.scanTopBarEnabled)
                            }
                        }

                        ToolbarItem(placement: .topBarTrailing) {
                            Button(action: {
                                withAnimation(.easeInOut(duration: 0.2)) {
                                    model.boardModeEnabled.toggle()
                                }
                            }) {
                                Image(systemName: model.boardModeEnabled ? "text.bubble" : "square.grid.3x3.fill")
                                    .accessibilityLabel(Text(LocalizedStringKey(model.boardModeEnabled ? "toolbar.switch_phrase_mode" : "toolbar.switch_symbol_mode")))
                            }
                            .accessibilityHidden(model.scanningEnabled && !model.scanTopBarEnabled)
                        }

                        ToolbarItem(placement: .topBarTrailing) {
                            Button(action: {
                                withAnimation(.spring(response: 0.45, dampingFraction: 0.85, blendDuration: 0.1)) {
                                    showSettingsPanel = true
                                }
                            }) {
                                Image(systemName: "slider.horizontal.3")
                                    .accessibilityLabel(Text("toolbar.settings_panel"))
                            }
                            .accessibilityHidden(model.scanningEnabled && !model.scanTopBarEnabled)
                        }
                    }
                }
            }
            .preferHorizontalToolbarOnDuo()
            .partnerDisplayAccessory(
                model: model,
                isEnabled: shouldShowPartnerDisplay,
                isAvailable: $partnerDisplayAvailable,
                hingePosture: $hingePosture
            )
            // Editing access relocks whenever Wingmate leaves the foreground, in either workspace.
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { model.lockEditingAccess() }
            }
            .onKeyPress(phases: [.down, .up]) { press -> KeyPress.Result in
                guard !shouldShowWelcomeFlow else { return .ignored }
                return model.accessKey(press.characters, isDown: press.phase == .down) ? .handled : .ignored
            }
            .task {
                while !Task.isCancelled {
                    if model.dwellToSelectMillis > 0 {
                        model.tickAccessInput()
                        try? await Task.sleep(nanoseconds: 16_000_000)
                    } else {
                        try? await Task.sleep(nanoseconds: 200_000_000)
                    }
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if !shouldShowWelcomeFlow && (model.dwellToSelectMillis > 0 || !model.selectKeyBinding.isEmpty) {
                    Button(action: model.toggleInputPause) {
                        Image(systemName: model.inputIsPaused ? "play.fill" : "pause.fill")
                            .font(.title2.weight(.semibold))
                            .frame(minWidth: 56, minHeight: 56)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.circle)
                    .padding(16)
                    .accessibilityLabel(Text(LocalizedStringKey(model.inputIsPaused ? "interaction.resume" : "interaction.rest_mode")))
                    .accessibilityAddTraits(.isButton)
                }
            }
            .overlay(alignment: .bottom) {
                if model.inputIsPaused && restBannerVisible {
                    HStack(spacing: 12) {
                        Text("interaction.paused_status")
                            .font(.subheadline.weight(.semibold))
                        Button(action: model.toggleInputPause) {
                            Text("interaction.resume")
                                .font(.subheadline.weight(.semibold))
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.accentColor, lineWidth: 2))
                    .padding(.bottom, 84)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .accessibilityAddTraits(.updatesFrequently)
                }
            }
            .task(id: model.inputIsPaused) {
                if model.inputIsPaused {
                    withAnimation(.easeInOut(duration: 0.2)) { restBannerVisible = true }
                    try? await Task.sleep(nanoseconds: 10_000_000_000)
                    withAnimation(.easeInOut(duration: 0.2)) { restBannerVisible = false }
                } else {
                    withAnimation(.easeInOut(duration: 0.2)) { restBannerVisible = false }
                }
            }
        }
    }

    private func requestMicAndStart(for phraseId: String) {
        func stopAndSaveCurrentRecording() {
            Task { @MainActor in
                guard let activePhraseId = recordingForPhraseId else { return }
                if let url = try? await recorder.stopRecording() {
                    model.setRecordingPath(url.path, for: activePhraseId)
                }
                recordingForPhraseId = nil
            }
        }

        if recorder.isRecording {
            // Tapping mic while recording toggles stop/save. If a different phrase is tapped,
            // stop current recording first and then begin for the new phrase.
            let currentId = recordingForPhraseId
            stopAndSaveCurrentRecording()
            if currentId == phraseId {
                return
            }
        }

        AVAudioApplication.requestRecordPermission { granted in
            if granted {
                Task { @MainActor in
                    recordingForPhraseId = phraseId
                    _ = try? await recorder.startRecording()
                }
            }
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View { ContentView() }
}
