import SwiftUI
import Shared

extension SymbolBoardWorkspaceView {
    // MARK: - Library View
    @ViewBuilder
    var libraryView: some View {
        ZStack {
            VStack(spacing: 16) {
            // Header bar: stack buttons below the title on narrow widths (such as iPhone Duo's outer display)
            ViewThatFits(in: .horizontal) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(NSLocalizedString("board_sets.library.title", comment: ""))
                            .font(.title2)
                            .fontWeight(.bold)
                        Text(NSLocalizedString("board_sets.library.subtitle", comment: ""))
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }

                    Spacer()

                    Button(action: { manageRemoteScreen(id: nil) }) {
                        Label("remote_screen.open", systemImage: "icloud.and.arrow.down")
                            .font(.headline)
                            .frame(minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    Button(action: { showSpeechImport = true }) {
                        Label("speech_import.title", systemImage: "text.badge.plus")
                            .font(.headline)
                            .frame(minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    Button(action: { showCreateBoardsetSheet = true }) {
                        Label("boardset.create", systemImage: "plus")
                            .font(.headline)
                            .frame(minHeight: 48)
                    }
                    .buttonStyle(.borderedProminent)
                }

                VStack(alignment: .leading, spacing: 10) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(NSLocalizedString("board_sets.library.title", comment: ""))
                            .font(.title2)
                            .fontWeight(.bold)
                        Text(NSLocalizedString("board_sets.library.subtitle", comment: ""))
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                    HStack(spacing: 8) {
                        Button(action: { manageRemoteScreen(id: nil) }) {
                            Label("remote_screen.open", systemImage: "icloud.and.arrow.down")
                                .labelStyle(.iconOnly)
                                .font(.subheadline.weight(.semibold))
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.bordered)
                        Button(action: { showSpeechImport = true }) {
                            Label("speech_import.title", systemImage: "text.badge.plus")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.bordered)
                        Button(action: { showCreateBoardsetSheet = true }) {
                            Label("boardset.create", systemImage: "plus")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
            }
            .padding(.horizontal)
            .padding(.top, 12)

            if model.boardSets.isEmpty {
                emptyLibraryState
            } else {
                ScrollView {
                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 280, maximum: 460), spacing: 16)],
                        spacing: 16
                    ) {
                        ForEach(model.boardSets) { set in
                            BoardSetLibraryCard(
                                set: set,
                                loadPreview: { await model.loadScreenPreview(rootBoardId: set.rootBoardId) },
                                onOpen: {
                                    Task {
                                        await model.selectBoardSet(id: set.id)
                                        route = .workspace(boardSetId: set.id, mode: .run)
                                    }
                                },
                                onEdit: {
                                    Task {
                                        await model.selectBoardSet(id: set.id)
                                        await requestEnterEditing(boardSetId: set.id)
                                    }
                                },
                                onDuplicate: {
                                    Task { await model.duplicateBoardSet(id: set.id) }
                                },
                                onToggleLock: {
                                    Task { await beginScreenLockToggle(set) }
                                },
                                onExport: {
                                    exportTargetSetId = set.id
                                },
                                onPrint: { printScreen(id: set.id) },
                                onRemote: { manageRemoteScreen(id: set.id) },
                                onDelete: {
                                    deleteTargetSet = set
                                }
                            )
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 24)
                }
            }
            }
            if model.isCreatingBoardSet {
                Color(.systemBackground)
                    .ignoresSafeArea()
                VStack(spacing: 12) {
                    ProgressView()
                    Text("boardset.creating")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    var emptyLibraryState: some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer(minLength: 16)
                Image(systemName: "square.grid.3x3")
                    .font(.system(size: 56))
                    .foregroundColor(.secondary)

                Text(NSLocalizedString("board_sets.library.empty_title", comment: ""))
                    .font(.title2)
                    .fontWeight(.bold)

                Text(NSLocalizedString("board_sets.library.empty_body", comment: ""))
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal)

                Text("board_sets.library.empty_guidance")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal)

                Button(action: { showCreateBoardsetSheet = true }) {
                    Text(NSLocalizedString("boardset.create_first", comment: ""))
                        .font(.headline)
                        .frame(minHeight: 48)
                        .padding(.horizontal, 24)
                }
                .buttonStyle(.borderedProminent)
                Button("speech_import.title") { showSpeechImport = true }
                    .font(.headline)
                    .frame(minHeight: 48)
                    .buttonStyle(.bordered)
                Spacer(minLength: 16)
            }
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - BoardSet Library Card Component
struct BoardSetLibraryCard: View {
    let set: BoardSetInfo
    let loadPreview: () async -> ScreenPreview?
    let onOpen: () -> Void
    let onEdit: () -> Void
    let onDuplicate: () -> Void
    let onToggleLock: () -> Void
    let onExport: () -> Void
    let onPrint: () -> Void
    let onRemote: () -> Void
    let onDelete: () -> Void

    @State private var preview: ScreenPreview?
    @State private var previewLoaded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 10) {
                    ScreenLibraryPreview(
                        preview: preview,
                        isLoaded: previewLoaded,
                        isLocked: set.isLocked,
                        remoteBadge: nil
                    )
                        .aspectRatio(4 / 3, contentMode: .fit)
                        .accessibilityHidden(true)
                    Text(set.name)
                        .font(.title3)
                        .fontWeight(.semibold)
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)
                    Text(pageSummary)
                        .font(.body)
                        .foregroundStyle(.primary)
                    Text(updatedSummary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if set.remoteConflict, let remoteSummary {
                        Label(remoteSummary, systemImage: remoteSummaryIcon)
                            .font(.subheadline)
                            .foregroundStyle(Color.red)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(openAccessibilityLabel))
            .accessibilityHint(Text("board_sets.library.open_hint"))
            .task(id: set.updatedAt) {
                previewLoaded = false
                preview = await loadPreview()
                previewLoaded = true
            }

            HStack(spacing: 12) {
                Button(action: onEdit) {
                    Label("board_sets.library.edit", systemImage: "pencil")
                        .font(.headline)
                        .frame(minHeight: 56)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(set.isLocked)
                .overlay(alignment: .topTrailing) {
                    // Who owns a synced Screen, as an icon only; the text is in the VoiceOver label.
                    if set.remoteName != nil {
                        Image(systemName: remoteSummaryIcon)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(set.remoteConflict ? Color.red : Color.accentColor)
                            .padding(5)
                            .background(Circle().fill(Color(.systemBackground)))
                            .offset(x: 6, y: -6)
                            .accessibilityHidden(true)
                    }
                }

                Menu {
                    Button(action: onDuplicate) {
                        Label("board_sets.library.duplicate", systemImage: "doc.on.doc")
                    }
                    if set.isLocked {
                        Button(action: onToggleLock) {
                            Label("boardset.unlock", systemImage: "lock.open.fill")
                        }
                    } else {
                        Button(action: onToggleLock) {
                            Label("boardset.lock", systemImage: "lock.fill")
                        }
                    }
                    Button(action: onExport) {
                        Label("boardset.export_obz", systemImage: "square.and.arrow.up")
                    }
                    Button(action: onPrint) {
                        Label("boardset.print_pdf", systemImage: "printer")
                    }
                    Button(action: onRemote) {
                        Label("remote_screen.storage", systemImage: "icloud")
                    }
                    Divider()
                    Button(role: .destructive, action: onDelete) {
                        Label("common.delete", systemImage: "trash")
                    }
                } label: {
                    Label("board_sets.library.more", systemImage: "ellipsis.circle")
                        .font(.headline)
                        .frame(minWidth: 56, minHeight: 56)
                }
                .accessibilityLabel(Text("board_sets.library.more"))
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.secondarySystemBackground))
        )
    }

