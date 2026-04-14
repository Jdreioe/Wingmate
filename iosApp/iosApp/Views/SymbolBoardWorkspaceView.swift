import SwiftUI
import Shared
import LocalAuthentication
import PhotosUI
import UniformTypeIdentifiers
import UIKit

private struct CellOpenSymbolsTokenResponse: Decodable {
    let access_token: String
}

private struct CellOpenSymbolsSymbolResult: Decodable {
    let id: Int64
    let name: String
    let image_url: String?
}

private enum CellSymbolSource: String, CaseIterable, Identifiable {
    case openSymbols
    case userPhotos

    var id: String { rawValue }
}

private func resolveCellOpenSymbolsSecret() -> String? {
    let fromInfoRaw = Bundle.main.object(forInfoDictionaryKey: "OPEN_SYMBOLS_SECRET") as? String
    let fromInfo = fromInfoRaw?.trimmingCharacters(in: .whitespacesAndNewlines)
    if let fromInfo, !fromInfo.isEmpty { return fromInfo }

    let fromEnvRaw = ProcessInfo.processInfo.environment["OPEN_SYMBOLS_SECRET"]
    let fromEnv = fromEnvRaw?.trimmingCharacters(in: .whitespacesAndNewlines)
    if let fromEnv, !fromEnv.isEmpty { return fromEnv }

    return nil
}

struct SymbolBoardWorkspaceView: View {
    @ObservedObject var model: IosViewModel
    @Namespace private var sentenceAnimationNamespace

    @State private var showCreateBoardsetSheet = false
    @State private var showAddBoardSheet = false

    @State private var createBoardsetName: String = ""
    @State private var createBoardsetRows: Int = 4
    @State private var createBoardsetColumns: Int = 4

    @State private var addBoardName: String = ""
    @State private var addBoardRows: Int = 4
    @State private var addBoardColumns: Int = 4

    @State private var authErrorMessage: String? = nil
    @State private var isEditModeEnabled: Bool = false
    @State private var showEditCellSheet: Bool = false
    @State private var editingRow: Int = 0
    @State private var editingCol: Int = 0
    @State private var editingLabel: String = ""
    @State private var editingVocalization: String = ""
    @State private var editingBackgroundPickerColor: Color = Color(.tertiarySystemBackground)
    @State private var editingBorderPickerColor: Color = Color(.separator)
    @State private var useCustomBackgroundColor: Bool = false
    @State private var useCustomBorderColor: Bool = false
    @State private var editingLinkedBoardId: String = ""
    @State private var editingSymbolSource: CellSymbolSource = .openSymbols
    @State private var editingSymbolQuery: String = ""
    @State private var editingSelectedSymbolUrl: String? = nil
    @State private var editingSelectedPhotoItem: PhotosPickerItem? = nil
    @State private var isImportingCellSymbolFile: Bool = false
    @State private var editingSymbolResults: [CellOpenSymbolsSymbolResult] = []
    @State private var isSearchingCellSymbols: Bool = false
    @State private var editingSymbolError: String? = nil
    @State private var shouldClearEditingSymbol: Bool = false
    @State private var boardSentenceTokens: [SentencePhraseToken] = []
    @State private var activeSentenceAnimation: ActiveSentenceAnimation? = nil

    private struct ActiveSentenceAnimation: Equatable {
        let sourceCellId: String
        let tokenId: String
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            boardsetStrip

