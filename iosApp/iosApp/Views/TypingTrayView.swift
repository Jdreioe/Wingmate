import SwiftUI
import Shared

/// Which Typing Screen Page the tray shows.
enum TypingPage: Equatable {
    case all
    case category(String)
    case history
}

/// The Typing Screen in the tray below the Message bar. Its Page elements (Page
/// navigation, the Phrase collection, Action strips) come from the shared template,
/// placed on the template's grid; rows of Phrases take the space that is left.
struct TypingTrayView: View {
    @ObservedObject var model: IosViewModel
    let tray: Shared.TypingTray
    @Binding var page: TypingPage
    let recorder: AudioRecorder
    let chipFontSize: CGFloat
    /// Runs a vocabulary change after Editing access allows it.
    let requestMutation: (@escaping () -> Void) -> Void
    let onAddPhrase: () -> Void
    let onAddCategory: () -> Void
    let onEditPhrase: (Shared.Phrase) -> Void
    let requestMic: (String) -> Void
    let onShowKeyboard: () -> Void

    @State private var categoryMenu: Shared.Phrase? = nil
    @State private var confirmDeleteCategory: Shared.Phrase? = nil

    var body: some View {
        TypingTrayLayout(columns: max(1, Int(tray.gridColumns))) {
            ForEach(Array(tray.elements.enumerated()), id: \.offset) { _, element in
                elementView(element)
                    .layoutValue(
                        key: TrayPlacement.self,
                        value: TrayPlacement.Value(
                            row: Int(element.row),
                            column: Int(element.column),
                            rowSpan: Int(element.rowSpan),
                            columnSpan: Int(element.columnSpan),
                            // Chips and Action Buttons stay full-size touch targets.
                            minimumHeight: element.kind == .pageNavigation ? 48
                                : element.kind == .actionStrip ? 64 : 0
                        )
                    )
            }
        }
        .onChange(of: model.historyVisible) { _, _ in leaveHistoryIfUnavailable() }
        .onChange(of: model.historyPhrases.isEmpty) { _, _ in leaveHistoryIfUnavailable() }
        .confirmationDialog(
            categoryMenu?.text ?? "",
            isPresented: Binding(get: { categoryMenu != nil }, set: { if !$0 { categoryMenu = nil } }),
            titleVisibility: .visible,
            presenting: categoryMenu
        ) { category in
            categoryActions(category)
        }
        .alert(
            NSLocalizedString("category.delete", comment: ""),
            isPresented: Binding(get: { confirmDeleteCategory != nil }, set: { if !$0 { confirmDeleteCategory = nil } }),
            presenting: confirmDeleteCategory
        ) { category in
            Button("common.delete", role: .destructive) {
                requestMutation {
                    model.deleteCategory(id: category.id)
                    if page == .category(category.id) { selectPage(.all) }
                }
            }
            Button("common.cancel", role: .cancel) {}
        } message: { category in
            Text(category.text)
        }
    }

    @ViewBuilder
    private func elementView(_ element: Shared.TypingTrayElement) -> some View {
        if element.kind == .pageNavigation {
            pageNavigation(showAdd: element.showAddCategory)
        } else if element.kind == .phraseCollection {
            phraseCollection
        } else {
            VStack(spacing: 0) {
                Divider()
                TypingActionStrip(model: model, actions: element.actions, onShowKeyboard: onShowKeyboard)
            }
            .accessibilityHidden(model.scanningEnabled && !model.scanPlaybackAreaEnabled)
        }
    }

    // MARK: - Page navigation

    private var historyAvailable: Bool { model.historyVisible && !model.historyPhrases.isEmpty }

    /// History can disappear while open (turned off in Settings); fall back to All Phrases.
    private func leaveHistoryIfUnavailable() {
        if page == .history && !historyAvailable { selectPage(.all) }
    }

    private func selectPage(_ newPage: TypingPage) {
        page = newPage
        // Phrases added from the tray go into the open Category.
        if case .category(let id) = newPage { model.selectCategory(id: id) } else { model.selectCategory(id: nil) }
        if newPage == .history { Task { await model.loadHistory() } }
    }

