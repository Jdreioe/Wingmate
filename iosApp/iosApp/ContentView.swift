import SwiftUI
import Shared
import AVFoundation
import UIKit

struct ContentView: View {
    @StateObject private var model = IosViewModel()
    @State private var showVoiceSheet = false
    @State private var showLanguageSheet = false
    @State private var showWelcomeFlow = false
    @State private var hasCompletedWelcome = UserDefaults.standard.bool(forKey: "welcome_flow_completed")
    @State private var isWideLayout = true // Track layout mode
    @State private var showAddCategory = false
    @State private var showAddPhrase = false
    @State private var showUiSizeSheet = false
    @State private var editingPhrase: Shared.Phrase? = nil

    // Runtime pieces
    @State private var recorder = AudioRecorder()
    @State private var recordingForPhraseId: String? = nil

    // Reorder state
    @State private var wiggleMode = false
    @State private var gridLocal: [Shared.Phrase] = []
    @State private var draggingId: String? = nil
    @State private var draggingOffset: CGSize = .zero
    @State private var dragStartFrame: CGRect? = nil
    @State private var itemFrames: [String: CGRect] = [:]

    // UI size controls (persisted)
    @AppStorage("ui_textFieldHeight") private var uiTextFieldHeight: Double = 72
    @AppStorage("ui_inputFontSize") private var uiInputFontSize: Double = 20
    @AppStorage("ui_chipFontSize") private var uiChipFontSize: Double = 18
    @AppStorage("ui_playIconSize") private var uiPlayIconSize: Double = 36

    // Derived paddings based on chip font
    private var chipHPadding: CGFloat { CGFloat(max(12, uiChipFontSize * 0.75)) }
    private var chipVPadding: CGFloat { CGFloat(max(8, uiChipFontSize * 0.45)) }

    // iPad right settings panel visibility
    @State private var showRightPanel: Bool = UIDevice.current.userInterfaceIdiom == .pad
    // If we hid the right panel due to narrow width, remember to restore on wide
    @State private var autoCollapsedRightPanel: Bool = false

    private var shouldShowWelcomeFlow: Bool {
        return showWelcomeFlow || (!hasCompletedWelcome && model.selectedVoice == nil)
    }