            if let selectedSet = model.selectedBoardSet {
                boardsetControls(set: selectedSet)

                if selectedSet.boardIds.count > 1 {
                    Picker("boardset.board_picker", selection: Binding(
                        get: { model.selectedBoardId ?? selectedSet.rootBoardId },
                        set: { newId in
                            Task { await model.selectBoard(id: newId) }
                        }
                    )) {
                        ForEach(selectedSet.boardIds, id: \.self) { id in
                            Text(model.boardDisplayName(id: id)).tag(id)
                        }
                    }
                    .pickerStyle(.menu)
                }

                SentenceBoxView(
                    phrases: boardSentenceTokens,
                    onDelete: { index in
                        guard boardSentenceTokens.indices.contains(index) else { return }
                        let removedTokenId = boardSentenceTokens[index].id
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                            boardSentenceTokens.remove(at: index)
                        }
                        if activeSentenceAnimation?.tokenId == removedTokenId {
                            activeSentenceAnimation = nil
                        }
                    },
                    onSpeak: {
                        let sentence = boardSentenceText
                        guard !sentence.isEmpty else { return }
                        model.speak(sentence)
                    },
                    animationNamespace: sentenceAnimationNamespace,
                    animatedTokenId: activeSentenceAnimation?.tokenId
                )
                .accessibilityElement(children: .contain)

                boardPreview
            } else {
                emptyState
            }

