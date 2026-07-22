import SwiftUI
import Shared
import LocalAuthentication
import PhotosUI
import UniformTypeIdentifiers
import UIKit
import AudioToolbox

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

private enum BoardWorkspaceMode: Equatable {
    case run
    case edit
}

private enum BoardManagementSheet: String, Identifiable {
    case renameSet
    case renameBoard
    case resizeBoard

    var id: String { rawValue }
}

private enum BoardSetRoute: Equatable {
    case library
    case workspace(boardSetId: String, mode: BoardWorkspaceMode)
}

struct SymbolBoardWorkspaceView: View {
    @ObservedObject var model: IosViewModel
    @Namespace private var sentenceAnimationNamespace

    @State private var route: BoardSetRoute = .library
    @State private var hasAppliedStartupDestination = false
    @State private var showCreateBoardsetSheet = false
    @State private var showAddBoardSheet = false

    @State private var createBoardsetName: String = ""
    @State private var createBoardsetRows: Int = 4
    @State private var createBoardsetColumns: Int = 4

    @State private var addBoardName: String = ""
    @State private var addBoardRows: Int = 4
    @State private var addBoardColumns: Int = 4

    @State private var deleteTargetSet: BoardSetInfo? = nil
    @State private var authErrorMessage: String? = nil
    @State private var isFullscreen: Bool = false
    @State private var showEditCellSheet: Bool = false
    @State private var managementSheet: BoardManagementSheet? = nil
    @State private var managementName: String = ""
    @State private var managementRows: Int = 4
    @State private var managementColumns: Int = 4
    @State private var showDeleteBoardConfirmation: Bool = false
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
        VStack(spacing: 0) {
            switch route {
            case .library:
                libraryView
            case .workspace(let boardSetId, let mode):
                workspaceView(boardSetId: boardSetId, mode: mode)
            }
        }
        .task {
            await model.loadBoardSets()
            if !hasAppliedStartupDestination {
                hasAppliedStartupDestination = true
                if let startupId = model.startupBoardSetId,
                   model.boardSets.contains(where: { $0.id == startupId }) {
                    await model.selectBoardSet(id: startupId)
                    route = .workspace(boardSetId: startupId, mode: .run)
                }
            }
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
        .sheet(item: $managementSheet) { sheet in
            boardManagementSheet(sheet)
        }
        .onChange(of: editingSelectedPhotoItem) { _, newItem in
            guard let newItem else { return }
            Task {
                await importCellPhotoItem(newItem)
            }
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
            NSLocalizedString("board_sets.library.delete_title", comment: ""),
            isPresented: Binding(
                get: { deleteTargetSet != nil },
                set: { if !$0 { deleteTargetSet = nil } }
            )
        ) {
            Button(NSLocalizedString("common_delete", comment: ""), role: .destructive) {
                if let target = deleteTargetSet {
                    Task {
                        await model.deleteBoardSet(id: target.id)
                        deleteTargetSet = nil
                    }
                }
            }
            Button(NSLocalizedString("common.cancel", comment: ""), role: .cancel) {
                deleteTargetSet = nil
            }
        } message: {
            Text(String(format: NSLocalizedString("board_sets.library.delete_message", comment: ""), deleteTargetSet?.name ?? ""))
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
        .alert("boardset.delete_board", isPresented: $showDeleteBoardConfirmation) {
            Button("common.cancel", role: .cancel) {}
            Button("common_delete", role: .destructive) {
                Task { await model.deleteSelectedBoard() }
            }
        } message: {
            Text("boardset.delete_board.message")
        }
    }

    // MARK: - Library View
    @ViewBuilder
    private var libraryView: some View {
        VStack(spacing: 16) {
            // Header bar
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

                Button(action: { showCreateBoardsetSheet = true }) {
                    Label("boardset.create", systemImage: "plus")
                        .font(.headline)
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(.horizontal)
            .padding(.top, 12)

            if let status = model.boardStatusMessage, !status.isEmpty {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
            }

            if model.boardSets.isEmpty {
                emptyLibraryState
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(model.boardSets) { set in
                            BoardSetLibraryCard(
                                set: set,
                                onOpen: {
                                    Task {
                                        await model.selectBoardSet(id: set.id)
                                        route = .workspace(boardSetId: set.id, mode: .run)
                                    }
                                },
                                onEdit: {
                                    Task {
                                        await model.selectBoardSet(id: set.id)
                                        route = .workspace(boardSetId: set.id, mode: .edit)
                                    }
                                },
                                onDuplicate: {
                                    Task { await model.duplicateBoardSet(id: set.id) }
                                },
                                onToggleLock: {
                                    if set.isLocked {
                                        authenticateAndUnlock(for: set)
                                    } else {
                                        Task {
                                            await model.selectBoardSet(id: set.id)
                                            model.setSelectedBoardSetLocked(true)
                                        }
                                    }
                                },
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
    }

    private var emptyLibraryState: some View {
        VStack(spacing: 16) {
            Spacer(minLength: 40)
            Image(systemName: "square.grid.3x3")
                .font(.system(size: 60))
                .foregroundColor(.secondary)

            Text(NSLocalizedString("board_sets.library.empty_title", comment: ""))
                .font(.title2)
                .fontWeight(.bold)

            Text(NSLocalizedString("board_sets.library.empty_body", comment: ""))
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal)

            Button(action: { showCreateBoardsetSheet = true }) {
                Text(NSLocalizedString("boardset.create_first", comment: ""))
                    .font(.headline)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            Spacer(minLength: 40)
        }
    }

    // MARK: - Workspace View (Run & Edit Modes)
    @ViewBuilder
    private func workspaceView(boardSetId: String, mode: BoardWorkspaceMode) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Workspace Header
            HStack {
                Button(action: {
                    route = .library
                }) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                        Text(NSLocalizedString("board_workspace.exit_library", comment: ""))
                    }
                    .font(.headline)
                    .foregroundColor(.blue)
                }

                Spacer()

                if mode == .edit {
                    Text(String(format: NSLocalizedString("board_workspace.editing", comment: ""), model.selectedBoardSet?.name ?? ""))
                        .font(.headline)
                        .fontWeight(.bold)
                } else {
                    Text(model.selectedBoard?.name ?? NSLocalizedString("boardset.untitled_board", comment: ""))
                        .font(.headline)
                        .fontWeight(.bold)
                }

                Spacer()

                if mode == .edit {
                    HStack(spacing: 12) {
                        Menu {
                            Button {
                                managementName = model.selectedBoardSet?.name ?? ""
                                managementSheet = .renameSet
                            } label: {
                                Label("boardset.rename_set", systemImage: "pencil")
                            }
                            Button {
                                managementName = model.selectedBoard?.name ?? ""
                                managementSheet = .renameBoard
                            } label: {
                                Label("boardset.rename_board", systemImage: "rectangle.and.pencil.and.ellipsis")
                            }
                            Button {
                                managementRows = max(1, Int(model.selectedBoard?.grid?.rows ?? 4))
                                managementColumns = max(1, Int(model.selectedBoard?.grid?.columns ?? 4))
                                managementSheet = .resizeBoard
                            } label: {
                                Label("boardset.resize_board", systemImage: "arrow.up.left.and.arrow.down.right")
                            }
                            if let set = model.selectedBoardSet,
                               let boardId = model.selectedBoardId,
                               boardId != set.rootBoardId {
                                Button {
                                    Task { await model.makeSelectedBoardRoot() }
                                } label: {
                                    Label("boardset.make_home", systemImage: "house")
                                }
                                Divider()
                                Button(role: .destructive) {
                                    showDeleteBoardConfirmation = true
                                } label: {
                                    Label("boardset.delete_board", systemImage: "trash")
                                }
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle").font(.title3)
                        }

                        Button(action: {
                            route = .workspace(boardSetId: boardSetId, mode: .run)
                        }) {
                            Text(NSLocalizedString("board_workspace.finish", comment: ""))
                                .font(.headline)
                                .foregroundColor(.blue)
                        }
                    }
                } else {
                    Button(action: {
                        if model.canEditSelectedBoardSet {
                            route = .workspace(boardSetId: boardSetId, mode: .edit)
                        } else {
                            authenticateAndUnlock(for: model.selectedBoardSet)
                        }
                    }) {
                        Image(systemName: "pencil")
                            .font(.title3)
                            .foregroundColor(.blue)
                    }
                }
            }
            .padding(.horizontal)
            .padding(.top, 8)

            // Sub-board Strip (in Edit Mode for quick switching)
            if mode == .edit, let selectedSet = model.selectedBoardSet, selectedSet.boardIds.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(selectedSet.boardIds, id: \.self) { id in
                            Button(action: {
                                Task { await model.selectBoard(id: id) }
                            }) {
                                HStack(spacing: 5) {
                                    if id == selectedSet.rootBoardId {
                                        Image(systemName: "house.fill").font(.caption2)
                                    }
                                    Text(model.boardDisplayName(id: id))
                                        .font(.subheadline)
                                        .fontWeight(model.selectedBoardId == id ? .bold : .regular)
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(model.selectedBoardId == id ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground))
                                .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }
            }

            // Controls & Lock status
            if let selectedSet = model.selectedBoardSet {
                if mode == .edit {
                    HStack(spacing: 10) {
                        Button(action: { showAddBoardSheet = true }) {
                            Label("boardset.add_board", systemImage: "plus.rectangle.on.rectangle")
                                .font(.subheadline)
                        }
                        .buttonStyle(.bordered)

                        Spacer()
                    }
                    .padding(.horizontal)
                }

                // Sentence Box in Run mode
                if mode == .run {
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
                            model.speakBoardSentence(sentence, boardSetId: boardSetId)
                        },
                        animationNamespace: sentenceAnimationNamespace,
                        animatedTokenId: activeSentenceAnimation?.tokenId
                    )
                    .padding(.horizontal)
                }

                boardPreview(isEditMode: mode == .edit)
                    .padding(.horizontal)
            }

            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private func boardPreview(isEditMode: Bool) -> some View {
        if let board = model.selectedBoard {
            let rows = max(1, Int(board.grid?.rows ?? 1))
            let cols = max(1, Int(board.grid?.columns ?? 1))
            let previewCols = Array(repeating: GridItem(.flexible(), spacing: 8), count: cols)

            VStack(alignment: .leading, spacing: 10) {
                LazyVGrid(columns: previewCols, spacing: 8) {
                    ForEach(0..<(rows * cols), id: \.self) { idx in
                        let row = idx / cols
                        let col = idx % cols
                        boardCellButton(row: row, col: col, isEditMode: isEditMode)
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

    private func boardCellButton(row: Int, col: Int, isEditMode: Bool) -> some View {
        let cell = model.cellAt(row: row, col: col)
        let isLinked = trimmed(cell?.linkedBoardId) != nil
        let sourceCellId = "\(row):\(col)"

        return ZStack(alignment: .topTrailing) {
            Button {
                if isEditMode && model.canEditSelectedBoardSet {
                    openCellEditor(row: row, col: col, existing: cell)
                } else {
                    guard model.holdToSelectMillis <= 0 else { return }
                    if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
                    appendCellToSentenceIfNeeded(cell)
                    Task { await model.activateSelectedBoardCell(row: row, col: col) }
                }
            } label: {
                Group {
                    if let animation = activeSentenceAnimation, animation.sourceCellId == sourceCellId {
                        boardCellContent(row: row, col: col, cell: cell, isLinked: isLinked, isEditMode: isEditMode)
                            .matchedGeometryEffect(id: animation.tokenId, in: sentenceAnimationNamespace, isSource: true)
                            .zIndex(3)
                    } else {
                        boardCellContent(row: row, col: col, cell: cell, isLinked: isLinked, isEditMode: isEditMode)
                    }
                }
            }
            .buttonStyle(.plain)
            .simultaneousGesture(
                LongPressGesture(minimumDuration: max(0.01, model.holdToSelectMillis / 1_000))
                    .onEnded { _ in
                        guard !isEditMode, model.holdToSelectMillis > 0 else { return }
                        if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
                        appendCellToSentenceIfNeeded(cell)
                        Task { await model.activateSelectedBoardCell(row: row, col: col) }
                    }
            )
            .onHover { hovering in
                if hovering && model.auditoryFishingEnabled,
                   let preview = trimmed(cell?.vocalization) ?? trimmed(cell?.label) {
                    model.speak(preview)
                }
            }

            if isEditMode, model.canEditSelectedBoardSet, cell != nil {
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
            }
        }
    }

    private func boardCellContent(row: Int, col: Int, cell: BoardCellInfo?, isLinked: Bool, isEditMode: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if model.labelAtTop && model.showButtonLabels {
                boardCellLabel(cell)
            }

            if model.showButtonSymbols, let imageUrl = trimmed(cell?.imageUrl), let url = URL(string: imageUrl) {
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

            if !model.labelAtTop && model.showButtonLabels {
                boardCellLabel(cell)
            }

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
        }
        .padding(8)
        .frame(minHeight: 72, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(model.highContrastMode ? Color(.systemBackground) : colorFromHex(cell?.backgroundColor, fallback: Color(.tertiarySystemBackground)))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(model.highContrastMode ? Color.primary : colorFromHex(cell?.borderColor, fallback: isLinked ? .accentColor : Color(.separator)), lineWidth: model.highContrastMode ? 2 : (isLinked ? 1.5 : 1))
        )
    }

    private func boardCellLabel(_ cell: BoardCellInfo?) -> some View {
        Text(trimmed(cell?.label) ?? NSLocalizedString("boardset.cell.empty", comment: ""))
            .font(.subheadline)
            .fontWeight(cell == nil ? .regular : .semibold)
            .lineLimit(2)
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func boardManagementSheet(_ sheet: BoardManagementSheet) -> some View {
        NavigationStack {
            Form {
                switch sheet {
                case .renameSet, .renameBoard:
                    Section {
                        TextField("boardset.name_placeholder", text: $managementName)
                            .textInputAutocapitalization(.words)
                    }
                case .resizeBoard:
                    Section {
                        Stepper(value: $managementRows, in: 1...12) {
                            Text(String(format: NSLocalizedString("boardset.rows", comment: ""), managementRows))
                        }
                        Stepper(value: $managementColumns, in: 1...12) {
                            Text(String(format: NSLocalizedString("boardset.columns", comment: ""), managementColumns))
                        }
                    } header: {
                        Text("boardset.grid")
                    } footer: {
                        Text("boardset.resize_warning")
                    }
                }
            }
            .navigationTitle(Text(managementTitle(sheet)))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { managementSheet = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") {
                        Task {
                            switch sheet {
                            case .renameSet:
                                await model.renameSelectedBoardSet(managementName)
                            case .renameBoard:
                                await model.renameSelectedBoard(managementName)
                            case .resizeBoard:
                                await model.resizeSelectedBoard(rows: managementRows, columns: managementColumns)
                            }
                            managementSheet = nil
                        }
                    }
                    .disabled((sheet == .renameSet || sheet == .renameBoard) && managementName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func managementTitle(_ sheet: BoardManagementSheet) -> LocalizedStringKey {
        switch sheet {
        case .renameSet: "boardset.rename_set"
        case .renameBoard: "boardset.rename_board"
        case .resizeBoard: "boardset.resize_board"
        }
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

    private func authenticateAndUnlock(for set: BoardSetInfo?) {
        guard let set else { return }
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
                    Task {
                        await model.selectBoardSet(id: set.id)
                        model.setSelectedBoardSetLocked(false)
                    }
                } else {
                    authErrorMessage = error?.localizedDescription ?? NSLocalizedString("boardset.unlock.failed", comment: "")
                }
            }
        }
    }
}

// MARK: - BoardSet Library Card Component
struct BoardSetLibraryCard: View {
    let set: BoardSetInfo
    let onOpen: () -> Void
    let onEdit: () -> Void
    let onDuplicate: () -> Void
    let onToggleLock: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(set.name)
                            .font(.headline)
                            .fontWeight(.semibold)
                            .foregroundColor(.primary)

                        if set.isLocked {
                            Image(systemName: "lock.fill")
                                .font(.caption)
                                .foregroundColor(.orange)
                        }
                    }

                    Text(String(format: NSLocalizedString("boardset.grid_summary", comment: ""), set.boardIds.count, 0, 0))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Button(action: onOpen) {
                    HStack {
                        Image(systemName: "play.fill")
                        Text(NSLocalizedString("board_sets.library.open", comment: ""))
                    }
                    .font(.subheadline)
                    .fontWeight(.medium)
                }
                .buttonStyle(.borderedProminent)
            }

            Divider()

            HStack(spacing: 12) {
                Button(action: onEdit) {
                    Label("board_sets.library.open", systemImage: "pencil")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .disabled(set.isLocked)

                Button(action: onDuplicate) {
                    Label("board_sets.library.duplicate", systemImage: "doc.on.doc")
                        .font(.caption)
                }
                .buttonStyle(.bordered)

                Button(action: onToggleLock) {
                    Label(set.isLocked ? "boardset.unlock" : "boardset.lock", systemImage: set.isLocked ? "lock.open.fill" : "lock.fill")
                        .font(.caption)
                }
                .buttonStyle(.bordered)

                Spacer()

                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                        .font(.caption)
                        .foregroundColor(.red)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.secondarySystemBackground))
                .shadow(color: Color.black.opacity(0.05), radius: 2, x: 0, y: 1)
        )
    }
}
