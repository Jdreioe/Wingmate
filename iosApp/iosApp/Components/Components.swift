import SwiftUI
import Shared

struct CategoryChip: View {
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

struct LanguageChip: View {
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

struct MultiLineInput: View {
    @Binding var text: String
    var placeholder: String
    var fontSize: CGFloat
    var minHeight: CGFloat

    var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 8).fill(Color(.secondarySystemBackground))
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
        .frame(height: minHeight)
    }
}

struct CategoriesRowView: View {
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

struct VoiceRow: View {
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

struct PhraseItemView: View {
    @ObservedObject var model: IosViewModel
    let phrase: Shared.Phrase
    let recorder: AudioRecorder
    @Binding var recordingForPhraseId: String?
    let onEdit: () -> Void
    let requestMic: (String) -> Void
    let onDelete: (String) -> Void
    var wiggle: Bool = false

    var body: some View {
    let bgHex = phrase.backgroundColor ?? "#00000000"
    let useDefaultBg = bgHex == "#00000000"
    let bgColor = useDefaultBg ? Color(.secondarySystemBackground) : Color(hex: bgHex)
    let tileShape = RoundedRectangle(cornerRadius: 12, style: .continuous)

        Button(action: { model.insertPhraseText(phrase) }) {
            VStack(alignment: .leading, spacing: 6) {
                Text(phrase.name ?? phrase.text)
                    .font(.body)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Secondary hint could be added here if needed
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: UIDevice.current.userInterfaceIdiom == .pad ? 150 : 120)
            .background(tileShape.fill(bgColor))
            .overlay(tileShape.stroke(Color(.separator), lineWidth: 1))
            .contentShape(tileShape)
        }
        .buttonStyle(.plain)
        .modifier(WiggleEffect(active: wiggle))
        .contextMenu {
            Button { onEdit() } label: { Label("phrase.edit", systemImage: "pencil") }
            Button { model.speak(phrase.text) } label: { Label("phrase.play_tts", systemImage: "speaker.wave.2.fill") }
            if let path = model.recordingPath(for: phrase.id) {
                Button { recorder.play(url: URL(fileURLWithPath: path)) } label: { Label("phrase.play_recording", systemImage: "waveform") }
                Button { requestMic(phrase.id) } label: { Label("phrase.record.replace", systemImage: "mic") }
            } else {
                Button { requestMic(phrase.id) } label: { Label("phrase.record", systemImage: "mic") }
            }
            Button(role: .destructive) { onDelete(phrase.id) } label: { Label("phrase.delete", systemImage: "trash") }
        }
    }
}