    private func pageNavigation(showAdd: Bool) -> some View {
        let categories = model.state.categories
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                CategoryChip(
                    title: NSLocalizedString("categories.all", comment: ""),
                    selected: page == .all,
                    fontSize: chipFontSize
                ) { selectPage(.all) }
                ForEach(categories, id: \.id) { category in
                    let selected = page == .category(category.id)
                    CategoryChip(
                        title: category.text.isEmpty ? NSLocalizedString("common.no_name", comment: "") : category.text,
                        selected: selected,
                        fontSize: chipFontSize
                    ) {
                        // Tapping the open Category again opens its menu (move, delete).
                        if selected { requestMutation { categoryMenu = category } } else { selectPage(.category(category.id)) }
                    }
                    .contextMenu { categoryActions(category) }
                    .accessibilityAddTraits(selected ? .isSelected : [])
                    .accessibilityHint(Text(selected ? "accessibility.category.menu_hint" : ""))
                }
                if historyAvailable {
                    CategoryChip(
                        title: NSLocalizedString("categories.history", comment: ""),
                        selected: page == .history,
                        fontSize: chipFontSize
                    ) { selectPage(.history) }
                }
                if showAdd {
                    Button(action: { requestMutation(onAddCategory) }) {
                        Image(systemName: "plus")
                            .font(.system(size: max(14, chipFontSize * 0.85), weight: .semibold))
                            .frame(minWidth: 44, minHeight: 44)
                            .background(Capsule().fill(Color(.secondarySystemFill)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("toolbar.add_category"))
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
        }
        .accessibilityElement(children: .contain)
        .accessibilityHidden(model.scanningEnabled && !model.scanCategoryItemsEnabled)
    }

    @ViewBuilder
    private func categoryActions(_ category: Shared.Phrase) -> some View {
        let categories = model.state.categories
        let index = categories.firstIndex(where: { $0.id == category.id })
        if let index, index > 0 {
            Button { requestMutation { model.movePhrase(category.id, onto: categories[index - 1].id) } } label: {
                Label("category.move_left", systemImage: "arrow.left")
            }
        }
        if let index, index < categories.count - 1 {
            Button { requestMutation { model.movePhrase(category.id, onto: categories[index + 1].id) } } label: {
                Label("category.move_right", systemImage: "arrow.right")
            }
        }
        Button(role: .destructive) { confirmDeleteCategory = category } label: {
            Label("category.delete", systemImage: "trash")
        }
    }

    // MARK: - Phrase collection

    /// Orders VoiceOver and switch scanning by the scan-order setting; higher goes first.
    private func scanPriority(index: Int, count: Int) -> Double {
        let columns = max(1, Int(tray.phraseColumns))
        guard model.scanPhraseGridOrder == "column-major" else { return Double(100_000 - index) }
        let rows = Int(ceil(Double(count) / Double(columns)))
        return Double(100_000 - ((index % columns) * rows + index / columns))
    }

    private var pagePhrases: [Shared.Phrase] {
        switch page {
        case .all: return model.state.phrases
        case .category(let id): return model.state.phrases.filter { $0.parentId == id }
        case .history: return model.historyPhrases
        }
    }

    private var phraseCollection: some View {
        let phrases = pagePhrases
        let isHistory = page == .history
        // Long presses activate Phrases under hold-to-select, so editing gestures stay off.
        let editable = !isHistory && model.holdToSelectMillis <= 0
        let columns = Array(
            repeating: GridItem(.flexible(), spacing: 8),
            count: max(1, Int(tray.phraseColumns))
        )
        return ScrollView {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(Array(phrases.enumerated()), id: \.element.id) { index, phrase in
                    PhraseItemView(
                        model: model,
                        phrase: phrase,
                        recorder: recorder,
                        editable: editable,
                        onEdit: { requestMutation { onEditPhrase(phrase) } },
                        requestMic: { id in requestMutation { requestMic(id) } },
                        onDelete: { id in requestMutation { model.deletePhrase(id: id) } },
                        onMoveEarlier: index > 0 ? {
                            requestMutation { model.movePhrase(phrase.id, onto: phrases[index - 1].id) }
                        } : nil,
                        onMoveLater: index < phrases.count - 1 ? {
                            requestMutation { model.movePhrase(phrase.id, onto: phrases[index + 1].id) }
                        } : nil,
                        onDrop: { movedId in
                            // Dropping onto a Phrase takes its place in the repository order.
                            requestMutation { model.movePhrase(movedId, onto: phrase.id) }
                        }
                    )
                    .accessibilitySortPriority(scanPriority(index: index, count: phrases.count))
                }
                if !isHistory {
                    Button(action: { requestMutation(onAddPhrase) }) {
                        VStack(spacing: 4) {
                            Image(systemName: "plus.circle.fill").font(.system(size: 28))
                            Text("phrase.add.tile")
                        }
                        .frame(maxWidth: .infinity, minHeight: 88)
                        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(.secondarySystemFill)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("phrase.new.title"))
                }
            }
            .padding(8)
        }
        .overlay {
            if isHistory && phrases.isEmpty {
                Text("history.empty").foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityHidden(model.scanningEnabled && !model.scanPhraseGridEnabled)
    }
}

/// An Action strip: every Button in template order. SSML insertions are compact
/// outlined chips in a monospace face; Message controls are filled buttons with an
/// icon, and the language toggle shows the secondary language's code.
struct TypingActionStrip: View {
    @ObservedObject var model: IosViewModel
    let actions: [Shared.TypingTrayAction]
    let onShowKeyboard: () -> Void
    var leading: AnyView? = nil

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let leading { leading }
                ForEach(actions, id: \.id) { action in
                    TypingActionButton(model: model, action: action, onShowKeyboard: onShowKeyboard)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
        }
        .accessibilityElement(children: .contain)
    }
}

private struct TypingActionButton: View {
    @ObservedObject var model: IosViewModel
    let action: Shared.TypingTrayAction
    let onShowKeyboard: () -> Void

    private var single: Shared.TypingEffectKind? {
        action.effects.count == 1 ? action.effects.first?.kind : nil
    }

    private var isSsml: Bool {
        !action.effects.isEmpty && action.effects.allSatisfy { $0.kind == .insertText || $0.kind == .wrapSelection }
    }

    private var unsupported: Bool {
        action.effects.contains { $0.kind == .unsupported }
    }

    private var enabled: Bool {
        guard !action.effects.isEmpty, !unsupported else { return false }
        return action.effects.allSatisfy { effect in
            let kind = effect.kind
            if kind == .pause { return model.playback == .playing || model.playback == .preparing }
            if kind == .resume { return model.playback == .paused }
            if kind == .stop { return model.playback != .idle }
            if kind == .secondaryLanguage { return model.hasUsableSecondaryLanguage }
            return true
        }
    }

    private var label: String {
        if single == .secondaryLanguage, !model.secondaryLanguage.isEmpty {
            return (model.secondaryLanguage.split(separator: "-").first.map(String.init) ?? model.secondaryLanguage).uppercased()
        }
        return action.label
    }

    private var icon: String? {
        guard let single else { return nil }
        if single == .holdMessage { return "bookmark.fill" }
        if single == .secondaryLanguage { return "character.bubble" }
        if single == .pause { return "pause.fill" }
        if single == .resume || single == .speak { return "play.fill" }
        if single == .stop { return "stop.fill" }
        if single == .backspace { return "delete.left" }
        if single == .clear { return "xmark" }
        if single == .keyboard { return "keyboard" }
        return nil
    }

    var body: some View {
        let targetId = "board:\(action.id)"
        let shape = RoundedRectangle(cornerRadius: isSsml ? 12 : 16, style: .continuous)
        Button(action: perform) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon).accessibilityHidden(true) }
                Text(label)
                    .font(isSsml ? .system(.body, design: .monospaced) : .body.weight(.medium))
                    .lineLimit(1)
            }
            .padding(.horizontal, isSsml ? 14 : 16)
            .frame(maxHeight: .infinity)
            .frame(minWidth: 44)
            .background {
                if isSsml {
                    shape.stroke(Color(.separator), lineWidth: 1)
                } else {
                    shape.fill(single == .secondaryLanguage ? Color.accentColor.opacity(0.2) : Color(.secondarySystemFill))
                }
            }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityValue(Text(unsupported ? "typing.action.unsupported" : enabled ? "" : "typing.action.unavailable"))
        .onAppear { model.registerAccessTarget(targetId, action: perform) }
        .onDisappear { model.unregisterAccessTarget(targetId) }
        .onHover { hovering in
            if hovering { model.accessEnter(targetId) } else { model.accessExit(targetId) }
        }
        .accessTargetFocus(model: model, targetId: targetId)
        .overlay {
            if model.accessTargetId == targetId {
                shape.stroke(Color.accentColor, lineWidth: 3).allowsHitTesting(false)
            }
        }
    }

    private func perform() {
        guard enabled else { return }
        for effect in action.effects {
            if effect.kind == .keyboard { onShowKeyboard() } else { model.performTypingEffect(effect) }
        }
    }
}

