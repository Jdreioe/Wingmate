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
            uiPlayIconSize: $uiPlayIconSize
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
                            let minTile: CGFloat = isPad ? 200 : 140
                            let spacing: CGFloat = isPad ? 12 : 8
                            let rightPanelWidth: CGFloat = (isPad && showRightPanel) ? min(340, max(280, width * 0.28)) : 0
                            let contentWidth = isPad ? max(0, width - rightPanelWidth) : width
                            let available = max(contentWidth - spacing, minTile)
                            let count = max(2, Int((available + spacing) / (minTile + spacing)))
                            let cols = Array(repeating: GridItem(.flexible(), spacing: spacing), count: count)

                            if isPad {
                                HStack(spacing: 0) {
                                    mainContent(columns: cols)
                                        .frame(width: contentWidth)
                                        .padding(24)
                                    if showRightPanel {
                                        RightSettingsPanel(
                                            model: model,
                                            uiTextFieldHeight: $uiTextFieldHeight,
                                            uiInputFontSize: $uiInputFontSize,
                                            uiChipFontSize: $uiChipFontSize,
                                            uiPlayIconSize: $uiPlayIconSize,
                                            openVoicePicker: { showVoiceSheet = true },
                                            openWelcomeFlow: { showWelcomeFlow = true }
                                        )
                                        .frame(width: rightPanelWidth)
                                    }
                                }
                            } else {
                                mainContent(columns: cols)
                                    .padding(16)
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
                            
                            // iPad: toggle the right settings panel, and hide voice/language/ui-size buttons
                            if UIDevice.current.userInterfaceIdiom == .pad {
                                Button(action: { showRightPanel.toggle() }) {
                                    Image(systemName: "sidebar.right")
                                        .accessibilityLabel(Text("toolbar.settings_panel"))
                                }
                            }
                            if wiggleMode {
                                Button("common.done") { wiggleMode = false; gridLocal.removeAll(); draggingId = nil; draggingOffset = .zero }
                            } else {
                                Button(action: {
                                    wiggleMode = true
                                    gridLocal = model.filteredPhrases
                                }) {
                                    Image(systemName: "square.grid.3x3.fill")
                                        .accessibilityLabel(Text("toolbar.wiggle_mode"))
                                }
                            }
                            if UIDevice.current.userInterfaceIdiom != .pad {
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
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View { ContentView() }
}


