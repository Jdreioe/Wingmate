import SwiftUI
import Shared
import AVFoundation

// Observer to bridge MVIKotlin states(observer:) to Swift closures
private final class StoreObserver: NSObject, Shared.RxObserver {
    private let onNextState: (Shared.PhraseListStoreState) -> Void
    init(onNext: @escaping (Shared.PhraseListStoreState) -> Void) {
        self.onNextState = onNext
    }
    func onComplete() { /* no-op */ }
    func onNext(value: Any?) {
        if let s = value as? Shared.PhraseListStoreState {
            onNextState(s)
        }
    }
}

@MainActor
final class IosViewModel: ObservableObject {
    private var store: Shared.PhraseListStore?
    private var disposable: Shared.RxDisposable?

    @Published var state: Shared.PhraseListStoreState = Shared.PhraseListStoreState(phrases: [], categories: [], selectedCategoryId: nil, isLoading: true, error: nil)

    // Bridge to shared KMP use-cases
    private let bridge = KoinBridge()

    // UI state
    @Published var input: String = ""
    @Published var primaryLanguage: String = "en-US"
    @Published var selectedVoice: Shared.Voice? = nil
    @Published var availableLanguages: [String] = []

    // Debug helpers
    @Published var debugRepoName: String = ""
    @Published var debugPersistedVoiceName: String = ""

    // TODO: These need to be migrated to their own BLoC stores
    // Languages are now managed via the toolbar Language button; chips hidden for a cleaner UI
    // Pick a language for a voice preferring non-empty selectedLanguage, else primaryLanguage, else current app language
    fileprivate func effectiveLanguage(for v: Shared.Voice) -> String {
        func nonEmpty(_ s: String?) -> String? {
            let t = (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        if let s = nonEmpty(v.selectedLanguage) { return s }
        if let p = nonEmpty(v.primaryLanguage) { return p }
        return self.primaryLanguage
    }

    /// Start platform-dependent services and subscribe the store. Call once from the UI (e.g. onAppear).
    func start() async {
        // Ensure DI is started and iOS overrides are applied before resolving the store
        await MainActor.run {
            // Start Koin and apply iOS overrides via the Kotlin bridge.
            IosDiBridge().startKoinWithOverridesBridge()
        }

    // Debug: show which VoiceRepository is bound before resolving store
    let repoNameBefore = KoinBridge().debugVoiceRepositoryName()
    print("DEBUG: After startKoinWithOverrides: Bound VoiceRepository = \(repoNameBefore)")

        // Resolve store safely; avoid crashing Swift if DI isn't fully ready
        if let phraseStore = KoinBridge().phraseListStoreOrNull() {
            self.store = phraseStore
            let observer = StoreObserver { [weak self] newState in
                self?.state = newState
            }
            self.disposable = store?.states(observer: observer)
        } else {
            print("DEBUG: phraseListStoreOrNull() returned nil — Koin not ready or store not bound")
            // Retry once after a short delay to tolerate slow DI startup
            try? await Task.sleep(nanoseconds: 150_000_000)
            if let retryStore = KoinBridge().phraseListStoreOrNull() {
                self.store = retryStore
                let observer = StoreObserver { [weak self] newState in
                    self?.state = newState
                }
                self.disposable = store?.states(observer: observer)
                print("DEBUG: Store resolved on retry")
            } else {
                print("DEBUG: Store still nil after retry")
            }
        }

    #if DEBUG
    // Debug: print and store which VoiceRepository implementation is bound and persisted voice
    print("DEBUG: Bound VoiceRepository = \(repoNameBefore)")
    self.debugRepoName = repoNameBefore
    let persisted = try? await bridge.selectedVoice()
    let pName = (persisted?.displayName ?? persisted?.name) ?? "(none)"
    print("DEBUG: persisted selected voice = \(pName)")
    self.debugPersistedVoiceName = pName
    #endif

    // Refresh voice/language state from repository after starting
    refreshVoiceAndLanguages()
    }
    deinit { disposable?.dispose() }

    func addCurrentInputAsPhrase() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
    store?.accept(intent: Shared.PhraseListStoreIntent.AddPhrase(text: text))
        input = ""
    }

    func addPhrase(text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store?.accept(intent: Shared.PhraseListStoreIntent.AddPhrase(text: trimmed))
    }

    func addCategory(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
    store?.accept(intent: Shared.PhraseListStoreIntent.AddCategory(name: trimmed))
    }

    func deletePhrase(id: String) {
        // Clean up any attached recording
        if let path = recordingPath(for: id) {
            try? FileManager.default.removeItem(atPath: path)
            setRecordingPath("", for: id) // will be pruned below
            recordings.removeValue(forKey: id)
            if let data = try? JSONSerialization.data(withJSONObject: recordings, options: []) {
                UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: recordingKey)
            }
        }
    store?.accept(intent: Shared.PhraseListStoreIntent.DeletePhrase(phraseId: id))
    }