    @ViewBuilder
    private func mainContent(columns: [GridItem]) -> some View {
        MainContentView(
            model: model,
            recorder: recorder,
            recordingForPhraseId: $recordingForPhraseId,
            editingPhrase: $editingPhrase,
            showAddPhrase: $showAddPhrase,
            wiggleMode: $wiggleMode,
            gridLocal: $gridLocal,
            draggingId: $draggingId,
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
            showVoiceSheet: $showVoiceSheet,
            showLanguageSheet: $showLanguageSheet,
            showAddCategory: $showAddCategory,
            showAddPhrase: $showAddPhrase,
            showUiSizeSheet: $showUiSizeSheet,
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
                            let currentWideLayout = width > wideThreshold // Use width threshold tuned for iPad
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
                                                    openWelcomeFlow: { showWelcomeFlow = true }
                                                )
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
                                        // Prediction bar (iPhone/Narrow layout)
                                        if !model.predictions.words.isEmpty || !model.predictions.letters.isEmpty {
                                            PredictionBar(
                                                result: model.predictions,
                                                onWordSelected: { model.applyWordPrediction($0) },
                                                onLetterSelected: { model.applyLetterPrediction($0) },
                                                fontSizeScale: 1.0 // Adjust if needed
                                            )
                                            .transition(.move(edge: .top).combined(with: .opacity))
                                        }
                                            VStack(spacing: 0) {
                                        // Prediction bar (iPhone/Narrow layout)
                                        if !model.predictions.words.isEmpty || !model.predictions.letters.isEmpty {
                                            PredictionBar(
                                                result: model.predictions,
                                                onWordSelected: { model.applyWordPrediction($0) },
                                                onLetterSelected: { model.applyLetterPrediction($0) },
                                                fontSizeScale: 1.0 // Adjust if needed
                                            )
                                            .transition(.move(edge: .top).combined(with: .opacity))
                                        }
                                        
                                        mainContent(columns: cols)
                                            .padding(16)
                                    }
                                    .onAppear {
                                        isWideLayout = false
                                        showRightPanel = false
                                        autoCollapsedRightPanel = true
                                    }
                                }
                            }
                            .onChange(of: currentWideLayout) { wide in
                                isWideLayout = wide
                                if wide {
                                    if autoCollapsedRightPanel {
                                        showRightPanel = true
                                        autoCollapsedRightPanel = false
                                    }
                                } else {
                                    if showRightPanel {
                                        showRightPanel = false
                                        autoCollapsedRightPanel = true
                                    }
                                }
                            }
                        }
                    }
                }
                .background(Color(.systemBackground))
                .navigationTitle(Text("app.title"))
                .toolbar {
                    // Hide top navigation buttons when showing the welcome flow
                    if !shouldShowWelcomeFlow {
                        ToolbarItemGroup(placement: .topBarTrailing) {
                            // Welcome flow restart button
                            Button(action: { showWelcomeFlow = true }) {
                                Image(systemName: "questionmark.circle")
                                    .accessibilityLabel(Text("toolbar.welcome_flow"))
                            }
                            
                            // Show settings panel toggle only in wide layout
                            if isWideLayout {
                                Button(action: {
                                    withAnimation(.spring(response: 0.5, dampingFraction: 0.85, blendDuration: 0.1)) {
                                        showRightPanel.toggle()
                                    }
                                }) {
                                    Image(systemName: "sidebar.right")
                                        .accessibilityLabel(Text("toolbar.settings_panel"))
                                }
                            }
                            
                            if wiggleMode {
                                Button("common.done") { 
                                    withAnimation(.easeOut(duration: 0.3)) {
                                        wiggleMode = false
                                        draggingId = nil
                                        draggingOffset = .zero
                                        dragStartFrame = nil
                                        gridLocal.removeAll()
                                    }
                                }
                                .foregroundStyle(.red)
                                .bold()
                            } else {
                                Button(action: {
                                    withAnimation(.easeInOut(duration: 0.2)) {
                                        wiggleMode = true
                                        gridLocal = model.filteredPhrases
                                        // Reset any drag state when entering wiggle mode
                                        draggingId = nil
                                        draggingOffset = .zero
                                        dragStartFrame = nil
                                    }
                                }) {
                                    Image(systemName: "square.grid.3x3.fill")
                                        .accessibilityLabel(Text("toolbar.wiggle_mode"))
                                }
                            }
                            
                            // Show these buttons when NOT in wide layout or when right panel is hidden
                            if !isWideLayout || !showRightPanel {
                                Button(action: { showLanguageSheet = true }) {
                                    Label("toolbar.language", systemImage: "globe")
                                }
                                Button(action: { showVoiceSheet = true }) {
                                    Image(systemName: "gearshape").accessibilityLabel(Text("toolbar.voice"))
                                }
                                Button(action: { showUiSizeSheet = true }) {
                                    Image(systemName: "textformat.size").accessibilityLabel(Text("toolbar.ui_size"))
                                }
                            }
                            
                            Button(action: { showAddCategory = true }) {
                                Image(systemName: "folder.badge.plus").accessibilityLabel(Text("toolbar.add_category"))
                            }
                        }
                    }
                }
                #if DEBUG
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("DEBUG: ContentView loaded")
                        if let v = model.selectedVoice {
                            let name = (v.displayName ?? v.name) ?? "—"
                            let lang = (v.selectedLanguage.isEmpty ? v.primaryLanguage : v.selectedLanguage) ?? "-"
                            Text("Voice: \(name) [\(lang)]")
                        } else {
                            Text("Voice: (none)")
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

    // Compute the nearest phrase ID by Euclidean distance to a point in gridSpace
    private func nearestId(to point: CGPoint, excluding: String) -> String? {
        var best: (String, CGFloat)? = nil
        for (id, frame) in itemFrames where id != excluding {
            let center = CGPoint(x: frame.midX, y: frame.midY)
            let dx = center.x - point.x
            let dy = center.y - point.y
            let d2 = dx*dx + dy*dy
            if best == nil || d2 < best!.1 { best = (id, d2) }
        }
        return best?.0
    }

    // Commit a local move to the shared store using global indices
    private func commitMove(movingId: String, toLocalIndex: Int) {
        let all = model.state.phrases
        let localIds = gridLocal.map { $0.id }
        let targetId = localIds[min(max(0, toLocalIndex), localIds.count-1)]
        guard let fromGlobal = all.firstIndex(where: { $0.id == movingId }) else { return }
        let toGlobal = all.firstIndex(where: { $0.id == targetId }) ?? fromGlobal
        if fromGlobal != toGlobal {
            model.movePhrase(from: fromGlobal, to: toGlobal)
        }
    }
 
struct ContentView_Previews: PreviewProvider {
    static var previews: some View { ContentView() }
}
}
