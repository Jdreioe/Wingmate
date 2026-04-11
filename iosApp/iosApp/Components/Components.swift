import SwiftUI
import Shared
import UIKit
import Foundation

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
    @Binding var selectedRange: NSRange
    var placeholder: String
    var fontSize: CGFloat
    var minHeight: CGFloat
    var scanEnabled: Bool = false
    var includeInScanArea: Bool = true
    var secondaryLanguage: String
    var secondaryLanguageRanges: [NSRange]
    var allowsSecondaryLanguageAction: Bool
    var onTextChanged: ((String) -> Void)? = nil
    var onTextEdited: ((NSRange, String) -> Void)? = nil
    var onMarkSelectionAsSecondaryLanguage: ((NSRange) -> Void)? = nil

    var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 8).fill(Color(.secondarySystemBackground))
            if text.isEmpty {
                Text(placeholder)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 12)
            }
            SelectableTextView(
                text: $text,
                selectedRange: $selectedRange,
                fontSize: fontSize,
                secondaryLanguage: secondaryLanguage,
                secondaryLanguageRanges: secondaryLanguageRanges,
                allowsSecondaryLanguageAction: allowsSecondaryLanguageAction,
                onTextChanged: onTextChanged,
                onTextEdited: onTextEdited,
                onMarkSelectionAsSecondaryLanguage: { range in
                    onMarkSelectionAsSecondaryLanguage?(range)
                }
            )
            .padding(6)
        }
        .frame(height: minHeight)
        .accessibilityElement(children: .contain)
        .accessibilityHidden(scanEnabled && !includeInScanArea)
    }
}

struct SelectableTextView: UIViewRepresentable {
    @Binding var text: String
    @Binding var selectedRange: NSRange
    let fontSize: CGFloat
    let secondaryLanguage: String
    let secondaryLanguageRanges: [NSRange]
    let allowsSecondaryLanguageAction: Bool
    let onTextChanged: ((String) -> Void)?
    let onTextEdited: ((NSRange, String) -> Void)?
    let onMarkSelectionAsSecondaryLanguage: ((NSRange) -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(
            text: $text,
            selectedRange: $selectedRange,
            onTextChanged: onTextChanged,
            onTextEdited: onTextEdited,
            onMarkSelectionAsSecondaryLanguage: onMarkSelectionAsSecondaryLanguage
        )
    }

