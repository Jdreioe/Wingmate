import SwiftUI
import Shared

@MainActor
final class IosViewModel: ObservableObject {
    private let store: PhraseListStore
    private var cancellable: AnyCancellable?

    @Published var state: PhraseListState = PhraseListState(phrases: [], categories: [], selectedCategoryId: nil, isLoading: true, error: nil)

    // TODO: These need to be migrated to their own BLoC stores
    private let bridge = KoinBridge()
    @Published var input: String = ""
    @Published var primaryLanguage: String = "en-US"
    @Published var selectedVoice: Shared.Voice? = nil
    @Published var availableLanguages: [String] = []

    init() {
        self.store = KoinBridge().phraseListStore()
        self.cancellable = store.stateFlow.asPublisher()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] newState in
                self?.state = newState
            }
    }

    func addCurrentInputAsPhrase() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        store.dispatch(intent: PhraseListIntent.AddPhrase(text: text, categoryId: state.selectedCategoryId))
        input = ""
    }

    func addCategory(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store.dispatch(intent: PhraseListIntent.AddCategory(name: trimmed))
    }

    func deletePhrase(id: String) {
        store.dispatch(intent: PhraseListIntent.DeletePhrase(id: id))
    }

    func selectCategory(id: String?) {
        store.dispatch(intent: PhraseListIntent.SelectCategory(id: id))
    }

    var filteredPhrases: [Shared.Phrase] {
        guard let sel = state.selectedCategoryId, !sel.isEmpty else { return state.phrases.filter { ($0.parentId ?? "").isEmpty } }
        return state.phrases.filter { $0.parentId == sel }
    }

    // MARK: - Old Methods to be refactored
    // These methods still use the old bridge and will be migrated to a new BLoC store.

    func insertPhraseText(_ phrase: Shared.Phrase) {
        let t = phrase.text ?? ""
        guard !t.isEmpty else { return }
        input.append(t)
    }

    func speak(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        Task { _ = try? await bridge.speak(text: t) }
    }

    func chooseVoice(_ v: Shared.Voice) {
        Task {
            do {
                try await bridge.selectVoiceAndMaybeUpdatePrimary(voice: v)
                await MainActor.run {
                    self.selectedVoice = v
                    self.availableLanguages = v.supportedLanguages as? [String] ?? []
                    self.primaryLanguage = v.selectedLanguage ?? v.primaryLanguage ?? self.primaryLanguage
                }
            } catch {
               // await MainActor.run { self.error = error.localizedDescription }
            }
        }
    }

    func updatePrimaryLanguage(_ lang: String) {
        Task {
            _ = try? await bridge.updatePrimaryLanguage(lang: lang)
            await MainActor.run { self.primaryLanguage = lang }
        }
    }

    func refreshVoiceAndLanguages() {
        Task {
            let v = try? await bridge.selectedVoice()
            await MainActor.run {
                self.selectedVoice = v
                self.availableLanguages = v?.supportedLanguages as? [String] ?? []
            }
        }
    }
}

