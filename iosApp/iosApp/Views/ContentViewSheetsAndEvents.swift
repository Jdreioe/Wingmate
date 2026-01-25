import SwiftUI
import UIKit
import Shared

struct ContentViewSheetsAndEvents<Content: View>: View {
    @ObservedObject var model: IosViewModel
    @Binding var showVoiceSheet: Bool
    @Binding var showLanguageSheet: Bool
    @Binding var showAddCategory: Bool
    @Binding var showAddPhrase: Bool
    @Binding var showUiSizeSheet: Bool
    @Binding var showPronunciationSheet: Bool
    @Binding var editingPhrase: Shared.Phrase?
    let uiTextFieldHeight: Binding<Double>
    let uiInputFontSize: Binding<Double>
    let uiChipFontSize: Binding<Double>
    let uiPlayIconSize: Binding<Double>
    // Recording surface
    var recorder: AudioRecorder? = nil
    var saveRecordingPath: ((String,String) -> Void)? = nil
    let content: () -> Content

    var body: some View {
        // Determine when to show modal sheets: only on narrow/compact or iPhone
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        content()
            .sheet(isPresented: Binding(get: { !isPad && showVoiceSheet }, set: { if !$0 { showVoiceSheet = false } })) {
                VoiceSelectionSheet(selected: model.selectedVoice, onClose: { showVoiceSheet = false }) { v in
                    Task {
                        await model.chooseVoice(v)
                        await MainActor.run { showVoiceSheet = false }
                    }
                }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .sheet(isPresented: Binding(get: { !isPad && showLanguageSheet }, set: { if !$0 { showLanguageSheet = false } })) {
                LanguageSelectionSheet(languages: model.availableLanguages.isEmpty ? [model.primaryLanguage] : model.availableLanguages,
                                       selected: model.primaryLanguage,
                                       onClose: { showLanguageSheet = false }) { lang in
                    model.updateLanguage(lang)
                    showLanguageSheet = false
                }
                .presentationDetents([.height(300), .medium, .large])
                .presentationDragIndicator(.visible)
            }
            .sheet(isPresented: $showAddCategory) {
                AddCategorySheet(onClose: { showAddCategory = false }) { name in
                    model.addCategory(name: name)
                    showAddCategory = false
                }
                .presentationDetents([.height(200), .medium])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(20)
            }
            .sheet(isPresented: $showAddPhrase) {
                AddPhraseSheet(onClose: { showAddPhrase = false }, recorder: recorder, saveRecordingPath: saveRecordingPath) { text in
                    model.addPhrase(text: text)
                    showAddPhrase = false
                }
                .presentationDetents([.height(400), .medium, .large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(20)
            }
            .sheet(isPresented: Binding(get: { !isPad && showUiSizeSheet }, set: { if !$0 { showUiSizeSheet = false } })) {
                UiSizeSheet(onClose: { showUiSizeSheet = false },
                            uiTextFieldHeight: uiTextFieldHeight,
                            uiInputFontSize: uiInputFontSize,
                            uiChipFontSize: uiChipFontSize,
                            uiPlayIconSize: uiPlayIconSize)
                .presentationDetents([.height(350), .medium, .large])
                .presentationDragIndicator(.visible)
            }
            .sheet(isPresented: $showPronunciationSheet) {
                NavigationStack {
                    PronunciationDictionaryView(model: model)
                }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .sheet(isPresented: Binding(get: { editingPhrase != nil }, set: { if !$0 { editingPhrase = nil } })) {
                if let phrase = editingPhrase {
                    EditPhraseSheet(
                        phrase: phrase,
                        onClose: { editingPhrase = nil },
                        onSave: { updatedText, updatedName in
                            model.updatePhrase(id: phrase.id, text: updatedText, name: updatedName)
                            editingPhrase = nil
                        },
                        recorder: recorder,
                        saveRecordingPath: saveRecordingPath
                    )
                    .presentationDetents([.height(450), .medium, .large])
                    .presentationDragIndicator(.visible)
                    .presentationCornerRadius(20)
                }
            }
            .onAppear {
                Task { await model.start() }
            }
            .onChange(of: model.selectedVoice?.name ?? String()) { _, _ in
                #if DEBUG
                let v = model.selectedVoice
                let name = (v?.displayName ?? v?.name) ?? "—"
                let lang = v.map { model.effectiveLanguage(for: $0) } ?? "-"
                print("DEBUG: Selected voice \(name) [\(lang)]")
                #endif
            }
            .onChange(of: model.primaryLanguage) { _, lang in
                #if DEBUG
                print("DEBUG: Primary language \(lang)")
                #endif
            }
    }
}
