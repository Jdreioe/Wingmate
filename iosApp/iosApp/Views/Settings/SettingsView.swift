import SwiftUI
import Shared
import UniformTypeIdentifiers

enum SettingsDestination: String, CaseIterable, Identifiable {
    case speech
    case display
    case accessibility
    case sync
    case privacy
    case general
    case pronunciation

    var id: String { rawValue }

    private var titleLocalizationKey: String {
        switch self {
        case .speech: "settings.category.speech"
        case .display: "settings.category.display"
        case .accessibility: "settings.category.accessibility"
        case .sync: "settings.category.sync"
        case .privacy: "settings.category.privacy"
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
        case .sync: "settings.category.sync.subtitle"
        case .privacy: "settings.category.privacy.subtitle"
        case .general: "settings.category.general.subtitle"
        case .pronunciation: "settings.category.pronunciation.subtitle"
        }
    }

    var symbol: String {
        switch self {
        case .speech: "waveform.and.mic"
        case .display: "textformat.size"
        case .accessibility: "accessibility"
        case .sync: "arrow.triangle.2.circlepath.icloud"
        case .privacy: "hand.raised.fill"
        case .general: "gearshape"
        case .pronunciation: "character.book.closed"
        }
    }

    var tint: Color {
        switch self {
        case .speech: .cyan
        case .display: .orange
        case .accessibility: .pink
        case .sync: .blue
        case .privacy: .indigo
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
        case .sync: keywords = "sync icloud google drive onedrive folder remote screens backup cloud"
        case .privacy: keywords = "privacy history cache analytics reporting logging data"
        case .general: keywords = "general startup screens keyboard setup"
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
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var query = ""
    @State private var selectedDestination: SettingsDestination? = .speech

    private var destinations: [SettingsDestination] {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return SettingsDestination.allCases.filter { $0.matches(normalized) }
    }

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                // On unfolded iPhone Duo and iPad, show categories beside the active pane
                // so settings can be browsed without repeatedly pushing and popping.
                NavigationSplitView {
                    List(selection: $selectedDestination) {
                        if destinations.isEmpty {
                            ContentUnavailableView.search(text: query)
                                .listRowBackground(Color.clear)
                        } else {
                            Section {
                                ForEach(destinations) { destination in
                                    SettingsCategoryLabel(destination: destination)
                                        .tag(destination)
                                }
                            }
                        }
                    }
                    .listStyle(.sidebar)
                    .navigationTitle(Text("settings.title"))
                    .searchable(text: $query, prompt: Text("settings.search"))
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("common.done") { dismiss() }
                        }
                    }
                } detail: {
                    NavigationStack {
                        destinationView(selectedDestination ?? .speech)
                    }
                }
            } else {
                NavigationStack {
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
                }
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
        case .sync:
            SyncSettingsView(model: model)
        case .privacy:
            PrivacySettingsView(model: model, backup: model.backup)
        case .general:
            GeneralSettingsView(model: model, onRestartSetup: onRestartSetup)
        case .pronunciation:
            PronunciationDictionaryView(model: model.pronunciationDictionary)
        }
    }
}

struct SettingsCategoryLabel: View {
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

struct SettingsNavigationLabel: View {
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

struct SettingsSliderRow: View {
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