    func makeUIView(context: Context) -> UITextView {
        let textView = MenuAwareTextView()
        textView.delegate = context.coordinator
        textView.backgroundColor = .clear
        textView.isScrollEnabled = true
        textView.isEditable = true
        textView.isSelectable = true
        textView.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)
        textView.font = UIFont.systemFont(ofSize: fontSize)
        textView.secondaryLanguageActionTitle = NSLocalizedString("textfield.mark_secondary_language", comment: "")
        textView.allowsSecondaryLanguageAction = allowsSecondaryLanguageAction
        textView.onMarkSelectionAsSecondaryLanguage = { [weak textView] in
            guard let selectedRange = textView?.selectedRange, selectedRange.location != NSNotFound, selectedRange.length > 0 else { return }
            onMarkSelectionAsSecondaryLanguage?(selectedRange)
        }
        context.coordinator.isProgrammaticUpdate = true
        applyHighlighting(to: textView, desiredSelectedRange: selectedRange)
        context.coordinator.isProgrammaticUpdate = false
        return textView
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        context.coordinator.isProgrammaticUpdate = true
        applyHighlighting(to: uiView, desiredSelectedRange: selectedRange)
        uiView.font = UIFont.systemFont(ofSize: fontSize)
        if let menuAwareView = uiView as? MenuAwareTextView {
            menuAwareView.secondaryLanguageActionTitle = NSLocalizedString("textfield.mark_secondary_language", comment: "")
            menuAwareView.allowsSecondaryLanguageAction = allowsSecondaryLanguageAction
        }
        context.coordinator.isProgrammaticUpdate = false
    }

    private func applyHighlighting(to textView: UITextView, desiredSelectedRange: NSRange) {
        let selected = (desiredSelectedRange.location == NSNotFound) ? textView.selectedRange : desiredSelectedRange
        let full = text as NSString
        let attributed = NSMutableAttributedString(string: text)
        attributed.addAttributes([
            .font: UIFont.systemFont(ofSize: fontSize),
            .foregroundColor: UIColor.label
        ], range: NSRange(location: 0, length: full.length))

        for range in secondaryLanguageRanges {
            if range.location != NSNotFound,
               range.length > 0,
               range.location + range.length <= full.length {
                attributed.addAttribute(.backgroundColor, value: UIColor.systemYellow.withAlphaComponent(0.35), range: range)
            }
        }

        if textView.attributedText.string != text {
            textView.attributedText = attributed
        } else {
            textView.textStorage.setAttributedString(attributed)
        }

        let maxPos = textView.text.utf16.count
        let safeLocation = min(max(0, selected.location), maxPos)
        let safeLength = min(max(0, selected.length), max(0, maxPos - safeLocation))
        let clamped = NSRange(location: safeLocation, length: safeLength)
        if !NSEqualRanges(textView.selectedRange, clamped) {
            textView.selectedRange = clamped
        }
        textView.typingAttributes = [
            .font: UIFont.systemFont(ofSize: fontSize),
            .foregroundColor: UIColor.label
        ]
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        @Binding var text: String
        @Binding var selectedRange: NSRange
        let onTextChanged: ((String) -> Void)?
        let onTextEdited: ((NSRange, String) -> Void)?
        let onMarkSelectionAsSecondaryLanguage: ((NSRange) -> Void)?
        var isProgrammaticUpdate: Bool = false

        init(
            text: Binding<String>,
            selectedRange: Binding<NSRange>,
            onTextChanged: ((String) -> Void)?,
            onTextEdited: ((NSRange, String) -> Void)?,
            onMarkSelectionAsSecondaryLanguage: ((NSRange) -> Void)?
        ) {
            self._text = text
            self._selectedRange = selectedRange
            self.onTextChanged = onTextChanged
            self.onTextEdited = onTextEdited
            self.onMarkSelectionAsSecondaryLanguage = onMarkSelectionAsSecondaryLanguage
        }

        func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText replacement: String) -> Bool {
            if isProgrammaticUpdate { return true }
            onTextEdited?(range, replacement)
            return true
        }

        func textViewDidChange(_ textView: UITextView) {
            if isProgrammaticUpdate { return }
            text = textView.text ?? ""
            onTextChanged?(text)
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            if isProgrammaticUpdate { return }
            let latestSelection = textView.selectedRange
            DispatchQueue.main.async {
                if !NSEqualRanges(self.selectedRange, latestSelection) {
                    self.selectedRange = latestSelection
                }
            }
        }
    }
}

final class MenuAwareTextView: UITextView {
    var secondaryLanguageActionTitle: String = NSLocalizedString("textfield.mark_secondary_language", comment: "")
    var allowsSecondaryLanguageAction: Bool = true
    var onMarkSelectionAsSecondaryLanguage: (() -> Void)?

    override var canBecomeFirstResponder: Bool { true }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(markSelectedTextAsSecondaryLanguage) {
            return allowsSecondaryLanguageAction && selectedRange.length > 0
        }
        return super.canPerformAction(action, withSender: sender)
    }

    override func buildMenu(with builder: UIMenuBuilder) {
        super.buildMenu(with: builder)
        guard allowsSecondaryLanguageAction, selectedRange.length > 0 else { return }
        let action = UIAction(title: secondaryLanguageActionTitle, image: UIImage(systemName: "globe.badge.chevron.backward")) { [weak self] _ in
            self?.markSelectedTextAsSecondaryLanguage()
        }
        builder.insertSibling(UIMenu(title: "", children: [action]), afterMenu: .standardEdit)
    }

    @objc func markSelectedTextAsSecondaryLanguage() {
        onMarkSelectionAsSecondaryLanguage?()
    }
}

struct CategoriesRowView: View {
    let state: Shared.PhraseListStoreState
    let chipFontSize: CGFloat
    let chipHPadding: CGFloat
    let chipVPadding: CGFloat
    let onSelect: (String?) -> Void
    let onDelete: (String) -> Void
    let onAddCategory: () -> Void
    // Optional History chip
    var showHistoryChip: Bool = false
    var isHistorySelected: Bool = false
    var onSelectHistory: (() -> Void)? = nil

