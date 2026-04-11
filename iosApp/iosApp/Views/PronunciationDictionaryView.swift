import SwiftUI
import Shared

struct PronunciationDictionaryView: View {
    @ObservedObject var model: IosViewModel
    @Environment(\.dismiss) private var dismiss
    
    @State private var showAddSheet = false
    
    var body: some View {
        List {
            if model.pronunciations.isEmpty {
                Text("pronunciation.empty")
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
        .navigationTitle(Text("pronunciation.title"))
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
    private let ipaSymbols = [
        "i", "y", "ɨ", "ʉ", "ɯ", "u",
        "ɪ", "ʏ", "ʊ", "e", "ø", "ɘ", "ɵ", "ɤ", "o",
        "ə", "ɛ", "œ", "ɜ", "ɞ", "ʌ", "ɔ",
        "æ", "ɐ", "a", "ɶ", "ɑ", "ɒ",
        "p", "b", "t", "d", "k", "g",
        "f", "v", "θ", "ð", "s", "z", "ʃ", "ʒ", "h",
        "m", "n", "ŋ", "l", "ɹ", "j", "w",
        "tʃ", "dʒ", "ˈ", "ˌ", ".", "ː", "ˑ", " "
    ]
    
    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("pronunciation.word")) {
                    TextField("pronunciation.word.placeholder", text: $word)
                        .autocorrectionDisabled()
                }
                
                Section(header: Text("pronunciation.phoneme")) {
                    TextField("pronunciation.phoneme.placeholder", text: $phoneme)
                        .autocorrectionDisabled()
                }
                
                Section(header: Text("pronunciation.alphabet")) {
                    Picker("pronunciation.alphabet", selection: $alphabet) {
                        ForEach(alphabets, id: \.self) { a in
                            Text(a.uppercased()).tag(a)
                        }
                    }
                }

                if alphabet == "ipa" {
                    Section(header: Text("pronunciation.ipa_palette")) {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 42), spacing: 8)], spacing: 8) {
                            ForEach(ipaSymbols, id: \.self) { symbol in
                                Button(symbol == " " ? "␠" : symbol) {
                                    phoneme.append(symbol)
                                }
                                .buttonStyle(.bordered)
                                .font(.system(.body, design: .monospaced))
                            }
                        }

                        HStack {
                            Spacer()
                            Button("pronunciation.delete_last") {
                                _ = phoneme.popLast()
                            }
                            .buttonStyle(.bordered)
                            .disabled(phoneme.isEmpty)
                        }
                    }
                }
                
                Section {
                    Button("common.save") {
                        model.addPronunciation(word: word, phoneme: phoneme, alphabet: alphabet)
                        dismiss()
                    }
                    .disabled(word.isEmpty || phoneme.isEmpty)
                }
            }
            .navigationTitle(Text("pronunciation.add_title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { dismiss() }
                }
            }
        }
    }
}