struct ContentView: View {
    @StateObject private var model = IosViewModel()
    @State private var showVoiceSheet = false
    @State private var showLanguageSheet = false
    @State private var showWelcome = true
    @State private var showAddCategory = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)

    var body: some View {
        NavigationStack {
            Group {
                if showWelcome {
                    WelcomeScreenIOS(onContinue: { showWelcome = false })
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        if let err = model.state.error { Text("Error: \(err)").foregroundStyle(.red) }
                        if model.state.isLoading { ProgressView().frame(maxWidth: .infinity, alignment: .center) }

                        // Categories row
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                CategoryChip(title: "All", selected: model.state.selectedCategoryId == nil) {
                                    model.selectCategory(id: nil)
                                }
                                ForEach(model.state.categories, id: \.id) { cat in
                                    CategoryChip(title: cat.name ?? "(No name)", selected: model.state.selectedCategoryId == cat.id) {
                                        model.selectCategory(id: cat.id)
                                    }
                                }
                            }
                            .padding(.horizontal, 4)
                        }

                        // Input field
                        TextField("Type text to speak", text: $model.input)
                            .textFieldStyle(.roundedBorder)

                        // Grid of phrases
                        ScrollView {
                            LazyVGrid(columns: columns, spacing: 8) {
                                // Add tile
                                Button(action: { model.addCurrentInputAsPhrase() }) {
                                    VStack {
                                        Image(systemName: "plus.circle.fill").font(.system(size: 28))
                                        Text("Add")
                                    }
                                    .frame(maxWidth: .infinity, minHeight: 100)
                                    .background(Color.secondary.opacity(0.12))
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                }
                                // Phrase tiles
                                ForEach(model.filteredPhrases, id: \.id) { p in
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text((p.name ?? p.text) ?? "")
                                            .font(.body)
                                            .lineLimit(3)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                        HStack {
                                            Button(action: { model.speak(p.text ?? "") }) { Image(systemName: "play.fill") }
                                                .buttonStyle(.borderless)
                                            Spacer()
                                            Button(action: { model.insertPhraseText(p) }) { Image(systemName: "text.insert") }
                                                .buttonStyle(.borderless)
                                        }
                                    }
                                    .padding(10)
                                    .frame(maxWidth: .infinity, minHeight: 120)
                                    .background(Color(hex: p.backgroundColor ?? "#00000000"))
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                    .contextMenu {
                                        Button(role: .destructive) {
                                            model.deletePhrase(id: p.id)
                                        } label: {
                                            Label("Delete", systemImage: "trash")
                                        }
                                    }
                                }
                            }
                            .padding(.top, 4)
                        }

                        // Playback controls
                        HStack(spacing: 16) {
                            Button(action: { model.speak(model.input) }) {
                                Image(systemName: "play.circle.fill").font(.system(size: 28))
                            }
                            .buttonStyle(.plain)
                            Button(action: {}) {
                                Image(systemName: "pause.circle").font(.system(size: 28))
                            }.disabled(true)
                            Button(action: {}) {
                                Image(systemName: "stop.circle").font(.system(size: 28))
                            }.disabled(true)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                    }
                }
            }
            .padding(16)
            .background(Color(.systemBackground))
        }
        .navigationTitle("Wingmate")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button(model.primaryLanguage) { showLanguageSheet = true }
                Button { showVoiceSheet = true } label: { Image(systemName: "gearshape") }
                Button { showAddCategory = true } label: { Image(systemName: "folder.badge.plus") }
            }
        }
        .sheet(isPresented: $showVoiceSheet) {
            VoiceSelectionSheet(selected: model.selectedVoice, onClose: { showVoiceSheet = false }) { v in
                model.chooseVoice(v)
                showVoiceSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showLanguageSheet) {
            LanguageSelectionSheet(languages: model.availableLanguages.isEmpty ? [model.primaryLanguage] : model.availableLanguages,
                                   selected: model.primaryLanguage,
                                   onClose: { showLanguageSheet = false }) { lang in
                model.updatePrimaryLanguage(lang)
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
        .onAppear {
            print("ContentView.onAppear — initializing DI")
            let bridge = KoinBridge()
            bridge.startIfNeeded()
            IosDiBridge().applyOverrides()
            model.refreshVoiceAndLanguages() // For settings that are not yet in BLoC
        }
        #if DEBUG
        .overlay(alignment: .topLeading) {
            Text("DEBUG: ContentView loaded")
                .font(.caption2)
                .padding(6)
                .background(Color.yellow.opacity(0.3))
                .cornerRadius(6)
                .padding(8)
        }
        #endif
    }
}

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (255, 0, 0, 0)
        }
        self.init(.sRGB, red: Double(r) / 255, green: Double(g) / 255, blue:  Double(b) / 255, opacity: Double(a) / 255)
    }
}

private struct CategoryChip: View {
    let title: String
    let selected: Bool
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            Text(title)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(selected ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12))
                .foregroundStyle(selected ? Color.accentColor : Color.primary)
                .clipShape(Capsule())
        }.buttonStyle(.plain)
    }
}

private struct WelcomeScreenIOS: View {
    @State private var step: Int = 0
    @State private var showAzureSettings = false
    @State private var showVoicePicker = false
    let onContinue: () -> Void

    var body: some View {
    VStack(spacing: 16) {
            if step == 0 {
                Spacer()
        Text("Welcome").font(.largeTitle).bold()
                Text("A Kotlin Multiplatform AAC app for Android, iOS, and beyond.")
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                Spacer()
                HStack(spacing: 12) {
                    Button("Azure Settings") { showAzureSettings = true }
                    Button("Choose Voice") { showVoicePicker = true }
                    Button("Continue") { onContinue() }
                }
            }
        }
    .padding(24)
    .background(Color(.systemGroupedBackground))
        .sheet(isPresented: $showAzureSettings) {
            AzureSettingsSheet(onClose: { showAzureSettings = false })
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showVoicePicker) {
            VoiceSelectionSheet(selected: nil, onClose: { showVoicePicker = false }) { _ in
                showVoicePicker = false
            }
            .presentationDetents([.medium, .large])
        }
    }
}

