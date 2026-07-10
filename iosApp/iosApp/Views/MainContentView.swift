import SwiftUI
import Shared

struct MainContentView: View {
    @ObservedObject var model: IosViewModel
    let recorder: AudioRecorder
    @Binding var recordingForPhraseId: String?
    @Binding var editingPhrase: Shared.Phrase?
    @Binding var showAddCategory: Bool
    @Binding var showAddPhrase: Bool
    @Binding var wiggleMode: Bool
    @Binding var gridLocal: [Shared.Phrase]
    @Binding var draggingId: String?
    @Binding var draggingOffset: CGSize
    @Binding var dragStartFrame: CGRect?
    @Binding var itemFrames: [String: CGRect]
    
    let columns: [GridItem]
    let uiInputFontSize: Double
    let uiTextFieldHeight: Double
    let uiChipFontSize: Double
    let chipHPadding: CGFloat
    let chipVPadding: CGFloat
    let uiPlayIconSize: Double
    
    let requestMicAndStart: (String) -> Void
    let commitMove: (String, Int) -> Void
    
    private var currentPhrases: [Shared.Phrase] { wiggleMode ? gridLocal : model.filteredPhrases }
    // Treat history as a separate feed of pseudo-phrases
    private var showingHistory: Bool {
        // We'll detect by comparing a local selection binding; use model.state plus a local flag passed from chip
        // MainContentView will maintain its own isHistorySelected state
        return isHistorySelected
    }
    @State private var isHistorySelected: Bool = false
    
    @ViewBuilder
    private func phraseCell(for p: Shared.Phrase) -> some View {
        ReorderablePhraseCell(
            model: model,
            phrase: p,
            recorder: recorder,
            recordingForPhraseId: $recordingForPhraseId,
            wiggleMode: $wiggleMode,
            gridLocal: $gridLocal,
            draggingId: $draggingId,
            draggingOffset: $draggingOffset,
            dragStartFrame: $dragStartFrame,
            itemFrames: itemFrames,
            onEdit: { editingPhrase = p },
            requestMic: { id in requestMicAndStart(id) },
            onDelete: { id in model.deletePhrase(id: id) },
            commitMove: { id, idx in commitMove(id, idx) }
        )
    }

    private var inputBinding: Binding<String> {
        Binding(
            get: { model.input },
            set: { newValue in
                model.onInputChanged(newValue)
            }
        )
    }

    private var hideInputFromScanning: Bool {
        model.scanningEnabled && !model.scanInputFieldEnabled
    }

    private var hideCategoriesFromScanning: Bool {
        model.scanningEnabled && !model.scanCategoryItemsEnabled
    }

    private var hideGridFromScanning: Bool {
        model.scanningEnabled && !model.scanPhraseGridEnabled
    }

    private var hidePlaybackFromScanning: Bool {
        model.scanningEnabled && !model.scanPlaybackAreaEnabled
    }