    private var pageSummary: String {
        let count = set.boardIds.count
        if count == 1 {
            return String(format: NSLocalizedString("board_sets.library.pages_one", comment: ""), count)
        }
        return String(format: NSLocalizedString("board_sets.library.pages_other", comment: ""), count)
    }

    private var updatedSummary: String {
        let date = Date(timeIntervalSince1970: set.updatedAt)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        let relative = formatter.localizedString(for: date, relativeTo: Date())
        return String(format: NSLocalizedString("board_sets.library.updated", comment: ""), relative)
    }

    private var remoteSummaryIcon: String {
        if set.remoteConflict { return "exclamationmark.icloud" }
        if set.remoteOwnership == "shared" { return "person.2.fill" }
        if set.remoteOwnership == "owner" {
            return set.remoteKind == "icloud_share" ? "person.2" : "person.crop.circle.badge.checkmark"
        }
        return "icloud"
    }

    private var remoteSummary: String? {
        guard let remoteName = set.remoteName else { return nil }
        if set.remoteConflict { return NSLocalizedString("remote_screen.conflict_badge", comment: "") }
        if set.remoteOwnership == "shared" {
            if let owner = set.remoteOwnerName, !owner.isEmpty {
                return String(format: NSLocalizedString("remote_screen.shared_by_badge", comment: ""), owner, remoteName)
            }
            return String(format: NSLocalizedString("remote_screen.shared_with_you_badge", comment: ""), remoteName)
        }
        if set.remoteOwnership == "owner" {
            return String(format: NSLocalizedString("remote_screen.owned_by_you_badge", comment: ""), remoteName)
        }
        return String(format: NSLocalizedString("remote_screen.linked_badge", comment: ""), remoteName)
    }

    private var openAccessibilityLabel: String {
        let parts = [set.name, pageSummary]
            + (set.isLocked ? [NSLocalizedString("board_sets.library.locked", comment: "")] : [])
            + [remoteSummary].compactMap { $0 }
        return parts.joined(separator: ", ")
    }
}

private struct ScreenRemotePreviewBadge {
    let title: String
    let systemImage: String
    let isAlert: Bool
}

