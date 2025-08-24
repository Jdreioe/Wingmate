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
    @Binding var editingPhrase: Shared.Phrase?
    @Binding var showReorderSheet: Bool
    let uiTextFieldHeight: Binding<Double>
    let uiInputFontSize: Binding<Double>
    let uiChipFontSize: Binding<Double>
    let uiPlayIconSize: Binding<Double>
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
            }
            .sheet(isPresented: Binding(get: { !isPad && showLanguageSheet }, set: { if !$0 { showLanguageSheet = false } })) {
                LanguageSelectionSheet(languages: model.availableLanguages.isEmpty ? [model.primaryLanguage] : model.availableLanguages,
                                       selected: model.primaryLanguage,
                                       onClose: { showLanguageSheet = false }) { lang in
                    model.updateLanguage(lang)
                    showLanguageSheet = false
                }
                .presentationDetents([.fraction(0.45), .large])
            }
            .sheet(isPresented: $showAddCategory) {
                AddCategorySheet(onClose: { showAddCategory = false }) { name in
                    model.addCategory(name: name)
                    showAddCategory = false
                }
                .presentationDetents([.fraction(0.3), .medium])
            }
            .sheet(isPresented: $showAddPhrase) {
                AddPhraseSheet(onClose: { showAddPhrase = false }) { text in
                    model.addPhrase(text: text)
                    showAddPhrase = false
                }
                .presentationDetents([.fraction(0.3), .medium])
            }
            .sheet(isPresented: Binding(get: { !isPad && showUiSizeSheet }, set: { if !$0 { showUiSizeSheet = false } })) {
                UiSizeSheet(onClose: { showUiSizeSheet = false },
                            uiTextFieldHeight: uiTextFieldHeight,
                            uiInputFontSize: uiInputFontSize,
                            uiChipFontSize: uiChipFontSize,
                            uiPlayIconSize: uiPlayIconSize)
                .presentationDetents([.fraction(0.35), .medium])
            }
            .sheet(isPresented: $showReorderSheet) {
                let phrases = model.filteredPhrases
                let all = model.state.phrases
                ReorderPhrasesSheet(
                    phrases: phrases,
                    allPhrases: all,
                    onMove: { from, to in model.movePhrase(from: from, to: to) },
                    onClose: { showReorderSheet = false }
                )
                .presentationDetents([.fraction(0.45), .large])
            }
            .sheet(isPresented: Binding(get: { editingPhrase != nil }, set: { if !$0 { editingPhrase = nil } })) {
                if let phrase = editingPhrase {
                    EditPhraseSheet(phrase: phrase, onClose: { editingPhrase = nil }) { updatedText, updatedName in
                        model.updatePhrase(id: phrase.id, text: updatedText, name: updatedName)
                        editingPhrase = nil
                    }
                    .presentationDetents([.fraction(0.35), .medium])
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