    private func priorityForIndex(_ index: Int) -> Double {
        Double(10_000 - index)
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                CategoryChip(title: NSLocalizedString("categories.all", comment: ""),
                             selected: state.selectedCategoryId == nil && !isHistorySelected,
                             fontSize: chipFontSize,
                             hPadding: chipHPadding,
                             vPadding: chipVPadding) { onSelect(nil) }
                .accessibilitySortPriority(priorityForIndex(0))
                if showHistoryChip {
                    CategoryChip(title: NSLocalizedString("categories.history", comment: "History"),
                                 selected: isHistorySelected,
                                 fontSize: chipFontSize,
                                 hPadding: chipHPadding,
                                 vPadding: chipVPadding) { onSelectHistory?() }
                    .accessibilitySortPriority(priorityForIndex(1))
                }
                let categoryStartIndex = showHistoryChip ? 2 : 1
                ForEach(Array(state.categories.enumerated()), id: \.element.id) { offset, cat in
                    CategoryChip(title: cat.name ?? NSLocalizedString("common.no_name", comment: ""),
                                 selected: state.selectedCategoryId == cat.id,
                                 fontSize: chipFontSize,
                                 hPadding: chipHPadding,
                                 vPadding: chipVPadding) { onSelect(cat.id) }
                    .accessibilitySortPriority(priorityForIndex(categoryStartIndex + offset))
                    .contextMenu {
                        Button(role: .destructive) { onDelete(cat.id) } label: { Label("category.delete", systemImage: "trash") }
                    }
                }
                Button(action: onAddCategory) {
                    Image(systemName: "plus")
                        .font(.system(size: max(14, chipFontSize * 0.85), weight: .semibold))
                        .padding(.horizontal, chipHPadding)
                        .padding(.vertical, chipVPadding)
                        .background(Capsule().fill(Color(.secondarySystemBackground)))
                        .overlay(Capsule().stroke(Color(.separator), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("toolbar.add_category"))
                .accessibilitySortPriority(priorityForIndex(categoryStartIndex + state.categories.count))
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 4)
            .accessibilityElement(children: .contain)
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
        let title = phrase.name?.trimmingCharacters(in: .whitespacesAndNewlines)
        let accessibleName = ((title?.isEmpty == false ? title : phrase.text) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        Button(action: { model.insertPhraseText(phrase) }) {
            VStack(alignment: .leading, spacing: 6) {
                if let imageUrl = phrase.imageUrl,
                   let url = URL(string: imageUrl) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: .infinity, maxHeight: 80)
                        case .failure(_):
                            EmptyView()
                        case .empty:
                            ProgressView()
                                .frame(maxWidth: .infinity, minHeight: 44)
                        @unknown default:
                            EmptyView()
                        }
                    }
                }

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
        .accessibilityLabel(Text(accessibleName.isEmpty ? NSLocalizedString("common.no_name", comment: "") : accessibleName))
        .accessibilityHint(Text(wiggle ? "accessibility.phrase.wiggle_hint" : "accessibility.phrase.insert_hint"))
        .accessibilityAction(named: Text("accessibility.phrase.speak_action")) { model.speak(phrase.text) }
        .accessibilityAction(named: Text("accessibility.phrase.edit_action")) {
            if !phrase.id.hasPrefix("history-") {
                onEdit()
            }
        }
        .accessibilityAction(named: Text("accessibility.phrase.delete_action")) {
            if !phrase.id.hasPrefix("history-") {
                onDelete(phrase.id)
            }
        }
        .modifier(WiggleEffect(active: wiggle))
        .contextMenu {
            // Hide edit/record/delete for history items
            let isHistory = phrase.id.hasPrefix("history-")
            if !isHistory { Button { onEdit() } label: { Label("phrase.edit", systemImage: "pencil") } }
            Button { model.speak(phrase.text) } label: { Label("phrase.play_tts", systemImage: "speaker.wave.2.fill") }
            // Prefer phrase.recordingPath (works for history pseudo-phrases)
            if let direct = phrase.recordingPath, !direct.isEmpty {
                Button { recorder.play(url: URL(fileURLWithPath: direct)) } label: { Label("phrase.play_recording", systemImage: "waveform") }
            } else if let path = model.recordingPath(for: phrase.id) {
                Button { recorder.play(url: URL(fileURLWithPath: path)) } label: { Label("phrase.play_recording", systemImage: "waveform") }
                if !isHistory { Button { requestMic(phrase.id) } label: { Label("phrase.record.replace", systemImage: "mic") } }
            } else if !isHistory {
                Button { requestMic(phrase.id) } label: { Label("phrase.record", systemImage: "mic") }
            }
            if !isHistory { Button(role: .destructive) { onDelete(phrase.id) } label: { Label("phrase.delete", systemImage: "trash") } }
        }
    }
}
