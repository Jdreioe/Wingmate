import SwiftUI
import Shared
import UIKit
import Foundation
import AudioToolbox

private struct AccessTargetFocusModifier: ViewModifier {
    @ObservedObject var model: IosViewModel
    let targetId: String
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focusable()
            .focused($focused)
            .onChange(of: focused) { _, value in
                if value { model.accessFocus(targetId) } else { model.accessBlur(targetId) }
            }
    }
}

extension View {
    func accessTargetFocus(model: IosViewModel, targetId: String) -> some View {
        modifier(AccessTargetFocusModifier(model: model, targetId: targetId))
    }
}

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
                .frame(minHeight: 44)
                .background(selected ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12))
                .foregroundStyle(selected ? Color.accentColor : Color.primary)
                .clipShape(Capsule())
                .overlay {
                    // Selection is not color alone.
                    if selected { Capsule().stroke(Color.accentColor, lineWidth: 2) }
                }
        }.buttonStyle(.plain)
    }
}

/// The Message field: grows with the Message from [minHeight] up to [maxHeight], then scrolls.
struct MultiLineInput: View {
    @Binding var text: String
    @Binding var selectedRange: NSRange
    @Binding var isFocused: Bool
    var placeholder: String
    var fontSize: CGFloat
    var minHeight: CGFloat
    var maxHeight: CGFloat
    var scanEnabled: Bool = false
    var includeInScanArea: Bool = true
    var secondaryLanguage: String
    var secondaryLanguageRanges: [NSRange]
    var allowsSecondaryLanguageAction: Bool
    var onMarkSelectionAsSecondaryLanguage: ((NSRange) -> Void)? = nil

    var body: some View {
        ZStack(alignment: .topLeading) {
            // The message-bar look shared with Screens: rounded, translucent, no outline.
            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(.secondarySystemFill))
            if text.isEmpty {
                Text(placeholder)
                    .font(.system(size: fontSize))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 16)
                    .accessibilityHidden(true)
            }
            SelectableTextView(
                text: $text,
                selectedRange: $selectedRange,
                isFocused: $isFocused,
                fontSize: fontSize,
                secondaryLanguage: secondaryLanguage,
                secondaryLanguageRanges: secondaryLanguageRanges,
                allowsSecondaryLanguageAction: allowsSecondaryLanguageAction,
                onMarkSelectionAsSecondaryLanguage: { range in
                    onMarkSelectionAsSecondaryLanguage?(range)
                }
            )
            .padding(6)
            .accessibilityLabel(Text(placeholder))
        }
        .frame(minHeight: minHeight, maxHeight: max(minHeight, maxHeight))
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .contain)
        .accessibilityHidden(scanEnabled && !includeInScanArea)
    }
}