/// A miniature of a screen's home page. Empty cells stay blank, matching use mode.
private struct ScreenLibraryPreview: View {
    var preview: ScreenPreview?
    var isLoaded: Bool
    var isLocked: Bool
    var remoteBadge: ScreenRemotePreviewBadge? = nil

    var body: some View {
        GeometryReader { geo in
            let rows = CGFloat(max(1, preview?.rows ?? 1))
            let columns = CGFloat(max(1, preview?.columns ?? 1))
            let inset: CGFloat = 8
            let gap: CGFloat = 3
            let gridWidth = max(0, geo.size.width - inset * 2)
            let gridHeight = max(0, geo.size.height - inset * 2)
            let cellWidth = max(0, (gridWidth - gap * (columns - 1)) / columns)
            let cellHeight = max(0, (gridHeight - gap * (rows - 1)) / rows)
            ZStack(alignment: .topLeading) {
                ForEach(preview?.cells ?? []) { cell in
                    previewTile(cell, cellWidth: cellWidth, cellHeight: cellHeight, gap: gap)
                        .offset(
                            x: inset + CGFloat(cell.column) * (cellWidth + gap),
                            y: inset + CGFloat(cell.row) * (cellHeight + gap)
                        )
                }
            }
        }
        .overlay {
            if !isLoaded {
                ProgressView()
            }
        }
        .background(libraryPreviewColor(preview?.backgroundHex, fallback: Color(.tertiarySystemBackground)))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(.separator), lineWidth: 1))
        .overlay(alignment: .topLeading) {
            if let badge = remoteBadge {
                Label(badge.title, systemImage: badge.systemImage)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(badge.isAlert ? Color.white : Color.primary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(
                        badge.isAlert ? AnyShapeStyle(Color.red) : AnyShapeStyle(.regularMaterial),
                        in: Capsule()
                    )
                    .padding(8)
                    .accessibilityHidden(true)
            }
        }
        .overlay(alignment: .topTrailing) {
            if isLocked {
                Image(systemName: "lock.fill")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .padding(8)
                    .background(.regularMaterial, in: Circle())
                    .padding(8)
                    .accessibilityHidden(true)
            }
        }
    }

    private func previewTile(_ cell: ScreenPreviewCell, cellWidth: CGFloat, cellHeight: CGFloat, gap: CGFloat) -> some View {
        let width = cellWidth * CGFloat(cell.columnSpan) + gap * CGFloat(max(0, cell.columnSpan - 1))
        let height = cellHeight * CGFloat(cell.rowSpan) + gap * CGFloat(max(0, cell.rowSpan - 1))
        let fill = libraryPreviewColor(cell.colorHex, fallback: Color(.systemBackground))
        return ZStack {
            if let urlString = cell.imageUrl, let url = URL(string: urlString) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFit().padding(2)
                    default:
                        previewLabel(cell.label, colorHex: cell.colorHex)
                    }
                }
            } else {
                previewLabel(cell.label, colorHex: cell.colorHex)
            }
        }
        .frame(width: max(0, width), height: max(0, height))
        .background(fill, in: RoundedRectangle(cornerRadius: min(6, max(width, 1) / 6)))
    }

    @ViewBuilder
    private func previewLabel(_ label: String?, colorHex: String?) -> some View {
        if let label, !label.isEmpty {
            Text(label)
                .font(.caption2)
                .lineLimit(2)
                .minimumScaleFactor(0.4)
                .multilineTextAlignment(.center)
                .foregroundStyle(libraryPreviewTextColor(colorHex))
                .padding(1)
        }
    }
}

private func libraryPreviewColor(_ hex: String?, fallback: Color) -> Color {
    var value = (hex ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    guard !value.isEmpty else { return fallback }
    if value.hasPrefix("#") { value.removeFirst() }
    guard let parsed = UInt64(value, radix: 16) else { return fallback }
    let red: Double
    let green: Double
    let blue: Double
    let alpha: Double
    if value.count == 6 {
        red = Double((parsed >> 16) & 0xFF) / 255
        green = Double((parsed >> 8) & 0xFF) / 255
        blue = Double(parsed & 0xFF) / 255
        alpha = 1
    } else if value.count == 8 {
        alpha = Double((parsed >> 24) & 0xFF) / 255
        red = Double((parsed >> 16) & 0xFF) / 255
        green = Double((parsed >> 8) & 0xFF) / 255
        blue = Double(parsed & 0xFF) / 255
    } else {
        return fallback
    }
    return Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha)
}

private func libraryPreviewTextColor(_ hex: String?) -> Color {
    var value = (hex ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    if value.hasPrefix("#") { value.removeFirst() }
    guard value.count >= 6, let parsed = UInt64(value.suffix(6), radix: 16) else { return .primary }
    let red = Double((parsed >> 16) & 0xFF)
    let green = Double((parsed >> 8) & 0xFF)
    let blue = Double(parsed & 0xFF)
    let luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255
    return luminance > 0.6 ? .black : .white
}