/// Where a Page element sits on the template's grid.
private struct TrayPlacement: LayoutValueKey {
    struct Value {
        var row = 0
        var column = 0
        var rowSpan = 1
        var columnSpan = 1
        var minimumHeight: CGFloat = 0
    }

    static let defaultValue = Value()
}

/// Places Page elements on the template's grid, as Android does: rows holding Page
/// navigation or an Action strip keep their minimum height, and rows without one
/// (the Phrase collection) share the rest of the tray.
private struct TypingTrayLayout: Layout {
    let columns: Int

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let placements = subviews.map { $0[TrayPlacement.self] }
        let rows = max(1, placements.map { $0.row + max(1, $0.rowSpan) }.max() ?? 1)
        var minimums = Array(repeating: CGFloat(0), count: rows)
        for placement in placements {
            let span = max(1, placement.rowSpan)
            for row in placement.row..<min(placement.row + span, rows) where row >= 0 {
                minimums[row] = max(minimums[row], placement.minimumHeight / CGFloat(span))
            }
        }
        let heights = Self.rowHeights(minimums: minimums, height: bounds.height)
        let tops = heights.reduce(into: [CGFloat(0)]) { $0.append($0.last! + $1) }
        for (subview, placement) in zip(subviews, placements) {
            let firstRow = min(max(placement.row, 0), rows - 1)
            let lastRow = min(firstRow + max(1, placement.rowSpan), rows)
            let firstColumn = min(max(placement.column, 0), columns - 1)
            let lastColumn = min(firstColumn + max(1, placement.columnSpan), columns)
            let left = bounds.width * CGFloat(firstColumn) / CGFloat(columns)
            let right = bounds.width * CGFloat(lastColumn) / CGFloat(columns)
            subview.place(
                at: CGPoint(x: bounds.minX + left, y: bounds.minY + tops[firstRow]),
                anchor: .topLeading,
                proposal: ProposedViewSize(width: right - left, height: tops[lastRow] - tops[firstRow])
            )
        }
    }

    /// Rows with a minimum keep exactly that; the others share what is left. With no
    /// flexible rows, the spare height is spread over all rows.
    private static func rowHeights(minimums: [CGFloat], height: CGFloat) -> [CGFloat] {
        let remaining = max(0, height - minimums.reduce(0, +))
        let flexible = minimums.filter { $0 == 0 }.count
        return minimums.map { minimum in
            if flexible == 0 { return minimum + remaining / CGFloat(minimums.count) }
            return minimum > 0 ? minimum : remaining / CGFloat(flexible)
        }
    }
}
