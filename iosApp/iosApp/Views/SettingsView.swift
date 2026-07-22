import SwiftUI
import Shared

private enum SettingsDestination: String, CaseIterable, Identifiable {
    case speech
    case display
    case accessibility
    case general
    case pronunciation

    var id: String { rawValue }

    private var titleLocalizationKey: String {
        switch self {
        case .speech: "settings.category.speech"
        case .display: "settings.category.display"
        case .accessibility: "settings.category.accessibility"
        case .general: "settings.category.general"
        case .pronunciation: "pronunciation.title"
        }
    }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(titleLocalizationKey)
    }

    var subtitleKey: LocalizedStringKey {
        switch self {
        case .speech: "settings.category.speech.subtitle"
        case .display: "settings.category.display.subtitle"
        case .accessibility: "settings.category.accessibility.subtitle"
        case .general: "settings.category.general.subtitle"
        case .pronunciation: "settings.category.pronunciation.subtitle"
        }
    }

    var symbol: String {
        switch self {
        case .speech: "waveform.and.mic"
        case .display: "textformat.size"
        case .accessibility: "accessibility"
        case .general: "gearshape"
        case .pronunciation: "character.book.closed"
        }
    }

    var tint: Color {
        switch self {
        case .speech: .cyan
        case .display: .orange
        case .accessibility: .pink
        case .general: .green
        case .pronunciation: .purple
        }
    }

    func matches(_ query: String) -> Bool {
        guard !query.isEmpty else { return true }
        let title = NSLocalizedString(titleLocalizationKey, comment: "").lowercased()
        let keywords: String
        switch self {
        case .speech: keywords = "speech voice language tts azure system pronunciation audio playback"
        case .display: keywords = "display layout symbols labels grid columns size scaling contrast"
        case .accessibility: keywords = "accessibility scanning dwell hold feedback sound logging"
        case .general: keywords = "general startup screens keyboard analytics privacy setup"
        case .pronunciation: keywords = "pronunciation dictionary phoneme ipa words"
        }
        return title.contains(query) || keywords.contains(query)
    }
}

struct SettingsView: View {
    @ObservedObject var model: IosViewModel
    @Binding var uiTextFieldHeight: Double
    @Binding var uiInputFontSize: Double
    @Binding var uiChipFontSize: Double
    @Binding var uiPlayIconSize: Double
    let onRestartSetup: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var destinations: [SettingsDestination] {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return SettingsDestination.allCases.filter { $0.matches(normalized) }
    }