struct SelectableTextView: UIViewRepresentable {
    @Binding var text: String
    @Binding var selectedRange: NSRange
    @Binding var isFocused: Bool
    let fontSize: CGFloat
    let secondaryLanguage: String
    let secondaryLanguageRanges: [NSRange]
    let allowsSecondaryLanguageAction: Bool
    let onMarkSelectionAsSecondaryLanguage: ((NSRange) -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text, selectedRange: $selectedRange, isFocused: $isFocused)
    }

    /// Sizes to the text's height, so the field grows with the Message.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        guard let width = proposal.width, width > 0 else { return nil }
        let fitting = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: fitting.height)
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
        textView.autocorrectionType = .no
        textView.autocapitalizationType = .none
        textView.spellCheckingType = .no
        textView.smartQuotesType = .no
        textView.smartDashesType = .no
        textView.smartInsertDeleteType = .no
        textView.secondaryLanguageActionTitle = NSLocalizedString("textfield.mark_secondary_language", comment: "")
        textView.allowsSecondaryLanguageAction = allowsSecondaryLanguageAction
        textView.onMarkSelectionAsSecondaryLanguage = { [weak textView] in
            guard let selectedRange = textView?.selectedRange, selectedRange.location != NSNotFound, selectedRange.length > 0 else { return }
            onMarkSelectionAsSecondaryLanguage?(selectedRange)
        }
        context.coordinator.isProgrammaticUpdate = true
        applyHighlighting(to: textView, desiredSelectedRange: selectedRange, force: true, coordinator: context.coordinator)
        context.coordinator.isProgrammaticUpdate = false
        return textView
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        context.coordinator.isProgrammaticUpdate = true
        applyHighlighting(to: uiView, desiredSelectedRange: selectedRange, force: false, coordinator: context.coordinator)
        if let menuAwareView = uiView as? MenuAwareTextView {
            menuAwareView.secondaryLanguageActionTitle = NSLocalizedString("textfield.mark_secondary_language", comment: "")
            menuAwareView.allowsSecondaryLanguageAction = allowsSecondaryLanguageAction
        }
        context.coordinator.isProgrammaticUpdate = false
        // Focus changes raise or dismiss the keyboard, so apply them after this update.
        if isFocused != uiView.isFirstResponder {
            let shouldFocus = isFocused
            DispatchQueue.main.async {
                if shouldFocus { uiView.becomeFirstResponder() } else { uiView.resignFirstResponder() }
            }
        }
    }

    private func applyHighlighting(
        to textView: UITextView,
        desiredSelectedRange: NSRange,
        force: Bool,
        coordinator: Coordinator
    ) {
        let selected = (desiredSelectedRange.location == NSNotFound) ? textView.selectedRange : desiredSelectedRange
        let full = text as NSString
        let length = full.length
        let validRanges = secondaryLanguageRanges.filter {
            $0.location != NSNotFound && $0.length > 0 && $0.location + $0.length <= length
        }
        let textChangedExternally = textView.text != text
        let formattingChanged = !rangesEqual(validRanges, coordinator.appliedHighlightRanges)
            || (coordinator.appliedFontSize.map { $0 != fontSize } ?? true)
        let needsTextUpdate = force || textChangedExternally || formattingChanged

        guard length > 0 else {
            if needsTextUpdate && textView.attributedText.length != 0 {
                textView.attributedText = NSAttributedString()
            }
            coordinator.appliedHighlightRanges = []
            coordinator.appliedFontSize = fontSize
            return
        }

        let hasHighlights = !validRanges.isEmpty

        if needsTextUpdate && hasHighlights {
            let attributed = NSMutableAttributedString(string: text)
            attributed.addAttributes([
                .font: UIFont.systemFont(ofSize: fontSize),
                .foregroundColor: UIColor.label
            ], range: NSRange(location: 0, length: length))

            for range in validRanges {
                attributed.addAttribute(.backgroundColor, value: UIColor.systemYellow.withAlphaComponent(0.35), range: range)
            }

            textView.attributedText = attributed
        } else if needsTextUpdate {
            textView.attributedText = NSAttributedString(string: text, attributes: [
                .font: UIFont.systemFont(ofSize: fontSize),
                .foregroundColor: UIColor.label
            ])
        }

        coordinator.appliedHighlightRanges = validRanges
        coordinator.appliedFontSize = fontSize

        let maxPos = textView.text.utf16.count
        let safeLocation = min(max(0, selected.location), maxPos)
        let safeLength = min(max(0, selected.length), max(0, maxPos - safeLocation))
        let clamped = NSRange(location: safeLocation, length: safeLength)
        if !NSEqualRanges(textView.selectedRange, clamped) {
            textView.selectedRange = clamped
        }
        if needsTextUpdate {
            textView.typingAttributes = [
                .font: UIFont.systemFont(ofSize: fontSize),
                .foregroundColor: UIColor.label
            ]
        }
    }

    private func rangesEqual(_ lhs: [NSRange], _ rhs: [NSRange]) -> Bool {
        lhs.count == rhs.count && zip(lhs, rhs).allSatisfy { NSEqualRanges($0, $1) }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        @Binding var text: String
        @Binding var selectedRange: NSRange
        @Binding var isFocused: Bool
        var isProgrammaticUpdate: Bool = false
        var appliedHighlightRanges: [NSRange] = []
        var appliedFontSize: CGFloat? = nil

        init(text: Binding<String>, selectedRange: Binding<NSRange>, isFocused: Binding<Bool>) {
            self._text = text
            self._selectedRange = selectedRange
            self._isFocused = isFocused
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            if !isFocused { isFocused = true }
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            if isFocused { isFocused = false }
        }

        func textViewDidChange(_ textView: UITextView) {
            if isProgrammaticUpdate { return }
            // Record the cursor before the text, so the Message mirror keeps it.
            selectedRange = textView.selectedRange
            text = textView.text ?? ""
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            if isProgrammaticUpdate { return }
            let latestSelection = textView.selectedRange
            if !NSEqualRanges(selectedRange, latestSelection) {
                selectedRange = latestSelection
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

/// A Phrase (or History item) in the Typing tray. Tapping activates it per the Typing
/// Screen; a long press opens Edit, recording, and Delete, and keeps holding to drag it
/// onto another Phrase's place. VoiceOver gets the same actions, including moves.
struct PhraseItemView: View {
    @ObservedObject var model: IosViewModel
    let phrase: Shared.Phrase
    let recorder: AudioRecorder
    /// False for History and while hold-to-select owns long presses.
    let editable: Bool
    var minHeight: CGFloat = UIDevice.current.userInterfaceIdiom == .pad ? 120 : 88
    let onEdit: () -> Void
    let requestMic: (String) -> Void
    let onDelete: (String) -> Void
    var onMoveEarlier: (() -> Void)? = nil
    var onMoveLater: (() -> Void)? = nil
    var onDrop: ((String) -> Void)? = nil

    var body: some View {
        let bgHex = phrase.backgroundColor ?? "#00000000"
        let useDefaultBg = bgHex == "#00000000"
        let bgColor = useDefaultBg ? Color(.tertiarySystemBackground) : Color(hex: bgHex)
        let tileShape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        // VoiceOver reads the visible label; the vocalization is what gets spoken.
        let accessibleName = phrase.text.trimmingCharacters(in: .whitespacesAndNewlines)
        let accessTargetId = "phrase:\(phrase.id)"
        let recordingPath = phrase.recordingPath.flatMap { $0.isEmpty ? nil : $0 }

        let tile = Button(action: {
            guard model.holdToSelectMillis <= 0 else { return }
            if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
            model.activateTypingPhrase(phrase)
        }) {
            VStack(alignment: .leading, spacing: 6) {
                if model.labelAtTop && model.showButtonLabels {
                    Text(phrase.text)
                        .font(.body)
                        .lineLimit(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                if model.showButtonSymbols, let imageUrl = phrase.imageUrl,
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

                if !model.labelAtTop && model.showButtonLabels {
                    Text(phrase.text)
                        .font(.body)
                        .lineLimit(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .topLeading)
            .background(tileShape.fill(model.highContrastMode ? Color(.systemBackground) : bgColor))
            .overlay {
                if model.highContrastMode { tileShape.stroke(Color.primary, lineWidth: 2) }
            }
            .contentShape(tileShape)
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            LongPressGesture(minimumDuration: max(0.01, model.holdToSelectMillis / 1_000))
                .onEnded { _ in
                    guard model.holdToSelectMillis > 0 else { return }
                    if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
                    model.activateTypingPhrase(phrase)
                }
        )
        .onHover { hovering in
            if hovering {
                model.accessEnter(accessTargetId)
                if model.auditoryFishingEnabled && !model.inputIsPaused { model.speak(accessibleName) }
            } else {
                model.accessExit(accessTargetId)
            }
        }
        .onAppear { model.registerAccessTarget(accessTargetId) { model.activateTypingPhrase(phrase) } }
        .onDisappear { model.unregisterAccessTarget(accessTargetId) }
        .accessTargetFocus(model: model, targetId: accessTargetId)
        .overlay {
            if model.accessTargetId == accessTargetId && model.pointerEmphasisStyle != "System" {
                tileShape.stroke(Color.accentColor, lineWidth: 3 * model.pointerEmphasisScale)
                    .padding(6 / model.pointerEmphasisScale)
                    .accessibilityHidden(true)
                    .allowsHitTesting(false)
            }
            if model.accessTargetId == accessTargetId && model.accessDwellProgress > 0 {
                tileShape.trim(from: 0, to: model.accessDwellProgress)
                    .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                    .accessibilityHidden(true)
                    .allowsHitTesting(false)
            }
        }
        .accessibilityLabel(Text(accessibleName.isEmpty ? NSLocalizedString("common.no_name", comment: "") : accessibleName))
        .accessibilityHint(Text("accessibility.phrase.insert_hint"))
        .accessibilityAction(named: Text("accessibility.phrase.speak_action")) { model.speakPhrase(phrase) }

        if editable {
            tile
                .accessibilityAction(named: Text("accessibility.phrase.edit_action")) { onEdit() }
                .accessibilityAction(named: Text("accessibility.phrase.delete_action")) { onDelete(phrase.id) }
                .accessibilityActions {
                    // Offered only where this Phrase can move.
                    if let onMoveEarlier {
                        Button("accessibility.reorder.move_earlier", action: onMoveEarlier)
                    }
                    if let onMoveLater {
                        Button("accessibility.reorder.move_later", action: onMoveLater)
                    }
                }
                .contextMenu {
                    Button { onEdit() } label: { Label("phrase.edit", systemImage: "pencil") }
                    Button { model.speakPhrase(phrase) } label: { Label("phrase.play_tts", systemImage: "speaker.wave.2.fill") }
                    if let recordingPath {
                        Button { recorder.play(url: URL(fileURLWithPath: recordingPath)) } label: { Label("phrase.play_recording", systemImage: "waveform") }
                        Button { requestMic(phrase.id) } label: { Label("phrase.record.replace", systemImage: "mic") }
                    } else {
                        Button { requestMic(phrase.id) } label: { Label("phrase.record", systemImage: "mic") }
                    }
                    Button(role: .destructive) { onDelete(phrase.id) } label: { Label("phrase.delete", systemImage: "trash") }
                }
                .draggable(phrase.id)
                .dropDestination(for: String.self) { ids, _ in
                    guard let onDrop, let moved = ids.first, moved != phrase.id else { return false }
                    onDrop(moved)
                    return true
                }
        } else {
            tile
                .contextMenu {
                    Button { model.speakPhrase(phrase) } label: { Label("phrase.play_tts", systemImage: "speaker.wave.2.fill") }
                    if let recordingPath {
                        Button { recorder.play(url: URL(fileURLWithPath: recordingPath)) } label: { Label("phrase.play_recording", systemImage: "waveform") }
                    }
                }
        }
    }
}
