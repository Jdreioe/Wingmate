import SwiftUI
import Shared

struct PronunciationDictionaryView: View {
    @ObservedObject var model: IosViewModel
    @Environment(\.dismiss) private var dismiss
    
    @State private var showAddSheet = false
    
    var body: some View {
        List {
            if model.pronunciations.isEmpty {
                Text("No custom pronunciations.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.pronunciations, id: \.word) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entry.word)
                            .font(.headline)
                        Text(entry.phoneme)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .monospaced()
                        Text("(\(entry.alphabet))")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
                .onDelete { indexSet in
                    for index in indexSet {
                        let word = model.pronunciations[index].word
                        model.deletePronunciation(word: word)
                    }
                }
            }
        }
        .navigationTitle("Pronunciations")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(action: { showAddSheet = true }) {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showAddSheet) {
            AddPronunciationSheet(model: model)
        }
        .task {
            await model.loadPronunciations()
        }
    }
}

struct AddPronunciationSheet: View {
    @ObservedObject var model: IosViewModel
    @Environment(\.dismiss) private var dismiss
    
    @State private var word = ""
    @State private var phoneme = ""
    @State private var alphabet = "ipa"
    
    let alphabets = ["ipa", "x-sampa", "sapi", "ups"]
    
    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Word")) {
                    TextField("Ex: Wingmate", text: $word)
                        .autocorrectionDisabled()
                }
                
                Section(header: Text("Phoneme")) {
                    TextField("Ex: w ɪ ŋ m eɪ t", text: $phoneme)
                        .autocorrectionDisabled()
                }
                
                Section(header: Text("Alphabet")) {
                    Picker("Alphabet", selection: $alphabet) {
                        ForEach(alphabets, id: \.self) { a in
                            Text(a.uppercased()).tag(a)
                        }
                    }
                }
                
                Section {
                    Button("Save") {
                        model.addPronunciation(word: word, phoneme: phoneme, alphabet: alphabet)
                        dismiss()
                    }
                    .disabled(word.isEmpty || phoneme.isEmpty)
                }
            }
            .navigationTitle("Add Pronunciation")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