    func selectCategory(id: String?) {
    store?.accept(intent: Shared.PhraseListStoreIntent.SelectCategory(categoryId: id))
    }

    var filteredPhrases: [Shared.Phrase] {
    // When "All" is selected (no category), show all phrases from all categories
    guard let sel = state.selectedCategoryId, !sel.isEmpty else { return state.phrases }
        return state.phrases.filter { $0.parentId == sel }
    }

    // MARK: - Old Methods to be refactored
    // These methods still use the old bridge and will be migrated to a new BLoC store.

    func insertPhraseText(_ phrase: Shared.Phrase) {
    let t = phrase.text
        guard !t.isEmpty else { return }
        input.append(t)
    }

    func speak(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
    AudioSessionHelper.activatePlayback()
        Task { _ = try? await bridge.speak(text: t) }
    }

    // MARK: - Recording store (Swift-side for now)
    // Map phraseId -> file path persisted in UserDefaults; simple until we bridge via KMP
    private let recordingKey = "phrase_recordings_v1"
    private var recordings: [String: String] = {
        let d = UserDefaults.standard
        if let json = d.string(forKey: "phrase_recordings_v1"), let data = json.data(using: .utf8) {
            return (try? JSONSerialization.jsonObject(with: data) as? [String: String]) ?? [:]
        }
        return [:]
    }()

    func recordingPath(for phraseId: String) -> String? { recordings[phraseId] }
    func setRecordingPath(_ path: String, for phraseId: String) {
        recordings[phraseId] = path
        if let data = try? JSONSerialization.data(withJSONObject: recordings, options: []) {
            UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: recordingKey)
        }
        objectWillChange.send()
    }

    func chooseVoice(_ v: Shared.Voice) async {
        do {
            try await bridge.selectVoiceAndMaybeUpdatePrimary(voice: v)
            await MainActor.run {
                self.selectedVoice = v
                if let langs = v.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
                self.primaryLanguage = effectiveLanguage(for: v)
            }
            // Refresh UI from persisted selection (ensure UI reflects actual saved state)
            let persisted = try? await bridge.selectedVoice()
            if let pv = persisted {
                self.selectedVoice = pv
                if let langs = pv.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
                self.primaryLanguage = effectiveLanguage(for: pv)
                #if DEBUG
                let name = (pv.displayName ?? pv.name) ?? "—"
                let lang = effectiveLanguage(for: pv)
                print("DEBUG: bridge.selectedVoice() => \(name) [\(lang)]")
                #endif
            } else {
                #if DEBUG
                print("DEBUG: bridge.selectedVoice() => (none)")
                #endif
            }
        } catch {
           // await MainActor.run { self.error = error.localizedDescription }
        }
    }

    func updateLanguage(_ lang: String) {
        Task {
            _ = try? await bridge.updateSelectedVoiceLanguage(lang: lang)
            self.primaryLanguage = lang
            refreshVoiceAndLanguages()
        }
    }

    func refreshVoiceAndLanguages() {
        Task {
            let v = try? await bridge.selectedVoice()
            self.selectedVoice = v
            if let langs = v?.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
            if let v = v {
                self.primaryLanguage = effectiveLanguage(for: v)
            }
        }
    }

    // MARK: - Public wrappers for store intents (avoid accessing private store externally)
    func deleteCategory(id: String) {
        store?.accept(intent: Shared.PhraseListStoreIntent.DeleteCategory(categoryId: id))
    }

    func updatePhrase(id: String, text: String?, name: String?) {
        store?.accept(intent: Shared.PhraseListStoreIntent.UpdatePhrase(id: id, text: text, name: name))
    }

    func movePhrase(from: Int, to: Int) {
        store?.accept(intent: Shared.PhraseListStoreIntent.MovePhrase(fromIndex: Int32(from), toIndex: Int32(to)))
    }
}