    var body: some View {
        List {
            if destinations.isEmpty {
                ContentUnavailableView.search(text: query)
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(destinations) { destination in
                        NavigationLink {
                            destinationView(destination)
                        } label: {
                            SettingsCategoryLabel(destination: destination)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(Text("settings.title"))
        .navigationBarTitleDisplayMode(.large)
        .searchable(text: $query, prompt: Text("settings.search"))
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("common.done") { dismiss() }
            }
        }
        .task {
            await model.refreshParitySettings()
            await model.loadBoardSets()
        }
    }

    @ViewBuilder
    private func destinationView(_ destination: SettingsDestination) -> some View {
        switch destination {
        case .speech:
            SpeechSettingsView(model: model)
        case .display:
            DisplaySettingsView(
                model: model,
                uiTextFieldHeight: $uiTextFieldHeight,
                uiInputFontSize: $uiInputFontSize,
                uiChipFontSize: $uiChipFontSize,
                uiPlayIconSize: $uiPlayIconSize
            )
        case .accessibility:
            AccessibilitySettingsView(model: model)
        case .general:
            GeneralSettingsView(model: model, onRestartSetup: onRestartSetup)
        case .pronunciation:
            PronunciationDictionaryView(model: model)
        }
    }
}

private struct SettingsCategoryLabel: View {
    let destination: SettingsDestination

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: destination.symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 38, height: 38)
                .background(destination.tint.gradient, in: RoundedRectangle(cornerRadius: 9))

            VStack(alignment: .leading, spacing: 3) {
                Text(destination.titleKey).font(.body)
                Text(destination.subtitleKey)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct SpeechSettingsView: View {
    @ObservedObject var model: IosViewModel
    @State private var showVoicePicker = false
    @State private var showAzureSetup = false

    private var languages: [String] {
        model.availableLanguages.isEmpty ? [model.primaryLanguage] : model.availableLanguages
    }

    var body: some View {
        Form {
            Section("settings.tts.title") {
                Picker("settings.speech.engine", selection: Binding(
                    get: { model.useSystemTts },
                    set: { model.setUseSystemTts($0) }
                )) {
                    Text("settings.speech.engine.azure").tag(false)
                    Text("settings.speech.engine.system").tag(true)
                }
                .pickerStyle(.segmented)

                Toggle("settings.tts.system_when_offline", isOn: Binding(
                    get: { model.useSystemTtsWhenOffline },
                    set: { model.setUseSystemTtsWhenOffline($0) }
                ))
                .disabled(model.useSystemTts)

                if !model.useSystemTts {
                    Button {
                        showAzureSetup = true
                    } label: {
                        SettingsNavigationLabel(
                            title: "settings.speech.azure.configure",
                            value: model.azureConfigured ? "settings.speech.azure.configured" : "settings.speech.azure.not_configured"
                        )
                    }
                }
            }

            Section("toolbar.voice") {
                Button { showVoicePicker = true } label: {
                    SettingsNavigationLabel(
                        title: "settings.speech.voice.choose",
                        value: (model.selectedVoice?.displayName ?? model.selectedVoice?.name) ?? "—"
                    )
                }

                Picker("toolbar.language", selection: Binding(
                    get: { model.primaryLanguage },
                    set: { model.updateLanguage($0) }
                )) {
                    ForEach(languages, id: \.self) { Text($0).tag($0) }
                }

                if model.canChangeVoiceLanguage {
                    Picker("toolbar.second_language", selection: Binding(
                        get: { model.secondaryLanguage },
                        set: { model.updateSecondaryLanguage($0) }
                    )) {
                        Text("settings.speech.language.none").tag("")
                        ForEach(languages.filter { $0 != model.primaryLanguage }, id: \.self) { Text($0).tag($0) }
                    }
                }
            }

            Section {
                Toggle("settings.playback.mix_recorded", isOn: Binding(
                    get: { model.mixRecordedPhrasesInSentences },
                    set: { model.setMixRecordedPhrases($0) }
                ))
            } header: {
                Text("settings.playback.title")
            } footer: {
                Text("settings.playback.mix.help")
            }
        }
        .navigationTitle(Text("settings.category.speech"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showVoicePicker) {
            VoiceSelectionSheet(selected: model.selectedVoice, onClose: { showVoicePicker = false }) { voice in
                Task {
                    await model.chooseVoice(voice)
                    showVoicePicker = false
                }
            }
        }
        .sheet(isPresented: $showAzureSetup) {
            F0SetupView(
                onDone: {
                    showAzureSetup = false
                    Task { await model.refreshAzureConfiguration() }
                },
                onBack: { showAzureSetup = false }
            )
        }
    }
}

private struct DisplaySettingsView: View {
    @ObservedObject var model: IosViewModel
    @Binding var uiTextFieldHeight: Double
    @Binding var uiInputFontSize: Double
    @Binding var uiChipFontSize: Double
    @Binding var uiPlayIconSize: Double

    var body: some View {
        Form {
            Section("settings.display.grid") {
                Toggle("settings.display.show_labels", isOn: Binding(
                    get: { model.showButtonLabels }, set: { model.setShowButtonLabels($0) }
                ))
                Toggle("settings.display.show_symbols", isOn: Binding(
                    get: { model.showButtonSymbols }, set: { model.setShowButtonSymbols($0) }
                ))
                if model.showButtonLabels && model.showButtonSymbols {
                    Toggle("settings.display.label_top", isOn: Binding(
                        get: { model.labelAtTop }, set: { model.setLabelAtTop($0) }
                    ))
                }
                Stepper(value: Binding(
                    get: { model.preferredGridColumns }, set: { model.setPreferredGridColumns($0) }
                ), in: 1...6) {
                    LabeledContent("settings.display.columns", value: "\(model.preferredGridColumns)")
                }
                Toggle("settings.display.high_contrast", isOn: Binding(
                    get: { model.highContrastMode }, set: { model.setHighContrastMode($0) }
                ))
            }

            Section("settings.display.interface_size") {
                SettingsSliderRow(title: "settings.ui_size.input_height", value: $uiTextFieldHeight, range: 44...160, step: 2)
                SettingsSliderRow(title: "settings.ui_size.input_font", value: $uiInputFontSize, range: 14...30, step: 1)
                SettingsSliderRow(title: "settings.ui_size.chip_font", value: $uiChipFontSize, range: 12...28, step: 1)
                SettingsSliderRow(title: "settings.ui_size.playback_icon", value: $uiPlayIconSize, range: 28...64, step: 1)
            }
        }
        .navigationTitle(Text("settings.category.display"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AccessibilitySettingsView: View {
    @ObservedObject var model: IosViewModel

    var body: some View {
        Form {
            Section("settings.accessibility.touch_timing") {
                SettingsSliderRow(
                    title: "settings.accessibility.hold",
                    value: Binding(get: { model.holdToSelectMillis }, set: { model.setHoldToSelectMillis($0) }),
                    range: 0...2_000,
                    step: 100,
                    suffix: " ms"
                )
                SettingsSliderRow(
                    title: "settings.accessibility.dwell",
                    value: Binding(get: { model.dwellToSelectMillis }, set: { model.setDwellToSelectMillis($0) }),
                    range: 0...5_000,
                    step: 250,
                    suffix: " ms"
                )
            }

            Section("settings.accessibility.feedback") {
                Toggle("settings.accessibility.selection_sound", isOn: Binding(
                    get: { model.selectionSoundEnabled }, set: { model.setSelectionSoundEnabled($0) }
                ))
                Toggle("settings.accessibility.auditory_fishing", isOn: Binding(
                    get: { model.auditoryFishingEnabled }, set: { model.setAuditoryFishingEnabled($0) }
                ))
                Toggle("settings.accessibility.usage_logging", isOn: Binding(
                    get: { model.usageLoggingEnabled }, set: { model.setUsageLoggingEnabled($0) }
                ))
            }

            Section("settings.scanning.title") {
                Toggle("settings.scanning.enable", isOn: Binding(
                    get: { model.scanningEnabled }, set: { model.setScanningEnabled($0) }
                ))
                Group {
                    Toggle("settings.scanning.playback_area", isOn: Binding(get: { model.scanPlaybackAreaEnabled }, set: { model.setScanPlaybackAreaEnabled($0) }))
                    Toggle("settings.scanning.input_field", isOn: Binding(get: { model.scanInputFieldEnabled }, set: { model.setScanInputFieldEnabled($0) }))
                    Toggle("settings.scanning.phrase_grid", isOn: Binding(get: { model.scanPhraseGridEnabled }, set: { model.setScanPhraseGridEnabled($0) }))
                    Toggle("settings.scanning.category_items", isOn: Binding(get: { model.scanCategoryItemsEnabled }, set: { model.setScanCategoryItemsEnabled($0) }))
                    Toggle("settings.scanning.topbar", isOn: Binding(get: { model.scanTopBarEnabled }, set: { model.setScanTopBarEnabled($0) }))
                    Picker("settings.scanning.grid_order", selection: Binding(get: { model.scanPhraseGridOrder }, set: { model.setScanPhraseGridOrder($0) })) {
                        Text("settings.scanning.grid_order.row_major").tag("row-major")
                        Text("settings.scanning.grid_order.column_major").tag("column-major")
                        Text("settings.scanning.grid_order.linear").tag("linear")
                    }
                    SettingsSliderRow(title: "settings.scanning.dwell", value: Binding(get: { model.scanDwellTimeSeconds }, set: { model.setScanDwellTimeSeconds($0) }), range: 0.3...2.0, step: 0.1, decimals: 1)
                    SettingsSliderRow(title: "settings.scanning.auto_advance", value: Binding(get: { model.scanAutoAdvanceSeconds }, set: { model.setScanAutoAdvanceSeconds($0) }), range: 0.5...3.0, step: 0.1, decimals: 1)
                }
                .disabled(!model.scanningEnabled)
            }
        }
        .navigationTitle(Text("settings.category.accessibility"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct GeneralSettingsView: View {
    @ObservedObject var model: IosViewModel
    let onRestartSetup: () -> Void

    var body: some View {
        Form {
            Section {
                Picker("settings.general.startup_mode", selection: Binding(
                    get: { model.startupUsesScreens }, set: { model.setStartupUsesScreens($0) }
                )) {
                    Text("settings.general.keyboard").tag(false)
                    Text("settings.general.screens").tag(true)
                }
                .pickerStyle(.segmented)

                if model.startupUsesScreens {
                    Picker("settings.general.startup_screen", selection: Binding(
                        get: { model.startupBoardSetId ?? "" },
                        set: { model.setStartupBoardSetId($0.isEmpty ? nil : $0) }
                    )) {
                        Text("settings.general.screen_library").tag("")
                        ForEach(model.boardSets) { set in
                            Text(set.name).tag(set.id)
                        }
                    }
                }
            } header: {
                Text("settings.general.startup")
            } footer: {
                Text("settings.general.startup.footer")
            }

            Section {
                Toggle("settings.general.analytics", isOn: Binding(
                    get: { model.featureUsageReportingEnabled },
                    set: { model.setFeatureUsageReportingEnabled($0) }
                ))
            } header: {
                Text("settings.general.privacy")
            } footer: {
                Text("settings.general.analytics.footer")
            }

            Section {
                Button(action: onRestartSetup) {
                    Label("settings.restart_setup", systemImage: "arrow.counterclockwise")
                }
            }
        }
        .navigationTitle(Text("settings.category.general"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct SettingsNavigationLabel: View {
    let title: LocalizedStringKey
    let value: LocalizedStringKey

    init(title: LocalizedStringKey, value: String) {
        self.title = title
        self.value = LocalizedStringKey(value)
    }

    var body: some View {
        HStack {
            Text(title).foregroundStyle(.primary)
            Spacer()
            Text(value).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

private struct SettingsSliderRow: View {
    let title: LocalizedStringKey
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    var suffix: String = ""
    var decimals: Int = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            LabeledContent {
                Text(value.formatted(.number.precision(.fractionLength(decimals))) + suffix)
                    .foregroundStyle(.secondary)
            } label: {
                Text(title)
            }
            Slider(value: $value, in: range, step: step)
        }
        .padding(.vertical, 2)
    }
}
