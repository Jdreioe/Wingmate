import SwiftUI
import Shared
import AVFoundation

struct WelcomeScreenIOS: View {
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

struct AddCategorySheet: View {
    @State private var name: String = ""
    let onClose: () -> Void
    let onSave: (String) -> Void
    
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Button("category.close", action: onClose)
                    Spacer()
                    Text("category.new.title")
                        .font(.headline)
                        .bold()
                    Spacer()
                    Button("category.save") { onSave(name) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .fontWeight(.semibold)
                        .foregroundColor(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? .secondary : .accentColor)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 16)
                
                Divider()
                
                // Content
                VStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("category.name.placeholder")
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .foregroundColor(.primary)
                        
                        TextField("", text: $name)
                            .textFieldStyle(.plain)
                            .font(.system(size: 16))
                            .padding(16)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color(.separator), lineWidth: 1)
                            )
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 24)
                    
                    Spacer()
                }
            }
        }
        .interactiveDismissDisabled(false)
    }
}

struct AddPhraseSheet: View {
    @State private var text: String = ""
    @State private var recordingUrl: URL? = nil
    @State private var micDenied: Bool = false
    @State private var isRecording: Bool = false
    let onClose: () -> Void
    let recorder: AudioRecorder?
    // (phraseId, path)
    let saveRecordingPath: ((String,String) -> Void)?
    let onSave: (String) -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Button("category.close", action: onClose)
                    Spacer()
                    Text("phrase.new.title")
                        .font(.headline)
                        .bold()
                    Spacer()
                    Button("phrase.save") {
                        onSave(text)
                        // We don't have the phrase ID here; ContentView persists later after store update
                    }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .fontWeight(.semibold)
                    .foregroundColor(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? .secondary : .accentColor)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 16)
                
                Divider()
                
                // Content
                ScrollView {
                    VStack(spacing: 20) {
                        // Text input
                        VStack(alignment: .leading, spacing: 8) {
                            Text("phrase.text.placeholder")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            
                            TextEditor(text: $text)
                                .frame(minHeight: 100)
                                .padding(12)
                                .background(Color(.secondarySystemBackground))
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color(.separator), lineWidth: 1)
                                )
                        }
                        
                        // Recording section
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Recording")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            
                            VStack(spacing: 12) {
                                HStack(spacing: 12) {
                                    if isRecording {
                                        Button(role: .destructive) {
                                            Task {
                                                if let url = try? await recorder?.stopRecording() {
                                                    await MainActor.run {
                                                        recordingUrl = url
                                                        isRecording = false
                                                    }
                                                }
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                Image(systemName: "stop.circle.fill")
                                                Text("Stop")
                                            }
                                            .font(.system(size: 16, weight: .medium))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 20)
                                            .padding(.vertical, 12)
                                            .background(Color.red)
                                            .cornerRadius(25)
                                        }
                                    } else {
                                        Button {
                                            requestMicPermission { granted in
                                                guard granted else { micDenied = true; return }
                                                Task {
                                                    _ = try? await recorder?.startRecording()
                                                    await MainActor.run { isRecording = true }
                                                }
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                Image(systemName: "mic.circle.fill")
                                                Text("Record")
                                            }
                                            .font(.system(size: 16, weight: .medium))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 20)
                                            .padding(.vertical, 12)
                                            .background(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Color.gray : Color.accentColor)
                                            .cornerRadius(25)
                                        }
                                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                                    }
                                    
                                    Spacer()
                                }
                                
                                if let url = recordingUrl {
                                    HStack(spacing: 16) {
                                        Button { recorder?.play(url: url) } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "play.circle.fill")
                                                Text("Play")
                                            }
                                            .font(.system(size: 14, weight: .medium))
                                            .foregroundColor(.accentColor)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 8)
                                            .background(Color.accentColor.opacity(0.1))
                                            .cornerRadius(20)
                                        }
                                        
                                        Button { recorder?.stopPlayback() } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "stop.circle")
                                                Text("Stop")
                                            }
                                            .font(.system(size: 14, weight: .medium))
                                            .foregroundColor(.secondary)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 8)
                                            .background(Color(.tertiarySystemBackground))
                                            .cornerRadius(20)
                                        }
                                        
                                        Spacer()
                                    }
                                }
                                
                                if micDenied {
                                    HStack {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .foregroundColor(.orange)
                                        Text("Microphone permission denied. Enable it in Settings.")
                                            .font(.footnote)
                                            .foregroundColor(.secondary)
                                    }
                                    .padding(12)
                                    .background(Color.orange.opacity(0.1))
                                    .cornerRadius(8)
                                }
                            }
                            .padding(16)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 20)
                }
            }
        }
        .interactiveDismissDisabled(false)
    }

    private func requestMicPermission(_ cb: @escaping (Bool) -> Void) {
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission { granted in cb(granted) }
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission { granted in cb(granted) }
        }
    }
}

