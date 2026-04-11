import SwiftUI
import Shared
import AVFoundation
import UIKit

struct ContentView: View {
    @StateObject private var model = IosViewModel()
    @State private var showVoiceSheet = false
    @State private var showLanguageSheet = false
    @State private var showSecondaryLanguageSheet = false
    @State private var showWelcomeFlow = false
    @State private var hasCompletedWelcome = UserDefaults.standard.bool(forKey: "welcome_flow_completed")
    @State private var isWideLayout = true
    @State private var showAddCategory = false
    @State private var showAddPhrase = false
    @State private var showUiSizeSheet = false
    @State private var showPronunciationSheet = false
    @State private var showSettingsPanel = false
    @State private var editingPhrase: Shared.Phrase? = nil

    @State private var recorder = AudioRecorder()
    @State private var recordingForPhraseId: String? = nil

    @State private var wiggleMode = false
    @State private var gridLocal: [Shared.Phrase] = []
    @State private var draggingId: String? = nil
    @State private var draggingOffset: CGSize = .zero
    @State private var dragStartFrame: CGRect? = nil
    @State private var itemFrames: [String: CGRect] = [:]

    @AppStorage("ui_textFieldHeight") private var uiTextFieldHeight: Double = 72
    @AppStorage("ui_inputFontSize") private var uiInputFontSize: Double = 20
    @AppStorage("ui_chipFontSize") private var uiChipFontSize: Double = 18
    @AppStorage("ui_playIconSize") private var uiPlayIconSize: Double = 36

    private var chipHPadding: CGFloat { CGFloat(max(12, uiChipFontSize * 0.75)) }
    private var chipVPadding: CGFloat { CGFloat(max(8, uiChipFontSize * 0.45)) }

    @State private var showRightPanel: Bool = false
    @State private var autoCollapsedRightPanel: Bool = false

    private var shouldShowWelcomeFlow: Bool {
        showWelcomeFlow || !hasCompletedWelcome
    }