private struct AddCategorySheet: View {
    @State private var name: String = ""
    let onClose: () -> Void
    let onSave: (String) -> Void
    var body: some View {
        NavigationStack {
            Form {
                TextField("Category name", text: $name)
            }
            .navigationTitle("New Category")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close", action: onClose) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { onSave(name) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct AzureSettingsSheet: View {
    @State private var endpoint: String = ""
    @State private var key: String = ""
    @State private var loading = true
    @State private var saving = false
    @State private var error: String? = nil
    private let bridge = KoinBridge()
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Azure Speech") {
                    TextField("Endpoint (https://<region>.tts.speech.microsoft.com)", text: $endpoint)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                    SecureField("Subscription Key", text: $key)
                }
                Section {
                    Button(saving ? "Saving…" : "Save") {
                        Task {
                            saving = true
                            defer { saving = false }
                            do {
                                let cfg = Shared.SpeechServiceConfig(endpoint: endpoint.trimmingCharacters(in: .whitespacesAndNewlines),
                                                                     subscriptionKey: key.trimmingCharacters(in: .whitespacesAndNewlines))
                                try await bridge.saveSpeechConfig(config: cfg)
                                // Optionally warm up voice list after saving config
                                _ = try? await bridge.listVoices()
                                onClose()
                            } catch {
                                self.error = error.localizedDescription
                            }
                        }
                    }
                    .disabled(loading || saving || endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationTitle("Azure Settings")
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Close", action: onClose) } }
            .onAppear {
                Task {
                    loading = true
                    defer { loading = false }
                    do {
                        if let cfg = try await bridge.getSpeechConfig() {
                            endpoint = cfg.endpoint
                            key = cfg.subscriptionKey
                        }
                    } catch {
                        self.error = error.localizedDescription
                    }
                }
            }
            .overlay(alignment: .top) {
                if loading { ProgressView().padding(.top, 8) }
            }
            .alert("Error", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("OK", role: .cancel) { error = nil }
            } message: { Text(error ?? "Unknown error") }
        }
    }
}

private struct VoiceSelectionSheet: View {
    @State private var voices: [Shared.Voice] = []
    @State private var loading = true
    @State private var error: String? = nil
    @State private var selected: Shared.Voice?
    @State private var query: String = ""
    private let bridge = KoinBridge()

    let current: Shared.Voice?
    let onClose: () -> Void
    let onSelect: (Shared.Voice) -> Void

    init(selected: Shared.Voice?, onClose: @escaping () -> Void, onSelect: @escaping (Shared.Voice) -> Void) {
        self.current = selected
        self.onClose = onClose
        self.onSelect = onSelect
        self._selected = State(initialValue: selected)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Simple search bar
                HStack {
                    Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                    TextField("Search voices", text: $query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                }
                .padding(8)
                .background(Color(.secondarySystemBackground))

                Group {
                    if loading { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
                    else if let err = error { Text("Error: \(err)").padding() }
                    else {
                        List {
                            ForEach(filteredVoices.indices, id: \.self) { idx in
                                let v = filteredVoices[idx]
                                VoiceRow(v: v, isSelected: v.name == selected?.name)
                                    .contentShape(Rectangle())
                                    .onTapGesture { selected = v }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Voice")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close", action: onClose) }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { Task { await refreshFromAzure() } } label: { Image(systemName: "arrow.clockwise") }
                    Button("Select") { if let v = selected { onSelect(v) } }
                }
            }
            .onAppear { Task { await loadInitial() } }
        }
    }

    private var filteredVoices: [Shared.Voice] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return voices }
        return voices.filter { v in
            let name = (v.displayName ?? v.name ?? "").lowercased()
            let locale = (v.primaryLanguage ?? "").lowercased()
            return name.contains(q.lowercased()) || locale.contains(q.lowercased())
        }
    }

    private func loadInitial() async {
        loading = true
        defer { loading = false }
        do {
            // Start with whatever the repo has (may be empty)
            let list = try await bridge.listVoices()
            voices = list
            if list.isEmpty {
                // Fetch from Azure if nothing cached
                let cloud = try await bridge.refreshVoicesFromAzure()
                voices = cloud
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func refreshFromAzure() async {
        loading = true
        defer { loading = false }
        do {
            let cloud = try await bridge.refreshVoicesFromAzure()
            voices = cloud
        } catch {
            self.error = error.localizedDescription
        }
    }
}

private struct VoiceRow: View {
    let v: Shared.Voice
    let isSelected: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(v.displayName ?? v.name ?? "Unknown")
                if let lang = v.primaryLanguage {
                    Text(lang).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if isSelected { Image(systemName: "checkmark").foregroundColor(.accentColor) }
        }
    }
}

private struct LanguageSelectionSheet: View {
    let languages: [String]
    let selected: String
    let onClose: () -> Void
    let onSelect: (String) -> Void

    var body: some View {
        NavigationStack {
            List(languages, id: \.self) { lang in
                HStack {
                    Text(lang)
                    Spacer()
                    if lang == selected { Image(systemName: "checkmark").foregroundColor(.accentColor) }
                }
                .contentShape(Rectangle())
                .onTapGesture { onSelect(lang) }
            }
            .navigationTitle("Language")
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Close", action: onClose) } }
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View { ContentView() }
}