struct EditPhraseSheet: View {
    let phrase: Shared.Phrase
    let onClose: () -> Void
    let onSave: (String?, String?) -> Void
    let recorder: AudioRecorder?
    // (phraseId, path)
    let saveRecordingPath: ((String,String) -> Void)?
    @State private var text: String = ""
    @State private var name: String = ""
    @State private var recordingUrl: URL? = nil
    @State private var micDenied: Bool = false
    @State private var isRecording: Bool = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Button("category.close", action: onClose)
                    Spacer()
                    Text("phrase.edit.title")
                        .font(.headline)
                        .bold()
                    Spacer()
                    Button("phrase.save") {
                        onSave(text.trimmingCharacters(in: .whitespacesAndNewlines), name.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                    .fontWeight(.semibold)
                    .foregroundColor(.accentColor)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 16)
                
                Divider()
                
                // Content
                ScrollView {
                    VStack(spacing: 20) {
                        // Name input (optional)
                        VStack(alignment: .leading, spacing: 8) {
                            Text("phrase.display_name.optional")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            
                            TextField("common.name", text: $name)
                                .textFieldStyle(.plain)
                                .font(.system(size: 16))
                                .padding(16)
                                .background(Color(.secondarySystemBackground))
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color(.separator), lineWidth: 1)
                                )
                        }
                        
                        // Text input
                        VStack(alignment: .leading, spacing: 8) {
                            Text("phrase.text.placeholder")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            
                            TextEditor(text: $text)
                                .frame(minHeight: 120)
                                .padding(12)
                                .background(Color(.secondarySystemBackground))
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color(.separator), lineWidth: 1)
                                )
                        }
                        
                        // Recording section
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Recording")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            
                            VStack(spacing: 12) {
                                HStack(spacing: 12) {
                                    if isRecording {
                                        Button(role: .destructive) {
                                            Task {
                                                if let url = try? await recorder?.stopRecording() {
                                                    await MainActor.run {
                                                        recordingUrl = url
                                                        isRecording = false
                                                    }
                                                    if let cb = saveRecordingPath {
                                                        cb(phrase.id, url.path)
                                                    }
                                                }
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                Image(systemName: "stop.circle.fill")
                                                Text("Stop")
                                            }
                                            .font(.system(size: 16, weight: .medium))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 20)
                                            .padding(.vertical, 12)
                                            .background(Color.red)
                                            .cornerRadius(25)
                                        }
                                    } else {
                                        Button {
                                            requestMicPermission { granted in
                                                guard granted else { micDenied = true; return }
                                                Task {
                                                    _ = try? await recorder?.startRecording()
                                                    await MainActor.run { isRecording = true }
                                                }
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                Image(systemName: "mic.circle.fill")
                                                Text("Record")
                                            }
                                            .font(.system(size: 16, weight: .medium))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 20)
                                            .padding(.vertical, 12)
                                            .background(Color.accentColor)
                                            .cornerRadius(25)
                                        }
                                    }
                                    
                                    Spacer()
                                }
                                
                                if let url = recordingUrl {
                                    HStack(spacing: 16) {
                                        Button { recorder?.play(url: url) } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "play.circle.fill")
                                                Text("Play")
                                            }
                                            .font(.system(size: 14, weight: .medium))
                                            .foregroundColor(.accentColor)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 8)
                                            .background(Color.accentColor.opacity(0.1))
                                            .cornerRadius(20)
                                        }
                                        
                                        Button { recorder?.stopPlayback() } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "stop.circle")
                                                Text("Stop")
                                            }
                                            .font(.system(size: 14, weight: .medium))
                                            .foregroundColor(.secondary)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 8)
                                            .background(Color(.tertiarySystemBackground))
                                            .cornerRadius(20)
                                        }
                                        
                                        Spacer()
                                    }
                                }
                                
                                if micDenied {
                                    HStack {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .foregroundColor(.orange)
                                        Text("Microphone permission denied. Enable it in Settings.")
                                            .font(.footnote)
                                            .foregroundColor(.secondary)
                                    }
                                    .padding(12)
                                    .background(Color.orange.opacity(0.1))
                                    .cornerRadius(8)
                                }
                            }
                            .padding(16)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 20)
                }
            }
            .onAppear { text = phrase.text; name = phrase.name ?? "" }
        }
        .interactiveDismissDisabled(false)
    }

    private func requestMicPermission(_ cb: @escaping (Bool) -> Void) {
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission { granted in cb(granted) }
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission { granted in cb(granted) }
        }
    }
}