    @ViewBuilder
    private func mainContent(columns: [GridItem]) -> some View {
        MainContentView(
            model: model,
            recorder: recorder,
            recordingForPhraseId: $recordingForPhraseId,
            editingPhrase: $editingPhrase,
            showAddCategory: $showAddCategory,
            showAddPhrase: $showAddPhrase,
            wiggleMode: $wiggleMode,
            gridLocal: $gridLocal,
            draggingId: $draggingId,
            draggingOffset: $draggingOffset,
            dragStartFrame: $dragStartFrame,
            itemFrames: $itemFrames,
            columns: columns,
            uiInputFontSize: uiInputFontSize,
            uiTextFieldHeight: uiTextFieldHeight,
            uiChipFontSize: uiChipFontSize,
            chipHPadding: chipHPadding,
            chipVPadding: chipVPadding,
            uiPlayIconSize: uiPlayIconSize,
            requestMicAndStart: requestMicAndStart,
            commitMove: commitMove
        )
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
                                hasCompletedWelcome = true
                                showWelcomeFlow = false
                                UserDefaults.standard.set(true, forKey: "welcome_flow_completed")
                            },
                            onSkip: {
                                hasCompletedWelcome = true
                                showWelcomeFlow = false
                                UserDefaults.standard.set(true, forKey: "welcome_flow_completed")
                            }
                        )
                    } else {
                        GeometryReader { proxy in
                            let width = proxy.size.width
                            let isPad = UIDevice.current.userInterfaceIdiom == .pad
                            let wideThreshold: CGFloat = isPad ? 700 : 900
                            let currentWideLayout = width > wideThreshold
                            let minTile: CGFloat = isPad ? 200 : 140
                            let spacing: CGFloat = isPad ? 12 : 8
                            let rightPanelWidth: CGFloat = (currentWideLayout && showRightPanel) ? min(400, max(350, width * 0.35)) : 0
                            let dividerWidth: CGFloat = (currentWideLayout && showRightPanel) ? max(1.0 / UIScreen.main.scale, 1) : 0
                            let contentWidth = currentWideLayout ? max(0, width - rightPanelWidth - dividerWidth) : width
                            let available = max(contentWidth - spacing, minTile)
                            let count = max(2, Int((available + spacing) / (minTile + spacing)))
                            let cols = Array(repeating: GridItem(.flexible(), spacing: spacing), count: count)

                            Group {
                                if currentWideLayout {
                                    HStack(spacing: 0) {
                                        mainContent(columns: cols)
                                            .frame(width: contentWidth)
                                        if showRightPanel {
                                            HStack(spacing: 0) {
                                                Divider()
                                                RightSettingsPanel(
                                                    model: model,
                                                    uiTextFieldHeight: $uiTextFieldHeight,
                                                    uiInputFontSize: $uiInputFontSize,
                                                    uiChipFontSize: $uiChipFontSize,
                                                    uiPlayIconSize: $uiPlayIconSize,
                                                    openVoicePicker: { showVoiceSheet = true },
                                                    openWelcomeFlow: { showWelcomeFlow = true },
                                                    openPronunciation: { showPronunciationSheet = true }
                                                )
                                                .frame(width: rightPanelWidth)
                                            }
                                            .transition(.asymmetric(
                                                insertion: .move(edge: .trailing).combined(with: .opacity),
                                                removal: .move(edge: .trailing).combined(with: .opacity)
                                            ))
                                        }
                                    }
                                    .onAppear { isWideLayout = true }
                                    .animation(.spring(response: 0.45, dampingFraction: 0.85, blendDuration: 0.1), value: showRightPanel)
                                } else {
                                    VStack(spacing: 0) {
                                        mainContent(columns: cols)
                                            .padding(16)
                                    }
                                    .onAppear {
                                        isWideLayout = false
                                        if showRightPanel {
                                            showRightPanel = false
                                            autoCollapsedRightPanel = true
                                        }
                                    }
                                }
                            }
                            .onChange(of: currentWideLayout) { _, wide in
                                isWideLayout = wide
                                if wide {
                                    if autoCollapsedRightPanel {
                                        showRightPanel = true
                                        autoCollapsedRightPanel = false
                                    }
                                } else if showRightPanel {
                                    showRightPanel = false
                                    autoCollapsedRightPanel = true
                                }
                            }
                        }
                    }
                }
                .background(Color(.systemBackground))
                .navigationTitle(Text("app.title"))
                .toolbar {
                    if !shouldShowWelcomeFlow {
                        ToolbarItemGroup(placement: .topBarTrailing) {
                            Button(action: {
                                withAnimation(.spring(response: 0.45, dampingFraction: 0.85, blendDuration: 0.1)) {
                                    if isWideLayout {
                                        showRightPanel.toggle()
                                    } else {
                                        showSettingsPanel.toggle()
                                    }
                                }
                            }) {
                                Image(systemName: "slider.horizontal.3")
                                    .accessibilityLabel(Text("toolbar.settings_panel"))
                            }
                            .accessibilityHidden(model.scanningEnabled && !model.scanTopBarEnabled)
                        }
                    }
                }
                #if DEBUG
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("debug.content_loaded")
                        if let v = model.selectedVoice {
                            let name = (v.displayName ?? v.name) ?? "—"
                            let lang = (v.selectedLanguage.isEmpty ? v.primaryLanguage : v.selectedLanguage) ?? "-"
                            Text(String(format: NSLocalizedString("debug.voice_with_lang", comment: ""), name, lang))
                        } else {
                            Text("debug.voice.none")
                        }
                    }
                    .font(.caption2)
                    .padding(6)
                    .background(Color.yellow.opacity(0.3))
                    .cornerRadius(6)
                    .padding(8)
                }
                #endif
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

        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission { granted in
                if granted {
                    Task { @MainActor in
                        recordingForPhraseId = phraseId
                        _ = try? await recorder.startRecording()
                    }
                }
            }
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                if granted {
                    Task { @MainActor in
                        recordingForPhraseId = phraseId
                        _ = try? await recorder.startRecording()
                    }
                }
            }
        }
    }

    private func nearestId(to point: CGPoint, excluding: String) -> String? {
        var best: (String, CGFloat)? = nil
        for (id, frame) in itemFrames where id != excluding {
            let center = CGPoint(x: frame.midX, y: frame.midY)
            let dx = center.x - point.x
            let dy = center.y - point.y
            let d2 = dx * dx + dy * dy
            if best == nil || d2 < best!.1 { best = (id, d2) }
        }
        return best?.0
    }

    private func commitMove(movingId: String, toLocalIndex: Int) {
        let all = model.state.phrases
        let localIds = gridLocal.map { $0.id }
        guard !localIds.isEmpty else { return }
        let targetId = localIds[min(max(0, toLocalIndex), localIds.count - 1)]
        guard let fromGlobal = all.firstIndex(where: { $0.id == movingId }) else { return }
        let toGlobal = all.firstIndex(where: { $0.id == targetId }) ?? fromGlobal
        if fromGlobal != toGlobal {
            model.movePhrase(from: fromGlobal, to: toGlobal)
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View { ContentView() }
}

