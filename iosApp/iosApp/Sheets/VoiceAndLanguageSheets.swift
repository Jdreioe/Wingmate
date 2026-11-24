import SwiftUI
import Shared
import AVFoundation

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
    let primaryLanguage: String?
    let onClose: () -> Void
    let onSelect: (Shared.Voice) -> Void

    init(
        selected: Shared.Voice?,
        primaryLanguage: String? = nil,
        onClose: @escaping () -> Void,
        onSelect: @escaping (Shared.Voice) -> Void
    ) {
        self.current = selected
        self.primaryLanguage = primaryLanguage
        self.onClose = onClose
        self.onSelect = onSelect
        self._selected = State(initialValue: selected)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                    TextField(NSLocalizedString("voice.search.placeholder", comment: ""), text: $query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                }
                .padding(8)
                .background(Color(.secondarySystemBackground))

                if !useSystemTts {
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
                        SystemVoicePickerList(
                            query: query,
                            primaryLanguage: primaryLanguage ?? current?.primaryLanguage ?? Locale.current.identifier
                        )
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

/// Lists every on-device voice iOS exposes to apps (Apple voices, downloaded enhanced/premium
/// voices, Personal Voice and third-party voice provider apps) and stores the choice per language
/// in Wingmate, instead of deferring to iOS Accessibility > Spoken Content defaults.
struct SystemVoicePickerList: View {
    let query: String
    let primaryLanguage: String

    @State private var voices: [AVSpeechSynthesisVoice] = []
    @State private var choices: [String: String] = SystemVoicePreferences.all
    @State private var personalVoiceStatus: AVSpeechSynthesizer.PersonalVoiceAuthorizationStatus =
        AVSpeechSynthesizer.personalVoiceAuthorizationStatus

    private struct LanguageGroup: Identifiable {
        let code: String
        let title: String
        let voices: [AVSpeechSynthesisVoice]
        var id: String { code }
    }

    var body: some View {
        List {
            if personalVoiceStatus == .notDetermined {
                Section {
                    Button {
                        AVSpeechSynthesizer.requestPersonalVoiceAuthorization { status in
                            DispatchQueue.main.async {
                                personalVoiceStatus = status
                                reload()
                            }
                        }
                    } label: {
                        Label("voice.system.personal_voice.allow", systemImage: "person.wave.2")
                    }
                } footer: {
                    Text("voice.system.personal_voice.footer")
                }
            }

            ForEach(groups) { group in
                Section(header: Text(group.title)) {
                    row(
                        title: NSLocalizedString("voice.system.default", comment: ""),
                        subtitle: nil,
                        isSelected: choices[group.code] == nil || !group.voices.contains { $0.identifier == choices[group.code] },
                        preview: nil
                    ) {
                        select(nil, languageCode: group.code)
                    }
                    ForEach(group.voices, id: \.identifier) { voice in
                        row(
                            title: voice.name,
                            subtitle: detail(for: voice),
                            isSelected: choices[group.code] == voice.identifier,
                            preview: voice
                        ) {
                            select(voice.identifier, languageCode: group.code)
                        }
                    }
                }
            }

            Section {
                EmptyView()
            } footer: {
                Text("voice.system_tts.settings_info")
            }
        }
        .onAppear(perform: reload)
        .onReceive(NotificationCenter.default.publisher(for: AVSpeechSynthesizer.availableVoicesDidChangeNotification)) { _ in
            reload()
        }
    }

    private func row(
        title: String,
        subtitle: String?,
        isSelected: Bool,
        preview: AVSpeechSynthesisVoice?,
        action: @escaping () -> Void
    ) -> some View {
        HStack {
            Button(action: action) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                        if let subtitle, !subtitle.isEmpty {
                            Text(subtitle).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                    if isSelected {
                        Image(systemName: "checkmark").foregroundColor(.accentColor)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(isSelected ? .isSelected : [])

            if let preview {
                Button {
                    SystemTtsManager.shared.preview(preview, text: preview.name)
                } label: {
                    Image(systemName: "play.circle")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("voice.system.preview"))
            }
        }
    }

    private func detail(for voice: AVSpeechSynthesisVoice) -> String {
        var parts: [String] = [voice.language]
        if voice.voiceTraits.contains(.isPersonalVoice) {
            parts.append(NSLocalizedString("voice.system.personal", comment: ""))
        }
        switch voice.quality {
        case .premium: parts.append(NSLocalizedString("voice.system.quality.premium", comment: ""))
        case .enhanced: parts.append(NSLocalizedString("voice.system.quality.enhanced", comment: ""))
        default: break
        }
        return parts.joined(separator: " · ")
    }

    private func select(_ identifier: String?, languageCode: String) {
        SystemVoicePreferences.setIdentifier(identifier, forLanguage: languageCode)
        choices = SystemVoicePreferences.all
    }

    private func reload() {
        voices = AVSpeechSynthesisVoice.speechVoices()
        choices = SystemVoicePreferences.all
        personalVoiceStatus = AVSpeechSynthesizer.personalVoiceAuthorizationStatus
    }

    private var groups: [LanguageGroup] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let primaryCode = SystemVoicePreferences.languageCode(of: primaryLanguage)

        func rank(_ v: AVSpeechSynthesisVoice) -> Int {
            if v.voiceTraits.contains(.isPersonalVoice) { return 0 }
            if v.voiceTraits.contains(.isNoveltyVoice) { return 9 }
            switch v.quality {
            case .premium: return 1
            case .enhanced: return 2
            default: return 3
            }
        }

        let grouped = Dictionary(grouping: voices) { SystemVoicePreferences.languageCode(of: $0.language) }
        return grouped.compactMap { code, list -> LanguageGroup? in
            let title = Locale.current.localizedString(forLanguageCode: code)?.capitalized ?? code
            let filtered = q.isEmpty || title.lowercased().contains(q)
                ? list
                : list.filter { $0.name.lowercased().contains(q) || $0.language.lowercased().contains(q) }
            guard !filtered.isEmpty else { return nil }
            let sorted = filtered.sorted {
                let (r0, r1) = (rank($0), rank($1))
                return r0 != r1 ? r0 < r1 : $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
            return LanguageGroup(code: code, title: title, voices: sorted)
        }
        .sorted {
            if $0.code == primaryCode { return true }
            if $1.code == primaryCode { return false }
            return $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
        }
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
