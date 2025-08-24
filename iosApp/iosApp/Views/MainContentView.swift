import SwiftUI
import Shared

struct MainContentView: View {
    @ObservedObject var model: IosViewModel
    let recorder: AudioRecorder
    @Binding var recordingForPhraseId: String?
    @Binding var editingPhrase: Shared.Phrase?
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
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.showOfflineInfoOnce {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top) {
                        Image(systemName: "info.circle.fill").foregroundStyle(.blue)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("You are offline").bold()
                            Text("Azure voices won’t work offline. Enable System TTS to use on-device speech while offline.")
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
                    Toggle("Use System TTS when offline", isOn: Binding(
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

            // Input field
            MultiLineInput(text: $model.input,
                           placeholder: NSLocalizedString("tts.placeholder", comment: ""),
                           fontSize: CGFloat(uiInputFontSize),
                           minHeight: CGFloat(uiTextFieldHeight))

            // Categories Row
            CategoriesRowView(
                state: model.state,
                chipFontSize: CGFloat(uiChipFontSize),
                chipHPadding: chipHPadding,
                chipVPadding: chipVPadding,
                onSelect: { id in model.selectCategory(id: id) },
                onDelete: { id in model.deleteCategory(id: id) }
            )

            // Grid of phrases
            PhrasesGridView(columns: columns,
                             phrases: currentPhrases,
                             onAdd: { showAddPhrase = true },
                             onItemFramesChange: { frames in itemFrames = frames }) { p in
                phraseCell(for: p)
            }

            // Playback controls
            HStack(spacing: 20) {
                Button(action: { model.speak(model.input) }) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: CGFloat(uiPlayIconSize)))
                }
                .buttonStyle(.plain)
                Button(action: { model.pauseTts() }) {
                    Image(systemName: "pause.circle")
                        .font(.system(size: CGFloat(uiPlayIconSize - 4)))
                }
                .buttonStyle(.plain)
                Button(action: { model.stopTts() }) {
                    Image(systemName: "stop.circle")
                        .font(.system(size: CGFloat(uiPlayIconSize - 4)))
                }
                .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
        }
    }
}