struct AzureSettingsSheet: View {
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
                                _ = try? await bridge.listVoices()
                            } catch {
                                self.error = error.localizedDescription
                                return
                            }
                            onClose()
                        }
                    }
                    .disabled(loading || saving || (endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
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
                    } catch { self.error = error.localizedDescription }
                }
            }
            .overlay(alignment: .top) { if loading { ProgressView().padding(.top, 8) } }
            .alert("common.error", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("common.ok", role: .cancel) { error = nil }
            } message: { Text(error ?? NSLocalizedString("common.unknown_error", comment: "")) }
        }
    }
}

struct VoiceSelectionSheet: View {
    @State private var voices: [Shared.Voice] = []
    @State private var loading = true
    @State private var error: String? = nil
    @State private var selected: Shared.Voice?
    @State private var query: String = ""
    @State private var useSystemTts: Bool = UserDefaults.standard.bool(forKey: "use_system_tts")
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
                // Show system TTS info when enabled
                if useSystemTts {
                    VStack(spacing: 8) {
                        HStack {
                            Image(systemName: "info.circle.fill")
                                .foregroundColor(.blue)
                            Text("System TTS is enabled. Voice selection is handled through iOS Settings.")
                                .font(.subheadline)
                            Spacer()
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 8)
                        .background(Color.blue.opacity(0.1))
                    }
                } else {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                        TextField(NSLocalizedString("voice.search.placeholder", comment: ""), text: $query)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                    }
                    .padding(8)
                    .background(Color(.secondarySystemBackground))
                }

                Group {
                    if useSystemTts {
                        // Show system TTS info
                        VStack(spacing: 16) {
                            Spacer()
                            VStack(spacing: 12) {
                                Image(systemName: "speaker.wave.2.fill")
                                    .font(.system(size: 50))
                                    .foregroundColor(.secondary)
                                Text("Using System Text-to-Speech")
                                    .font(.title2)
                                    .bold()
                                Text("Voice options are managed in iOS Settings > Accessibility > Spoken Content > Voices")
                                    .multilineTextAlignment(.center)
                                    .foregroundColor(.secondary)
                                    .padding(.horizontal)
                            }
                            Spacer()
                        }
                    } else if loading { 
                        ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) 
                    } else if let err = error { 
                        HStack(spacing: 4) { Text("common.error"); Text(err) }.padding() 
                    } else {
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
            .navigationTitle(Text("toolbar.voice"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("category.close", action: onClose) }
                if !useSystemTts {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button { Task { await refreshFromAzure() } } label: { Image(systemName: "arrow.clockwise") }
                        Button("common.select") { if let v = selected { onSelect(v); onClose() } }
                    }
                }
            }
            .onAppear { 
                useSystemTts = UserDefaults.standard.bool(forKey: "use_system_tts")
                if !useSystemTts {
                    Task { await loadInitial() } 
                }
            }
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
            let list = try await bridge.listVoices()
            voices = list
            if list.isEmpty {
                let cloud = try await bridge.refreshVoicesFromAzure()
                voices = cloud
            }
        } catch { self.error = error.localizedDescription }
    }

    private func refreshFromAzure() async {
        loading = true
        defer { loading = false }
        do {
            let cloud = try await bridge.refreshVoicesFromAzure()
            voices = cloud
        } catch { self.error = error.localizedDescription }
    }
}

struct LanguageSelectionSheet: View {
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

struct UiSizeSheet: View {
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

struct ReorderPhrasesSheet: View {
    let phrases: [Shared.Phrase]
    let allPhrases: [Shared.Phrase]
    let onMove: (Int, Int) -> Void
    let onClose: () -> Void

    @State private var local: [Shared.Phrase] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(local, id: \.id) { p in Text(p.name ?? p.text) }
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
        if toLocal > fromLocal { toLocal -= 1 }
        let movingId = local[fromLocal].id
        let targetGlobal: Int = {
            if toLocal >= local.count - 1 {
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
        var updated = local
        let item = updated.remove(at: fromLocal)
        updated.insert(item, at: max(0, min(toLocal, updated.count)))
        self.local = updated
        onMove(fromGlobal, targetGlobal)
    }
}