struct ContentView: View {
    @StateObject private var model = IosViewModel()
    @State private var showVoiceSheet = false
    @State private var showLanguageSheet = false
    @State private var showWelcome = true
    @State private var showAddCategory = false
    @State private var showAddPhrase = false
    @State private var showUiSizeSheet = false
    @State private var editingPhrase: Shared.Phrase? = nil
    @State private var showReorderSheet = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)
    @State private var recorder = AudioRecorder()
    @State private var recordingForPhraseId: String? = nil

    // UI size controls (persisted)
    @AppStorage("ui_textFieldHeight") private var uiTextFieldHeight: Double = 72
    @AppStorage("ui_inputFontSize") private var uiInputFontSize: Double = 20
    @AppStorage("ui_chipFontSize") private var uiChipFontSize: Double = 18
    @AppStorage("ui_playIconSize") private var uiPlayIconSize: Double = 36

    // Derived paddings based on chip font
    private var chipHPadding: CGFloat { CGFloat(max(12, uiChipFontSize * 0.75)) }
    private var chipVPadding: CGFloat { CGFloat(max(8, uiChipFontSize * 0.45)) }

    var body: some View {
        NavigationStack {
            Group {
                if showWelcome {
                    WelcomeScreenIOS(
                        onContinue: { showWelcome = false },
                        onVoiceSelected: { v in
                            Task { await model.chooseVoice(v) }
                        }
                    )
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        if let err = model.state.error {
                            HStack(spacing: 4) { Text("common.error"); Text(err) }.foregroundStyle(.red)
                        }
                        if model.state.isLoading { ProgressView().frame(maxWidth: .infinity, alignment: .center) }

                        // Input field (TTS box) - multi-line
                        MultiLineInput(text: $model.input,
                                       placeholder: NSLocalizedString("tts.placeholder", comment: ""),
                                       fontSize: CGFloat(uiInputFontSize),
                                       minHeight: CGFloat(uiTextFieldHeight))

                        // Categories row (above phrases)
                        CategoriesRowView(
                            state: model.state,
                            chipFontSize: CGFloat(uiChipFontSize),
                            chipHPadding: chipHPadding,
                            chipVPadding: chipVPadding,
                            onSelect: { id in model.selectCategory(id: id) },
                            onDelete: { id in model.deleteCategory(id: id) }
                        )

                        // Grid of phrases
                        ScrollView {
                            LazyVGrid(columns: columns, spacing: 8) {
                                // Add tile
                Button(action: { showAddPhrase = true }) {
                                    VStack {
                                        Image(systemName: "plus.circle.fill").font(.system(size: 28))
                    Text("phrase.add.tile")
                                    }
                                    .frame(maxWidth: .infinity, minHeight: 100)
                                    .background(Color.secondary.opacity(0.12))
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                }
                                // Phrase tiles
                                ForEach(model.filteredPhrases, id: \.id) { p in
                                    PhraseItemView(
                                        model: model,
                                        phrase: p,
                                        recorder: recorder,
                                        recordingForPhraseId: $recordingForPhraseId,
                                        onEdit: { editingPhrase = p },
                                        requestMic: { id in requestMicAndStart(for: id) },
                                        onDelete: { id in model.deletePhrase(id: id) }
                                    )
                                }
                            }
                            .padding(.top, 4)
                        }

                        // Playback controls
                        HStack(spacing: 20) {
                            Button(action: { model.speak(model.input) }) {
                                Image(systemName: "play.circle.fill")
                                    .font(.system(size: CGFloat(uiPlayIconSize)))
                            }
                            .buttonStyle(.plain)
                            Button(action: {}) {
                                Image(systemName: "pause.circle")
                                    .font(.system(size: CGFloat(uiPlayIconSize - 4)))
                            }.disabled(true)
                            Button(action: {}) {
                                Image(systemName: "stop.circle")
                                    .font(.system(size: CGFloat(uiPlayIconSize - 4)))
                            }.disabled(true)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)

                        // (chips moved above)
                    }
                }
            }
            .padding(16)
            .background(Color(.systemBackground))
            .navigationTitle(Text("app.title"))
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button(action: { showReorderSheet = true }) {
                        Image(systemName: "arrow.up.arrow.down").accessibilityLabel(Text("toolbar.reorder"))
                    }
                    Button(action: { showLanguageSheet = true }) {
                        Label("toolbar.language", systemImage: "globe")
                    }
                    Button(action: { showVoiceSheet = true }) {
                        Image(systemName: "gearshape").accessibilityLabel(Text("toolbar.voice"))
                    }
                    Button(action: { showAddCategory = true }) {
                        Image(systemName: "folder.badge.plus").accessibilityLabel(Text("toolbar.add_category"))
                    }
                    Button(action: { showUiSizeSheet = true }) {
                        Image(systemName: "textformat.size").accessibilityLabel(Text("toolbar.ui_size"))
                    }
                }
            }
    }
        .sheet(isPresented: $showVoiceSheet) {
            VoiceSelectionSheet(selected: model.selectedVoice, onClose: { showVoiceSheet = false }) { v in
                Task {
                    await model.chooseVoice(v)
                    await MainActor.run { showVoiceSheet = false }
                }
            }
            .presentationDetents([.medium, .large])
        }
    .sheet(isPresented: $showLanguageSheet) {
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
        .sheet(isPresented: $showUiSizeSheet) {
            UiSizeSheet(onClose: { showUiSizeSheet = false },
                        uiTextFieldHeight: $uiTextFieldHeight,
                        uiInputFontSize: $uiInputFontSize,
                        uiChipFontSize: $uiChipFontSize,
                        uiPlayIconSize: $uiPlayIconSize)
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
        .sheet(item: $editingPhrase) { phrase in
            EditPhraseSheet(phrase: phrase, onClose: { editingPhrase = nil }) { updatedText, updatedName in
                model.updatePhrase(id: phrase.id, text: updatedText, name: updatedName)
                editingPhrase = nil
            }
            .presentationDetents([.fraction(0.35), .medium])
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
        #if DEBUG
        .overlay(alignment: .topLeading) {
            VStack(alignment: .leading, spacing: 4) {
                Text("DEBUG: ContentView loaded")
                if let v = model.selectedVoice {
                    let name = (v.displayName ?? v.name) ?? "—"
                    let lang = v.selectedLanguage ?? v.primaryLanguage ?? "-"
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

    private func requestMicAndStart(for phraseId: String) {
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission { granted in
                if granted {
                    Task {
                        recordingForPhraseId = phraseId
                        _ = try? await recorder.startRecording()
                    }
                }
            }
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                if granted {
                    Task {
                        recordingForPhraseId = phraseId
                        _ = try? await recorder.startRecording()
                    }
                }
            }
        }
    }

}

// Lightweight recorder wrapper
final class AudioRecorder: NSObject, AVAudioRecorderDelegate, AVAudioPlayerDelegate {
    private(set) var isRecording = false
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?

    func startRecording() async throws -> URL {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)
        // Save in Documents/Recordings
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dir = docs.appendingPathComponent("Recordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("phrase_\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue
        ]
        recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder?.delegate = self
        recorder?.record()
        isRecording = true
        return url
    }

    func stopRecording() async throws -> URL {
        guard let r = recorder else { throw NSError(domain: "rec", code: 1) }
        r.stop()
        isRecording = false
        try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return r.url
    }

    func play(url: URL) {
        do {
            player?.stop()
            player = try AVAudioPlayer(contentsOf: url)
            player?.prepareToPlay()
            player?.play()
        } catch {
            print("Failed to play: \(error)")
        }
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
    var fontSize: CGFloat = 16
    var hPadding: CGFloat = 12
    var vPadding: CGFloat = 6
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            Text(title)
                .font(.system(size: fontSize, weight: .medium))
                .padding(.horizontal, hPadding)
                .padding(.vertical, vPadding)
                .background(selected ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12))
                .foregroundStyle(selected ? Color.accentColor : Color.primary)
                .clipShape(Capsule())
        }.buttonStyle(.plain)
    }
}

