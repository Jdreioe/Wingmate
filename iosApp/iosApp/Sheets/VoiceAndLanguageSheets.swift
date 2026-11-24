import SwiftUI
import Shared

struct VoiceSelectionSheet: View {
    @State private var voices: [Shared.Voice] = []
    @State private var loading = true
    @State private var error: String? = nil
    @State private var selected: Shared.Voice?
    @State private var query: String = ""
    @State private var googleModelFilter: String = ""
    @State private var useSystemTts: Bool = UserDefaults.standard.bool(forKey: "use_system_tts")
    @State private var ttsEngine: String = UserDefaults.standard.string(forKey: "tts_engine") ?? "SYSTEM"
    private let speechFacade = IosDiBridge().speechFacade()

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
                            Text("voice.system_tts.enabled_info")
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

                    if ttsEngine == "GOOGLE_CLOUD" {
                        HStack {
                            Text("voice.model.label")
                            Spacer()
                            Picker("voice.model.label", selection: $googleModelFilter) {
                                Text("voice.model.all").tag("")
                                ForEach(availableGoogleModels, id: \.self) { model in
                                    Text(googleModelLabel(model)).tag(model)
                                }
                            }
                            .pickerStyle(.menu)
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 6)
                    }
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
                                Text("voice.system_tts.using")
                                    .font(.title2)
                                    .bold()
                                Text("voice.system_tts.settings_info")
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
                                Button {
                                    selected = v
                                } label: {
                                    VoiceRow(v: v, isSelected: v.name == selected?.name)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Text(v.displayName ?? v.name ?? NSLocalizedString("common.no_name", comment: "")))
                                .accessibilityValue(Text(v.primaryLanguage ?? ""))
                                .accessibilityHint(Text("accessibility.double_tap_select_voice"))
                                .accessibilityAddTraits(v.name == selected?.name ? .isSelected : [])
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
                        Button { Task { await refreshFromCloud() } } label: { Image(systemName: "arrow.clockwise") }
                        Button("common.select") { if let v = selected { onSelect(v); onClose() } }
                    }
                }
            }
            .onAppear { 
                useSystemTts = UserDefaults.standard.bool(forKey: "use_system_tts")
                ttsEngine = UserDefaults.standard.string(forKey: "tts_engine") ?? (useSystemTts ? "SYSTEM" : "AZURE_USER_RESOURCE")
                if !useSystemTts {
                    Task { await loadInitial() } 
                }
            }
        }
    }

    private var filteredVoices: [Shared.Voice] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return voices.filter { v in
            if ttsEngine == "GOOGLE_CLOUD", !googleModelFilter.isEmpty,
               v.googleModelId != googleModelFilter {
                return false
            }
            guard !q.isEmpty else { return true }
            let name = (v.displayName ?? v.name ?? "").lowercased()
            let locale = (v.primaryLanguage ?? "").lowercased()
            return name.contains(q.lowercased()) || locale.contains(q.lowercased())
        }
    }

    private var availableGoogleModels: [String] {
        let desiredOrder = [
            "GEMINI_3_1_FLASH", "GEMINI_2_5_FLASH", "GEMINI_2_5_FLASH_LITE",
            "GEMINI_2_5_PRO", "CHIRP_3_HD", "STUDIO", "NEURAL2", "WAVENET",
            "STANDARD", "OTHER"
        ]
        let available = Set(voices.compactMap(\.googleModelId))
        return desiredOrder.filter(available.contains)
    }

    private func googleModelLabel(_ model: String) -> String {
        let key: String
        switch model {
        case "GEMINI_3_1_FLASH": key = "voice.model.gemini_3_1_flash"
        case "GEMINI_2_5_FLASH": key = "voice.model.gemini_2_5_flash"
        case "GEMINI_2_5_FLASH_LITE": key = "voice.model.gemini_2_5_flash_lite"
        case "GEMINI_2_5_PRO": key = "voice.model.gemini_2_5_pro"
        case "CHIRP_3_HD": key = "voice.model.chirp_3_hd"
        case "STUDIO": key = "voice.model.studio"
        case "NEURAL2": key = "voice.model.neural2"
        case "WAVENET": key = "voice.model.wavenet"
        case "STANDARD": key = "voice.model.standard"
        default: key = "voice.model.other"
        }
        return NSLocalizedString(key, comment: "")
    }

    private func loadInitial() async {
        loading = true
        defer { loading = false }
        do {
            let list = try await speechFacade.listVoices()
            voices = list
            if list.isEmpty {
                let cloud = try await refreshProviderVoices()
                voices = cloud
            }
        } catch { self.error = error.localizedDescription }
    }

    private func refreshFromCloud() async {
        loading = true
        defer { loading = false }
        do {
            let cloud = try await refreshProviderVoices()
            voices = cloud
        } catch { self.error = error.localizedDescription }
    }

    private func refreshProviderVoices() async throws -> [Shared.Voice] {
        if ttsEngine == "ELEVENLABS" {
            return try await speechFacade.refreshVoicesFromElevenLabs()
        }
        if ttsEngine == "GOOGLE_CLOUD" {
            return try await speechFacade.refreshVoicesFromGoogle()
        }
        return try await speechFacade.refreshVoicesFromAzure()
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
                Button {
                    onSelect(lang)
                } label: {
                    HStack {
                        Text(lang)
                        Spacer()
                        if lang == selected { Image(systemName: "checkmark").foregroundColor(.accentColor) }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(lang))
                .accessibilityHint(Text("accessibility.double_tap_select_language"))
                .accessibilityAddTraits(lang == selected ? .isSelected : [])
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