            Spacer(minLength: 0)
        }
        .task {
            await model.loadBoardSets()
        }
        .sheet(isPresented: $showCreateBoardsetSheet) {
            createBoardsetSheet
        }
        .sheet(isPresented: $showAddBoardSheet) {
            addBoardSheet
        }
        .sheet(isPresented: $showEditCellSheet) {
            editCellSheet
        }
        .onChange(of: editingSelectedPhotoItem) { _, newItem in
            guard let newItem else { return }
            Task {
                await importCellPhotoItem(newItem)
            }
        }
        .onChange(of: model.selectedBoardId) { _, _ in
            boardSentenceTokens = []
            activeSentenceAnimation = nil
        }
        .onChange(of: model.selectedBoardSetId) { _, _ in
            boardSentenceTokens = []
            activeSentenceAnimation = nil
        }
        .fileImporter(
            isPresented: $isImportingCellSymbolFile,
            allowedContentTypes: [.image],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                Task {
                    await importCellImageFile(url)
                }
            case .failure:
                editingSymbolError = NSLocalizedString("phrase.symbol.error.import_failed", comment: "")
            }
        }
        .alert(
            NSLocalizedString("boardset.unlock.failed_title", comment: ""),
            isPresented: Binding(
                get: { authErrorMessage != nil },
                set: { if !$0 { authErrorMessage = nil } }
            )
        ) {
            Button(NSLocalizedString("common.ok", comment: ""), role: .cancel) {
                authErrorMessage = nil
            }
        } message: {
            Text(authErrorMessage ?? NSLocalizedString("common.unknown_error", comment: ""))
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("symbol.workspace.title")
                    .font(.title3)
                    .bold()
                Text("symbol.workspace.subtitle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                showCreateBoardsetSheet = true
            } label: {
                Label("boardset.create", systemImage: "plus")
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private var boardsetStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.boardSets) { set in
                    Button {
                        Task { await model.selectBoardSet(id: set.id) }
                    } label: {
                        HStack(spacing: 6) {
                            if set.isLocked {
                                Image(systemName: "lock.fill")
                                    .font(.caption)
                            }
                            Text(set.name)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(model.selectedBoardSetId == set.id ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground))
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private func boardsetControls(set: BoardSetInfo) -> some View {
        HStack(spacing: 10) {
            Button {
                Task { await model.saveSelectedBoardSet() }
            } label: {
                Label("boardset.save", systemImage: "square.and.arrow.down")
            }
            .buttonStyle(.bordered)
            .disabled(model.selectedBoard == nil || !model.canEditSelectedBoardSet)

            Button {
                isEditModeEnabled.toggle()
            } label: {
                Label(
                    isEditModeEnabled ? "boardset.edit_done" : "boardset.edit",
                    systemImage: isEditModeEnabled ? "checkmark.circle.fill" : "pencil"
                )
            }
            .buttonStyle(.bordered)
            .disabled(model.selectedBoard == nil || !model.canEditSelectedBoardSet)

            Button {
                if set.isLocked {
                    authenticateAndUnlock()
                } else {
                    isEditModeEnabled = false
                    model.setSelectedBoardSetLocked(true)
                }
            } label: {
                Label(
                    set.isLocked ? "boardset.unlock" : "boardset.lock",
                    systemImage: set.isLocked ? "lock.open.fill" : "lock.fill"
                )
            }
            .buttonStyle(.bordered)

            Button {
                showAddBoardSheet = true
            } label: {
                Label("boardset.add_board", systemImage: "plus.rectangle.on.rectangle")
            }
            .buttonStyle(.bordered)
            .disabled(!model.canEditSelectedBoardSet)
        }

        if let status = model.boardStatusMessage, !status.isEmpty {
            Text(status)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }

        if set.isLocked {
            Label("boardset.locked_banner", systemImage: "lock.fill")
                .font(.footnote)
                .foregroundStyle(.orange)
        }
    }

    @ViewBuilder
    private var boardPreview: some View {
        if let board = model.selectedBoard {
            let rows = max(1, Int(board.grid?.rows ?? 1))
            let cols = max(1, Int(board.grid?.columns ?? 1))
            let previewCols = Array(repeating: GridItem(.flexible(), spacing: 8), count: cols)

            VStack(alignment: .leading, spacing: 10) {
                Text(board.name ?? NSLocalizedString("boardset.untitled_board", comment: ""))
                    .font(.headline)
                Text(String(format: NSLocalizedString("boardset.grid_summary", comment: ""), rows, cols, board.buttons.count))
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                LazyVGrid(columns: previewCols, spacing: 8) {
                    ForEach(0..<(rows * cols), id: \.self) { idx in
                        let row = idx / cols
                        let col = idx % cols
                        boardCellButton(row: row, col: col)
                    }
                }
            }
            .padding(12)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 24)
        }
    }

    private func boardCellButton(row: Int, col: Int) -> some View {
        let cell = model.cellAt(row: row, col: col)
        let isLinked = trimmed(cell?.linkedBoardId) != nil
        let sourceCellId = "\(row):\(col)"

        return ZStack(alignment: .topTrailing) {
            Button {
                if isEditModeEnabled && model.canEditSelectedBoardSet {
                    openCellEditor(row: row, col: col, existing: cell)
                } else {
                    appendCellToSentenceIfNeeded(cell)
                    Task { await model.activateSelectedBoardCell(row: row, col: col) }
                }
            } label: {
                Group {
                    if let animation = activeSentenceAnimation, animation.sourceCellId == sourceCellId {
                        boardCellContent(row: row, col: col, cell: cell, isLinked: isLinked)
                            .matchedGeometryEffect(id: animation.tokenId, in: sentenceAnimationNamespace, isSource: true)
                            .zIndex(3)
                    } else {
                        boardCellContent(row: row, col: col, cell: cell, isLinked: isLinked)
                    }
                }
            }
            .buttonStyle(.plain)

            if isEditModeEnabled, model.canEditSelectedBoardSet, cell != nil {
                Button(role: .destructive) {
                    Task { await model.clearSelectedBoardCell(row: row, col: col) }
                } label: {
                    Image(systemName: "trash.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.red)
                        .background(Color(.systemBackground), in: Circle())
                }
                .buttonStyle(.plain)
                .padding(4)
                .accessibilityLabel(Text("boardset.cell.delete"))
            }
        }
        .contextMenu {
            if model.canEditSelectedBoardSet {
                Button {
                    openCellEditor(row: row, col: col, existing: cell)
                } label: {
                    Label("boardset.cell.edit_title", systemImage: "pencil")
                }

                if cell != nil {
                    Button(role: .destructive) {
                        Task { await model.clearSelectedBoardCell(row: row, col: col) }
                    } label: {
                        Label("boardset.cell.delete", systemImage: "trash")
                    }
                }
            }
        }
    }

    private func boardCellContent(row: Int, col: Int, cell: BoardCellInfo?, isLinked: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let imageUrl = trimmed(cell?.imageUrl), let url = URL(string: imageUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity, maxHeight: 56)
                    case .failure(_):
                        EmptyView()
                    case .empty:
                        ProgressView()
                            .frame(maxWidth: .infinity, minHeight: 24)
                    @unknown default:
                        EmptyView()
                    }
                }
                .frame(maxWidth: .infinity, alignment: .center)
            }

            Text(trimmed(cell?.label) ?? NSLocalizedString("boardset.cell.empty", comment: ""))
                .font(.subheadline)
                .fontWeight(cell == nil ? .regular : .semibold)
                .lineLimit(2)
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)

            if let linkedBoardId = trimmed(cell?.linkedBoardId) {
                Label(model.boardDisplayName(id: linkedBoardId), systemImage: "arrowshape.turn.up.right")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if let vocalization = trimmed(cell?.vocalization), vocalization != trimmed(cell?.label) {
                Text(vocalization)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if isEditModeEnabled && model.canEditSelectedBoardSet {
                Text("\(row + 1), \(col + 1)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .padding(8)
        .frame(minHeight: 72, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(colorFromHex(cell?.backgroundColor, fallback: Color(.tertiarySystemBackground)))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(colorFromHex(cell?.borderColor, fallback: isLinked ? .accentColor : Color(.separator)), lineWidth: isLinked ? 1.5 : 1)
        )
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "square.grid.3x3")
                .font(.system(size: 36))
                .foregroundStyle(.secondary)
            Text("boardset.empty")
                .foregroundStyle(.secondary)
            Button("boardset.create_first") {
                showCreateBoardsetSheet = true
            }
            .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }

    private var createBoardsetSheet: some View {
        NavigationStack {
            Form {
                Section("boardset.name") {
                    TextField(NSLocalizedString("boardset.name_placeholder", comment: ""), text: $createBoardsetName)
                }
                Section("boardset.grid") {
                    Stepper(value: $createBoardsetRows, in: 1...12) {
                        Text(String(format: NSLocalizedString("boardset.rows", comment: ""), createBoardsetRows))
                    }
                    Stepper(value: $createBoardsetColumns, in: 1...12) {
                        Text(String(format: NSLocalizedString("boardset.columns", comment: ""), createBoardsetColumns))
                    }
                }
            }
            .navigationTitle(Text("boardset.create"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") {
                        showCreateBoardsetSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") {
                        Task {
                            await model.createBoardSet(name: createBoardsetName, rows: createBoardsetRows, columns: createBoardsetColumns)
                            createBoardsetName = ""
                            createBoardsetRows = 4
                            createBoardsetColumns = 4
                            showCreateBoardsetSheet = false
                        }
                    }
                }
            }
        }
    }

    private var addBoardSheet: some View {
        NavigationStack {
            Form {
                Section("boardset.board_name") {
                    TextField(NSLocalizedString("boardset.board_name_placeholder", comment: ""), text: $addBoardName)
                }
                Section("boardset.grid") {
                    Stepper(value: $addBoardRows, in: 1...12) {
                        Text(String(format: NSLocalizedString("boardset.rows", comment: ""), addBoardRows))
                    }
                    Stepper(value: $addBoardColumns, in: 1...12) {
                        Text(String(format: NSLocalizedString("boardset.columns", comment: ""), addBoardColumns))
                    }
                }
            }
            .navigationTitle(Text("boardset.add_board"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") {
                        showAddBoardSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") {
                        Task {
                            await model.addBoardToSelectedSet(name: addBoardName, rows: addBoardRows, columns: addBoardColumns)
                            addBoardName = ""
                            addBoardRows = 4
                            addBoardColumns = 4
                            showAddBoardSheet = false
                        }
                    }
                    .disabled(!model.canEditSelectedBoardSet)
                }
            }
        }
    }

    private var editCellSheet: some View {
        NavigationStack {
            Form {
                Section("boardset.cell.label") {
                    TextField(NSLocalizedString("boardset.cell.label", comment: ""), text: $editingLabel)
                }

                Section("boardset.cell.vocalization") {
                    TextField(NSLocalizedString("boardset.cell.vocalization", comment: ""), text: $editingVocalization)
                }

                Section("phrase.symbol.section") {
                    Picker("phrase.symbol.source", selection: $editingSymbolSource) {
                        Text("phrase.symbol.source.opensymbols").tag(CellSymbolSource.openSymbols)
                        Text("phrase.symbol.source.user_photos").tag(CellSymbolSource.userPhotos)
                    }
                    .pickerStyle(.segmented)

                    if editingSymbolSource == .openSymbols {
                        HStack(spacing: 8) {
                            TextField(NSLocalizedString("phrase.symbol.search.placeholder", comment: ""), text: $editingSymbolQuery)
                                .textFieldStyle(.roundedBorder)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled(true)
                                .onSubmit {
                                    Task { await searchCellOpenSymbols() }
                                }

                            Button {
                                Task { await searchCellOpenSymbols() }
                            } label: {
                                if isSearchingCellSymbols {
                                    ProgressView()
                                } else {
                                    Text("common.search")
                                }
                            }
                            .disabled(isSearchingCellSymbols || editingSymbolQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    } else {
                        HStack(spacing: 8) {
                            PhotosPicker(selection: $editingSelectedPhotoItem, matching: .images, photoLibrary: .shared()) {
                                Label("phrase.symbol.pick_photo", systemImage: "photo.on.rectangle")
                            }
                            .buttonStyle(.bordered)

                            Button(action: { isImportingCellSymbolFile = true }) {
                                Label("phrase.symbol.import_file", systemImage: "folder")
                            }
                            .buttonStyle(.bordered)
                        }
                    }

                    if let selectedUrl = editingSelectedSymbolUrl,
                       let url = URL(string: selectedUrl) {
                        HStack(spacing: 12) {
                            AsyncImage(url: url) { phase in
                                switch phase {
                                case .success(let image):
                                    image
                                        .resizable()
                                        .scaledToFit()
                                case .failure(_):
                                    Image(systemName: "photo")
                                        .foregroundStyle(.secondary)
                                case .empty:
                                    ProgressView()
                                @unknown default:
                                    EmptyView()
                                }
                            }
                            .frame(width: 56, height: 56)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(10)

                            Text("phrase.symbol.selected")
                                .font(.footnote)
                                .foregroundStyle(.secondary)

                            Spacer()

                            Button("common.clear") {
                                editingSelectedSymbolUrl = nil
                                shouldClearEditingSymbol = true
                            }
                            .font(.footnote)
                        }
                    }

                    if editingSymbolSource == .openSymbols && !editingSymbolResults.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(editingSymbolResults.prefix(30), id: \.id) { symbol in
                                    Button {
                                        editingSymbolSource = .openSymbols
                                        editingSelectedSymbolUrl = symbol.image_url
                                        shouldClearEditingSymbol = false
                                    } label: {
                                        VStack(spacing: 6) {
                                            if let imageUrl = symbol.image_url,
                                               let url = URL(string: imageUrl) {
                                                AsyncImage(url: url) { phase in
                                                    switch phase {
                                                    case .success(let image):
                                                        image
                                                            .resizable()
                                                            .scaledToFit()
                                                    case .failure(_):
                                                        Image(systemName: "photo")
                                                            .foregroundStyle(.secondary)
                                                    case .empty:
                                                        ProgressView()
                                                    @unknown default:
                                                        EmptyView()
                                                    }
                                                }
                                                .frame(width: 56, height: 56)
                                            } else {
                                                Image(systemName: "photo")
                                                    .frame(width: 56, height: 56)
                                                    .foregroundStyle(.secondary)
                                            }

                                            Text(symbol.name)
                                                .font(.caption2)
                                                .lineLimit(2)
                                                .multilineTextAlignment(.center)
                                        }
                                        .padding(8)
                                        .frame(width: 92, height: 110)
                                        .background(
                                            RoundedRectangle(cornerRadius: 10)
                                                .fill(editingSelectedSymbolUrl == symbol.image_url ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground))
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }

                    if let editingSymbolError {
                        Text(editingSymbolError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }

                Section("boardset.cell.linked_board") {
                    Picker("boardset.cell.linked_board", selection: $editingLinkedBoardId) {
                        Text("boardset.cell.no_link").tag("")
                        ForEach(linkableBoardIds, id: \.self) { boardId in
                            Text(model.boardDisplayName(id: boardId)).tag(boardId)
                        }
                    }
                }

                Section("boardset.cell.colors") {
                    Toggle("boardset.cell.background_enabled", isOn: $useCustomBackgroundColor)
                    if useCustomBackgroundColor {
                        ColorPicker("boardset.cell.background_picker", selection: $editingBackgroundPickerColor, supportsOpacity: true)
                    }

                    Toggle("boardset.cell.border_enabled", isOn: $useCustomBorderColor)
                    if useCustomBorderColor {
                        ColorPicker("boardset.cell.border_picker", selection: $editingBorderPickerColor, supportsOpacity: true)
                    }
                }

                if model.cellAt(row: editingRow, col: editingCol) != nil {
                    Section {
                        Button("boardset.cell.clear", role: .destructive) {
                            Task {
                                await model.clearSelectedBoardCell(row: editingRow, col: editingCol)
                                showEditCellSheet = false
                            }
                        }
                    }
                }
            }
            .navigationTitle(Text("boardset.cell.edit_title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") {
                        showEditCellSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") {
                        Task {
                            let backgroundColorHex = useCustomBackgroundColor ? hexFromColor(editingBackgroundPickerColor) : nil
                            let borderColorHex = useCustomBorderColor ? hexFromColor(editingBorderPickerColor) : nil
                            await model.upsertSelectedBoardCell(
                                row: editingRow,
                                col: editingCol,
                                label: editingLabel,
                                vocalization: editingVocalization,
                                backgroundColor: backgroundColorHex,
                                borderColor: borderColorHex,
                                linkedBoardId: editingLinkedBoardId,
                                imageUrl: editingSelectedSymbolUrl,
                                clearImage: shouldClearEditingSymbol
                            )
                            showEditCellSheet = false
                        }
                    }
                    .disabled(!model.canEditSelectedBoardSet)
                }
            }
        }
    }

    private var linkableBoardIds: [String] {
        guard let set = model.selectedBoardSet else { return [] }
        return set.boardIds.filter { $0 != model.selectedBoardId }
    }

    private var boardSentenceText: String {
        boardSentenceTokens
            .map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private func appendCellToSentenceIfNeeded(_ cell: BoardCellInfo?) {
        guard let cell else { return }
        // Do not add navigation cells to the sentence composition.
        if trimmed(cell.linkedBoardId) != nil { return }

        let title = trimmed(cell.label) ?? trimmed(cell.vocalization)
        let spokenText = trimmed(cell.vocalization) ?? trimmed(cell.label)
        guard let title, let spokenText else { return }

        let tokenId = UUID().uuidString
        activeSentenceAnimation = ActiveSentenceAnimation(sourceCellId: cell.id, tokenId: tokenId)

        withAnimation(.spring(response: 0.38, dampingFraction: 0.82)) {
            boardSentenceTokens.append(
                SentencePhraseToken(
                    id: tokenId,
                    phraseId: cell.buttonId,
                    text: spokenText,
                    title: title,
                    imageUrl: trimmed(cell.imageUrl)
                )
            )
        }

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 550_000_000)
            guard activeSentenceAnimation?.tokenId == tokenId else { return }
            withAnimation(.easeOut(duration: 0.18)) {
                activeSentenceAnimation = nil
            }
        }
    }

    private func openCellEditor(row: Int, col: Int, existing: BoardCellInfo?) {
        editingRow = row
        editingCol = col
        editingLabel = existing?.label ?? ""
        editingVocalization = existing?.vocalization ?? ""
        if let backgroundHex = trimmed(existing?.backgroundColor) {
            useCustomBackgroundColor = true
            editingBackgroundPickerColor = colorFromHex(backgroundHex, fallback: Color(.tertiarySystemBackground))
        } else {
            useCustomBackgroundColor = false
            editingBackgroundPickerColor = Color(.tertiarySystemBackground)
        }

        if let borderHex = trimmed(existing?.borderColor) {
            useCustomBorderColor = true
            editingBorderPickerColor = colorFromHex(borderHex, fallback: Color(.separator))
        } else {
            useCustomBorderColor = false
            editingBorderPickerColor = Color(.separator)
        }
        editingLinkedBoardId = existing?.linkedBoardId ?? ""
        editingSelectedSymbolUrl = existing?.imageUrl
        shouldClearEditingSymbol = false
        editingSymbolQuery = ""
        editingSymbolResults = []
        editingSymbolError = nil
        if let imageUrl = existing?.imageUrl,
           imageUrl.lowercased().hasPrefix("file://") {
            editingSymbolSource = .userPhotos
        } else {
            editingSymbolSource = .openSymbols
        }
        showEditCellSheet = true
    }

    private func trimmed(_ value: String?) -> String? {
        let text = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    private func colorFromHex(_ hex: String?, fallback: Color) -> Color {
        var value = (hex ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return fallback }
        if value.hasPrefix("#") {
            value.removeFirst()
        }

        guard let parsed = UInt64(value, radix: 16) else { return fallback }

        if value.count == 6 {
            let red = Double((parsed >> 16) & 0xFF) / 255.0
            let green = Double((parsed >> 8) & 0xFF) / 255.0
            let blue = Double(parsed & 0xFF) / 255.0
            return Color(red: red, green: green, blue: blue)
        }

        if value.count == 8 {
            let alpha = Double((parsed >> 24) & 0xFF) / 255.0
            let red = Double((parsed >> 16) & 0xFF) / 255.0
            let green = Double((parsed >> 8) & 0xFF) / 255.0
            let blue = Double(parsed & 0xFF) / 255.0
            return Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha)
        }

        return fallback
    }

    private func hexFromColor(_ color: Color) -> String {
        let uiColor = UIColor(color)
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0

        guard uiColor.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
            return "#000000"
        }

        let r = Int(round(red * 255.0))
        let g = Int(round(green * 255.0))
        let b = Int(round(blue * 255.0))
        let a = Int(round(alpha * 255.0))

        if a < 255 {
            return String(format: "#%02X%02X%02X%02X", a, r, g, b)
        }
        return String(format: "#%02X%02X%02X", r, g, b)
    }

    private func searchCellOpenSymbols() async {
        let trimmed = editingSymbolQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        guard let openSymbolsSecret = resolveCellOpenSymbolsSecret() else {
            await MainActor.run {
                editingSymbolError = NSLocalizedString("phrase.symbol.error.missing_secret", comment: "")
            }
            return
        }

        await MainActor.run {
            isSearchingCellSymbols = true
            editingSymbolError = nil
            editingSymbolResults = []
        }

        do {
            guard let tokenUrl = URL(string: "https://www.opensymbols.org/api/v2/token") else {
                await MainActor.run {
                    isSearchingCellSymbols = false
                    editingSymbolError = NSLocalizedString("phrase.symbol.error.token_url", comment: "")
                }
                return
            }

            var tokenRequest = URLRequest(url: tokenUrl)
            tokenRequest.httpMethod = "POST"
            tokenRequest.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            tokenRequest.httpBody = "secret=\(openSymbolsSecret)".data(using: .utf8)

            let (tokenData, tokenResponse) = try await URLSession.shared.data(for: tokenRequest)
            guard let tokenHttp = tokenResponse as? HTTPURLResponse, tokenHttp.statusCode == 200 else {
                await MainActor.run {
                    isSearchingCellSymbols = false
                    editingSymbolError = NSLocalizedString("phrase.symbol.error.auth_failed", comment: "")
                }
                return
            }

            let token = try JSONDecoder().decode(CellOpenSymbolsTokenResponse.self, from: tokenData).access_token

            var components = URLComponents(string: "https://www.opensymbols.org/api/v2/symbols")
            components?.queryItems = [
                URLQueryItem(name: "q", value: trimmed),
                URLQueryItem(name: "locale", value: "en"),
                URLQueryItem(name: "access_token", value: token)
            ]

            guard let symbolsUrl = components?.url else {
                await MainActor.run {
                    isSearchingCellSymbols = false
                    editingSymbolError = NSLocalizedString("phrase.symbol.error.search_url", comment: "")
                }
                return
            }

            let (symbolsData, symbolsResponse) = try await URLSession.shared.data(from: symbolsUrl)
            guard let symbolsHttp = symbolsResponse as? HTTPURLResponse, symbolsHttp.statusCode == 200 else {
                await MainActor.run {
                    isSearchingCellSymbols = false
                    editingSymbolError = NSLocalizedString("phrase.symbol.error.search_failed", comment: "")
                }
                return
            }

            let decoded = try JSONDecoder().decode([CellOpenSymbolsSymbolResult].self, from: symbolsData)

            await MainActor.run {
                editingSymbolResults = decoded
                isSearchingCellSymbols = false
            }
        } catch {
            await MainActor.run {
                isSearchingCellSymbols = false
                editingSymbolError = error.localizedDescription
            }
        }
    }

    private func importCellPhotoItem(_ item: PhotosPickerItem) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                await MainActor.run {
                    editingSymbolError = NSLocalizedString("phrase.symbol.error.photo_load_failed", comment: "")
                }
                return
            }
            let imageUrl = try persistCellImageData(data, preferredExtension: "jpg")
            await MainActor.run {
                editingSymbolSource = .userPhotos
                editingSelectedSymbolUrl = imageUrl
                editingSymbolError = nil
                shouldClearEditingSymbol = false
            }
        } catch {
            await MainActor.run {
                editingSymbolError = NSLocalizedString("phrase.symbol.error.photo_load_failed", comment: "")
            }
        }
    }

    private func importCellImageFile(_ sourceUrl: URL) async {
        do {
            let shouldStop = sourceUrl.startAccessingSecurityScopedResource()
            defer {
                if shouldStop { sourceUrl.stopAccessingSecurityScopedResource() }
            }

            let data = try Data(contentsOf: sourceUrl)
            let ext = sourceUrl.pathExtension.isEmpty ? "png" : sourceUrl.pathExtension
            let imageUrl = try persistCellImageData(data, preferredExtension: ext)
            await MainActor.run {
                editingSymbolSource = .userPhotos
                editingSelectedSymbolUrl = imageUrl
                editingSymbolError = nil
                shouldClearEditingSymbol = false
            }
        } catch {
            await MainActor.run {
                editingSymbolError = NSLocalizedString("phrase.symbol.error.import_failed", comment: "")
            }
        }
    }

    private func persistCellImageData(_ data: Data, preferredExtension: String) throws -> String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let symbolsDir = docs.appendingPathComponent("WingmateSymbols", isDirectory: true)
        if !FileManager.default.fileExists(atPath: symbolsDir.path) {
            try FileManager.default.createDirectory(at: symbolsDir, withIntermediateDirectories: true)
        }
        let filename = "\(UUID().uuidString).\(preferredExtension)"
        let destination = symbolsDir.appendingPathComponent(filename)
        try data.write(to: destination, options: .atomic)
        return destination.absoluteString
    }

    private func authenticateAndUnlock() {
        let context = LAContext()
        var authError: NSError?

        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &authError) else {
            authErrorMessage = authError?.localizedDescription ?? NSLocalizedString("boardset.unlock.not_available", comment: "")
            return
        }

        context.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: NSLocalizedString("boardset.unlock.reason", comment: "")
        ) { success, error in
            DispatchQueue.main.async {
                if success {
                    isEditModeEnabled = false
                    model.setSelectedBoardSetLocked(false)
                } else {
                    authErrorMessage = error?.localizedDescription ?? NSLocalizedString("boardset.unlock.failed", comment: "")
                }
            }
        }
    }
}