private struct LanguageChip: View {
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
    let onVoiceSelected: (Shared.Voice) -> Void

    var body: some View {
    VStack(spacing: 16) {
            if step == 0 {
        Spacer()
    Text("welcome.title").font(.largeTitle).bold()
        Text("welcome.subtitle")
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                Spacer()
                HStack(spacing: 12) {
            Button("welcome.azure_settings") { showAzureSettings = true }
            Button("welcome.choose_voice") { showVoicePicker = true }
            Button("common.continue") { onContinue() }
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
            VoiceSelectionSheet(selected: nil, onClose: { showVoicePicker = false }) { v in
                onVoiceSelected(v)
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
                TextField(NSLocalizedString("category.name.placeholder", comment: ""), text: $name)
            }
            .navigationTitle(Text("category.new.title"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("category.save") { onSave(name) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct AddPhraseSheet: View {
    @State private var text: String = ""
    let onClose: () -> Void
    let onSave: (String) -> Void

    var body: some View {
        NavigationStack {
            Form {
                TextField(NSLocalizedString("phrase.text.placeholder", comment: ""), text: $text, axis: .vertical)
                    .lineLimit(3, reservesSpace: true)
            }
            .navigationTitle(Text("phrase.new.title"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("phrase.save") { onSave(text) }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct EditPhraseSheet: View {
    let phrase: Shared.Phrase
    let onClose: () -> Void
    let onSave: (String?, String?) -> Void
    @State private var text: String = ""
    @State private var name: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("phrase.display_name.optional") {
                    TextField(NSLocalizedString("common.name", comment: ""), text: $name)
                }
                Section("phrase.text.placeholder") {
                    TextEditor(text: $text).frame(minHeight: 120)
                }
            }
            .navigationTitle(Text("phrase.edit.title"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("phrase.save") { onSave(text.trimmingCharacters(in: .whitespacesAndNewlines), name.trimmingCharacters(in: .whitespacesAndNewlines)) }
                }
            }
            .onAppear {
                text = phrase.text ?? ""
                name = phrase.name ?? ""
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
                Section("azure.settings.title") {
                    TextField(NSLocalizedString("azure.endpoint.placeholder", comment: ""), text: $endpoint)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                    SecureField(NSLocalizedString("azure.key.placeholder", comment: ""), text: $key)
                }
                Section {
                    Button(saving ? "common.saving" : "common.save") {
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
            .navigationTitle(Text("azure.settings.title"))
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) } }
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
            .alert("common.error", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("common.ok", role: .cancel) { error = nil }
            } message: { Text(error ?? NSLocalizedString("common.unknown_error", comment: "")) }
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
                    TextField(NSLocalizedString("voice.search.placeholder", comment: ""), text: $query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                }
                .padding(8)
                .background(Color(.secondarySystemBackground))

                Group {
                    if loading { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
                    else if let err = error { HStack(spacing: 4) { Text("common.error"); Text(err) }.padding() }
                    else {
                        List {
                            ForEach(filteredVoices.indices, id: \.self) { idx in
                                let v = filteredVoices[idx]
                                VoiceRow(v: v, isSelected: v.name == selected?.name)
                                    .contentShape(Rectangle())
                                    .onTapGesture {
                                        // Mark locally for immediate UI feedback; do not persist or close yet
                                        selected = v
                                    }
                            }
                        }
                    }
                }
            }
            .navigationTitle(Text("toolbar.voice"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { Task { await refreshFromAzure() } } label: { Image(systemName: "arrow.clockwise") }
                    Button("common.select") {
                        if let v = selected {
                            onSelect(v)
                            onClose()
                        }
                    }
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
                Text(v.displayName ?? v.name ?? NSLocalizedString("common.no_name", comment: ""))
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
            .navigationTitle(Text("toolbar.language"))
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) } }
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View { ContentView() }
}

// Make Shared.Phrase usable with SwiftUI's sheet(item:) and ForEach
extension Shared.Phrase: Identifiable {}

// Reorder sheet to drag and drop phrases; maps local indices to global ordering indices
private struct ReorderPhrasesSheet: View {
    let phrases: [Shared.Phrase]          // filtered list currently shown
    let allPhrases: [Shared.Phrase]       // full global-ordered list
    let onMove: (Int, Int) -> Void        // fromGlobal, toGlobal
    let onClose: () -> Void

    @State private var local: [Shared.Phrase] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(local, id: \.id) { p in
                    Text(p.name ?? p.text)
                }
                .onMove(perform: move)
            }
            .navigationTitle(Text("reorder.title"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) }
                ToolbarItem(placement: .topBarTrailing) { EditButton() }
            }
            .onAppear { self.local = phrases }
        }
    }

    private func move(from source: IndexSet, to destination: Int) {
        guard let fromLocal = source.first else { return }
        var toLocal = destination
        if toLocal > fromLocal { toLocal -= 1 } // SwiftUI semantics

        // Compute global indices using IDs
        let movingId = local[fromLocal].id
        let targetGlobal: Int = {
            if toLocal >= local.count - 1 {
                // end: place after last of current filtered subset
                if let lastId = local.last?.id, let lastGlobal = allPhrases.firstIndex(where: { $0.id == lastId }) {
                    return lastGlobal + 1
                }
                return allPhrases.count
            } else {
                let targetId = local[toLocal].id
                return allPhrases.firstIndex(where: { $0.id == targetId }) ?? allPhrases.count
            }
        }()
        guard let fromGlobal = allPhrases.firstIndex(where: { $0.id == movingId }) else { return }

        // Update local preview immediately
        var updated = local
        let item = updated.remove(at: fromLocal)
        updated.insert(item, at: max(0, min(toLocal, updated.count)))
        self.local = updated

        onMove(fromGlobal, targetGlobal)
    }
}

// A lightweight multi-line input using TextEditor with a placeholder and adjustable height
private struct MultiLineInput: View {
    @Binding var text: String
    var placeholder: String
    var fontSize: CGFloat
    var minHeight: CGFloat

    var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(.secondarySystemBackground))
            if text.isEmpty {
                Text(placeholder)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 12)
            }
            TextEditor(text: $text)
                .font(.system(size: fontSize))
                .scrollContentBackground(.hidden)
                .background(Color.clear)
                .padding(8)
        }
        .frame(height: minHeight) // enforce visible height to match slider
    }
}

private struct UiSizeSheet: View {
    let onClose: () -> Void
    @Binding var uiTextFieldHeight: Double
    @Binding var uiInputFontSize: Double
    @Binding var uiChipFontSize: Double
    @Binding var uiPlayIconSize: Double

    var body: some View {
        NavigationStack {
            Form {
                Section("ui_size.input.section") {
                    HStack { Text("ui_size.input.height"); Spacer(); Text("\(Int(uiTextFieldHeight))") }
                    Slider(value: $uiTextFieldHeight, in: 44...160, step: 2)
                    HStack { Text("ui_size.input.font"); Spacer(); Text("\(Int(uiInputFontSize))") }
                    Slider(value: $uiInputFontSize, in: 14...30, step: 1)
                }
                Section("ui_size.chips.section") {
                    HStack { Text("ui_size.input.font"); Spacer(); Text("\(Int(uiChipFontSize))") }
                    Slider(value: $uiChipFontSize, in: 12...28, step: 1)
                }
                Section("ui_size.playback.section") {
                    HStack { Text("ui_size.playback.icon"); Spacer(); Text("\(Int(uiPlayIconSize))") }
                    Slider(value: $uiPlayIconSize, in: 28...64, step: 1)
                }
            }
            .navigationTitle(Text("ui_size.title"))
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) } }
        }
    }
}