    @ViewBuilder
    private var predictionBar: some View {
        if !model.predictions.words.isEmpty || !model.predictions.letters.isEmpty {
            PredictionBar(
                result: model.predictions,
                onWordSelected: { model.applyWordPrediction($0) },
                onLetterSelected: { model.applyLetterPrediction($0) },
                fontSizeScale: 1.0
            )
        }
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Show reorder mode banner
            if wiggleMode {
                HStack {
                    Image(systemName: "hand.draw.fill")
                        .foregroundColor(.orange)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("reorder.banner.title")
                            .font(.headline)
                            .bold()
                        Text("reorder.banner.subtitle")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text("reorder.banner.done_hint")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(12)
                .background(Color.orange.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.orange.opacity(0.3), lineWidth: 1))
                .transition(.move(edge: .top).combined(with: .opacity))
            }
            
            if model.showOfflineInfoOnce {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top) {
                        Image(systemName: "info.circle.fill").foregroundStyle(.blue)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("offline.title").bold()
                            Text("offline.azure_unavailable_hint")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(action: { model.showOfflineInfoOnce = false }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                    Toggle("settings.tts.system_when_offline", isOn: Binding(
                        get: { model.useSystemTtsWhenOffline },
                        set: { model.setUseSystemTtsWhenOffline($0) }
                    ))
                    .toggleStyle(SwitchToggleStyle(tint: .accentColor))
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(.secondarySystemBackground)))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(.separator)))
            }
            if let err = model.state.error {
                HStack(spacing: 4) { Text("common.error"); Text(err) }.foregroundStyle(.red)
            }
            if model.state.isLoading { ProgressView().frame(maxWidth: .infinity, alignment: .center) }

            predictionBar

            if !model.sentencePhrases.isEmpty {
                SentenceBoxView(
                    phrases: model.sentencePhrases,
                    onDelete: { index in
                        model.removeSentencePhrase(at: index)
                    }
                )
                .accessibilityElement(children: .contain)
                .accessibilityHidden(hideInputFromScanning)
            }

            // Input field
            MultiLineInput(text: inputBinding,
                           selectedRange: Binding(
                               get: { model.inputSelectionRange },
                               set: { model.inputSelectionRange = $0 }
                           ),
                           placeholder: NSLocalizedString("tts.placeholder", comment: ""),
                           fontSize: CGFloat(uiInputFontSize),
                           minHeight: CGFloat(uiTextFieldHeight),
                           scanEnabled: model.scanningEnabled,
                           includeInScanArea: model.scanInputFieldEnabled,
                           secondaryLanguage: model.secondaryLanguage,
                           secondaryLanguageRanges: model.secondaryLanguageRanges,
                           allowsSecondaryLanguageAction: model.secondaryLanguage != model.primaryLanguage,
                           onTextEdited: { range, replacement in
                               model.adjustSecondaryLanguageRangesAfterEdit(range: range, replacementText: replacement)
                           },
                           onMarkSelectionAsSecondaryLanguage: { range in
                               model.markSelectionAsSecondaryLanguage(range: range)
                           })
            .accessibilityElement(children: .contain)
            .accessibilityHidden(hideInputFromScanning)

            // Categories Row
            CategoriesRowView(
                state: model.state,
                chipFontSize: CGFloat(uiChipFontSize),
                chipHPadding: chipHPadding,
                chipVPadding: chipVPadding,
                onSelect: { id in
                    if isHistorySelected { Task { await model.loadHistory() } }
                    isHistorySelected = false
                    model.selectCategory(id: id)
                },
                onDelete: { id in model.deleteCategory(id: id) },
                onAddCategory: { showAddCategory = true }
            ,
                showHistoryChip: !model.historyPhrases.isEmpty,
                isHistorySelected: isHistorySelected,
                onSelectHistory: {
                    isHistorySelected = true
                    // Keep store selection neutral so gridLocal calculations still work
                    model.selectCategory(id: model.historyCategoryId)
                    Task { await model.loadHistory() }
                }
            )
            .accessibilityElement(children: .contain)
            .accessibilityHidden(hideCategoriesFromScanning)

            // Grid of phrases
            let phrasesToShow = showingHistory ? model.historyPhrases : currentPhrases
            PhrasesGridView(columns: columns,
                            phrases: phrasesToShow,
                            onAdd: { showAddPhrase = true },
                            onItemFramesChange: { frames in itemFrames = frames },
                            cell: { p in phraseCell(for: p) },
                            isWiggleMode: wiggleMode,
                            hideAddButton: showingHistory,
                            scanEnabled: model.scanningEnabled,
                            includeInScanArea: model.scanPhraseGridEnabled,
                            scanOrder: model.scanPhraseGridOrder)
            .accessibilityElement(children: .contain)
            .accessibilityHidden(hideGridFromScanning)
            .overlay {
                if showingHistory && model.historyPhrases.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "clock.arrow.circlepath").font(.system(size: 32)).foregroundStyle(.secondary)
                        Text("history.empty").foregroundStyle(.secondary)
                    }
                    .padding(.top, 40)
                }
            }

            // Playback controls
            HStack(spacing: 20) {
                Button(action: { model.toggleHoldThatThought() }) {
                    Image(systemName: model.hasHeldThought ? "arrow.uturn.backward.circle.fill" : "arrow.down.circle.fill")
                        .font(.system(size: CGFloat(uiPlayIconSize)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(model.hasHeldThought ? "playback.restore_thought" : "playback.hold_thought"))
                .accessibilityHint(Text(model.hasHeldThought ? "accessibility.playback.restore_thought_hint" : "accessibility.playback.hold_thought_hint"))
                Button(action: { model.speak(model.input) }) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: CGFloat(uiPlayIconSize)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("playback.play"))
                .accessibilityHint(Text("accessibility.playback.play_hint"))
                Button(action: { model.pauseTts() }) {
                    Image(systemName: "pause.circle")
                        .font(.system(size: CGFloat(uiPlayIconSize)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("playback.pause"))
                .accessibilityHint(Text("accessibility.playback.pause_hint"))
                Button(action: { model.stopTts() }) {
                    Image(systemName: "stop.circle")
                        .font(.system(size: CGFloat(uiPlayIconSize)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("playback.stop"))
                .accessibilityHint(Text("accessibility.playback.stop_hint"))
                Button(action: { model.deleteText() }) {
                    Image(systemName: "trash.circle")
                        .font(.system(size: CGFloat(uiPlayIconSize)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("common.clear"))
                .accessibilityHint(Text("accessibility.playback.clear_hint"))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .accessibilityElement(children: .contain)
            .accessibilityHidden(hidePlaybackFromScanning)
        }
    }
}