// MARK: - Extracted subviews to reduce type-check complexity
private struct CategoriesRowView: View {
    let state: Shared.PhraseListStoreState
    let chipFontSize: CGFloat
    let chipHPadding: CGFloat
    let chipVPadding: CGFloat
    let onSelect: (String?) -> Void
    let onDelete: (String) -> Void
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                CategoryChip(title: NSLocalizedString("categories.all", comment: ""),
                             selected: state.selectedCategoryId == nil,
                             fontSize: chipFontSize,
                             hPadding: chipHPadding,
                             vPadding: chipVPadding) { onSelect(nil) }
                ForEach(state.categories, id: \.id) { cat in
                    CategoryChip(title: cat.name ?? NSLocalizedString("common.no_name", comment: ""),
                                 selected: state.selectedCategoryId == cat.id,
                                 fontSize: chipFontSize,
                                 hPadding: chipHPadding,
                                 vPadding: chipVPadding) { onSelect(cat.id) }
                    .contextMenu {
                        Button(role: .destructive) { onDelete(cat.id) } label: { Label("category.delete", systemImage: "trash") }
                    }
                }
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 4)
        }
    }
}

private struct PhraseItemView: View {
    @ObservedObject var model: IosViewModel
    let phrase: Shared.Phrase
    let recorder: AudioRecorder
    @Binding var recordingForPhraseId: String?
    let onEdit: () -> Void
    let requestMic: (String) -> Void
    let onDelete: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(phrase.name ?? phrase.text)
                .font(.body)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack {
                if recordingForPhraseId == phrase.id && recorder.isRecording {
                    Button(action: {
                        Task {
                            if let file = try? await recorder.stopRecording() {
                                model.setRecordingPath(file.path, for: phrase.id)
                            }
                            recordingForPhraseId = nil
                        }
                    }) { Image(systemName: "stop.fill") }
                    .buttonStyle(.borderless)
                } else {
                    Button(action: { requestMic(phrase.id) }) {
                        Image(systemName: model.recordingPath(for: phrase.id) == nil ? "mic" : "mic.fill")
                    }
                    .buttonStyle(.borderless)
                }
                if let path = model.recordingPath(for: phrase.id) {
                    Button(action: { recorder.play(url: URL(fileURLWithPath: path)) }) { Image(systemName: "play.fill") }
                        .buttonStyle(.borderless)
                } else {
                    Button(action: { model.speak(phrase.text) }) { Image(systemName: "play.fill") }
                        .buttonStyle(.borderless)
                }
                Spacer()
                Button(action: { model.insertPhraseText(phrase) }) { Image(systemName: "text.insert") }
                    .buttonStyle(.borderless)
                Button(action: onEdit) { Image(systemName: "pencil") }
                    .buttonStyle(.borderless)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 120)
        .background(Color(hex: phrase.backgroundColor ?? "#00000000"))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .contextMenu {
            Button(role: .destructive) { onDelete(phrase.id) } label: { Label("phrase.delete", systemImage: "trash") }
        }
    }
}
