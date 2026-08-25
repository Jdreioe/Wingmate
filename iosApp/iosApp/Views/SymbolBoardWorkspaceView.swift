import SwiftUI
import Shared
import LocalAuthentication
import PhotosUI
import UniformTypeIdentifiers
import UIKit
import AudioToolbox

private struct CellOpenSymbolsSymbolResult {
    let id: String
    let name: String
    let image_url: String?
    let source: String
}

private enum CellSymbolPackageFilter: String, CaseIterable, Identifiable {
    case all
    case opensymbols
    case mulberry
    case arasaac

    var id: String { rawValue }
    var localizationKey: LocalizedStringKey { LocalizedStringKey("phrase.symbol.package.\(rawValue)") }
}

private enum CellSymbolSource: String, CaseIterable, Identifiable {
    case openSymbols
    case userPhotos

    var id: String { rawValue }
}

private enum GridFieldSpanSelection: Hashable {
    case single
    case custom(GridFieldSpanInfo)

    static func == (lhs: GridFieldSpanSelection, rhs: GridFieldSpanSelection) -> Bool {
        switch (lhs, rhs) {
        case (.single, .single): return true
        case (.custom(let a), .custom(let b)): return a.rows == b.rows && a.columns == b.columns
        default: return false
        }
    }
}

struct GridFieldSpanInfo: Hashable {
    let rows: Int
    let columns: Int
}

private enum BoardSetCreationKind: String, CaseIterable, Identifiable {
    case blank
    case qwerty
    case alphabetical
    case quickCore24 = "quick-core-24"
    case quickCore40 = "quick-core-40"
    case quickCore60 = "quick-core-60"
    case quickCore84 = "quick-core-84"
    case quickCore112 = "quick-core-112"

    var id: String { rawValue }

    var quickCoreTitle: String? {
        switch self {
        case .quickCore24: NSLocalizedString("boardset.create_quick_core_24", comment: "")
        case .quickCore40: NSLocalizedString("boardset.create_quick_core_40", comment: "")
        case .quickCore60: NSLocalizedString("boardset.create_quick_core_60", comment: "")
        case .quickCore84: NSLocalizedString("boardset.create_quick_core_84", comment: "")
        case .quickCore112: NSLocalizedString("boardset.create_quick_core_112", comment: "")
        default: nil
        }
    }
}

private enum BoardKeyAction: String, CaseIterable, Identifiable {
    case none = ""
    case spell = ":spell"
    case space = ":space"
    case backspace = ":backspace"
    case clear = ":clear"
    case home = ":home"
    case speak = ":speak"
    case prediction = ":prediction"

    var id: String { rawValue }

    var localizationKey: LocalizedStringKey {
        switch self {
        case .none: "boardset.cell.action.none"
        case .spell: "boardset.cell.action.spell"
        case .space: "boardset.cell.action.space"
        case .backspace: "boardset.cell.action.backspace"
        case .clear: "boardset.cell.action.clear"
        case .home: "boardset.cell.action.home"
        case .speak: "boardset.cell.action.speak"
        case .prediction: "boardset.cell.action.prediction"
        }
    }
}

private func resolveCellOpenSymbolsProxyUrl() -> String? {
    let fromInfoRaw = Bundle.main.object(forInfoDictionaryKey: "OPEN_SYMBOLS_PROXY_URL") as? String
    let fromInfo = fromInfoRaw?.trimmingCharacters(in: .whitespacesAndNewlines)
    if let fromInfo, !fromInfo.isEmpty { return fromInfo }

    let fromEnvRaw = ProcessInfo.processInfo.environment["OPENSYMBOLS_PROXY_URL"]
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
    case backgroundColor

    var id: String { rawValue }
}

private enum BoardSetRoute: Equatable {
    case library
    case workspace(boardSetId: String, mode: BoardWorkspaceMode)
}

struct SymbolBoardWorkspaceView: View {
    @ObservedObject var model: IosViewModel
    @Environment(\.scenePhase) private var scenePhase
    @Namespace private var sentenceAnimationNamespace

    @State private var route: BoardSetRoute = .library
    @State private var hasAppliedStartupDestination = false
    @State private var showCreateBoardsetSheet = false
    @State private var showAddBoardSheet = false

    @State private var createBoardsetName: String = ""
    @State private var createBoardsetRows: Int = 4
    @State private var createBoardsetColumns: Int = 4
    @State private var createBoardsetKind: BoardSetCreationKind = .blank

    @State private var addBoardName: String = ""
    @State private var addBoardRows: Int = 4
    @State private var addBoardColumns: Int = 4
    @State private var addBoardKeyboardLayout: String = "qwerty"

    @State private var deleteTargetSet: BoardSetInfo? = nil
    @State private var authErrorMessage: String? = nil
    @State private var isFullscreen: Bool = false
    @State private var showEditCellSheet: Bool = false
    @State private var managementSheet: BoardManagementSheet? = nil
    @State private var managementName: String = ""
    @State private var managementRows: Int = 4
    @State private var managementColumns: Int = 4
    @State private var managementBackgroundColor: Color = Color(.secondarySystemBackground)
    @State private var useCustomManagementBackground = false
    @State private var showDeleteBoardConfirmation: Bool = false
    @State private var editingRow: Int = 0
    @State private var editingCol: Int = 0
    @State private var editingSpan: GridFieldSpanSelection = .single
    @State private var editingSpanOptions: [GridFieldSpanInfo] = []
    @State private var editingCellHasButton: Bool = false
    @State private var editingLabel: String = ""
    @State private var editingVocalization: String = ""
    @State private var editingInsertedText: String = ""
    @State private var editingActions: [String] = []
    @State private var editingInsertedTextFollowsLabel = false
    @State private var editingBackgroundPickerColor: Color = Color(.tertiarySystemBackground)
    @State private var editingWordType: String = ""
    @State private var editingBorderPickerColor: Color = Color(.separator)
    @State private var useCustomBackgroundColor: Bool = false
    @State private var useCustomBorderColor: Bool = false
    @State private var editingLinkedBoardId: String = ""
    @State private var editingSymbolSource: CellSymbolSource = .openSymbols
    @State private var editingSymbolQuery: String = ""
    @State private var editingSymbolPackage: CellSymbolPackageFilter = .all
    @State private var editingSelectedSymbolUrl: String? = nil
    @State private var editingSelectedPhotoItem: PhotosPickerItem? = nil
    @State private var isImportingCellSymbolFile: Bool = false
    @State private var editingSymbolResults: [CellOpenSymbolsSymbolResult] = []
    @State private var isSearchingCellSymbols: Bool = false
    @State private var editingSymbolError: String? = nil
    @State private var shouldClearEditingSymbol: Bool = false
    @State private var boardSentenceTokens: [SentencePhraseToken] = []
    @State private var showNativeKeyboardSheet = false
    @State private var nativeKeyboardDraft = ""
    @State private var activeSentenceAnimation: ActiveSentenceAnimation? = nil
    @State private var showHiddenButtons = false
    @State private var showEditingAccessSheet = false
    @State private var editingAccessCode = ""
    @State private var pendingEditingBoardSetId: String? = nil
    @State private var pendingDeleteBoardSetId: String? = nil
    @State private var editingAccessError = false

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
        .sheet(isPresented: $showNativeKeyboardSheet) {
            NavigationStack {
                Form {
                    TextField("boardset.native_keyboard.hint", text: $nativeKeyboardDraft, axis: .vertical)
                        .lineLimit(3...8)
                }
                .navigationTitle(Text("boardset.native_keyboard.title"))
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("common.cancel") { showNativeKeyboardSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("boardset.native_keyboard.return") {
                            boardSentenceTokens = nativeKeyboardDraft.isEmpty ? [] : [
                                SentencePhraseToken(
                                    phraseId: "native-keyboard",
                                    text: nativeKeyboardDraft,
                                    title: nativeKeyboardDraft,
                                    imageUrl: nil
                                )
                            ]
                            showNativeKeyboardSheet = false
                        }
                    }
                }
            }
        }
        .sheet(item: $managementSheet) { sheet in
            boardManagementSheet(sheet)
        }
        .sheet(isPresented: $showEditingAccessSheet) {
            NavigationStack {
                Form {
                    SecureField("editing_access.current_code", text: $editingAccessCode)
                        .keyboardType(.numberPad)
                    if editingAccessError { Text("editing_access.incorrect").foregroundStyle(.red) }
                }
                .navigationTitle(Text("editing_access.title"))
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("common.cancel") { showEditingAccessSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("common.ok") {
                            Task {
                                guard await model.unlockEditingAccess(editingAccessCode) else {
                                    editingAccessError = true
                                    return
                                }
                                showEditingAccessSheet = false
                                editingAccessCode = ""
                                if let deleteId = pendingDeleteBoardSetId {
                                    pendingDeleteBoardSetId = nil
                                    await model.deleteBoardSet(id: deleteId)
                                } else if let id = pendingEditingBoardSetId {
                                    pendingEditingBoardSetId = nil
                                    route = .workspace(boardSetId: id, mode: .edit)
                                }
                            }
                        }
                    }
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.lockEditingAccess() }
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
                        if await model.editingIsAuthorized() {
                            await model.deleteBoardSet(id: target.id)
                        } else {
                            pendingDeleteBoardSetId = target.id
                            pendingEditingBoardSetId = nil
                            editingAccessCode = ""
                            editingAccessError = false
                            showEditingAccessSheet = true
                        }
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
        ZStack {
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
                                        await requestEnterEditing(boardSetId: set.id)
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
                                onExport: {
                                    Task { await exportBoardSet(id: set.id) }
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
                            Button {
                                if let value = model.selectedBoard?.backgroundColor {
                                    managementBackgroundColor = colorFromHex(value, fallback: Color(.secondarySystemBackground))
                                    useCustomManagementBackground = true
                                } else {
                                    managementBackgroundColor = Color(.secondarySystemBackground)
                                    useCustomManagementBackground = false
                                }
                                managementSheet = .backgroundColor
                            } label: {
                                Label("boardset.background_color", systemImage: "paintpalette")
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
                    Button(action: { isFullscreen = true }) {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .font(.title3)
                            .foregroundColor(.blue)
                    }
                    .accessibilityLabel(Text("playback.fullscreen"))
                    Button(action: { showHiddenButtons.toggle() }) {
                        Image(systemName: showHiddenButtons ? "eye.slash" : "eye")
                            .font(.title3)
                            .foregroundColor(.blue)
                    }
                    .accessibilityLabel(
                        Text(showHiddenButtons ? "board_workspace.hide_hidden" : "board_workspace.show_hidden")
                    )
                    Button(action: {
                        if model.canEditSelectedBoardSet {
                            Task { await requestEnterEditing(boardSetId: boardSetId) }
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
                        Button(action: {
                            addBoardKeyboardLayout = model.selectedBoardKeyboardLayout ?? "qwerty"
                            showAddBoardSheet = true
                        }) {
                            Label("boardset.add_board", systemImage: "plus.rectangle.on.rectangle")
                                .font(.subheadline)
                        }
                        .buttonStyle(.bordered)

                        Spacer()
                    }
                    .padding(.horizontal)
                }

                // Sentence Box in Run mode
                if mode == .run && model.boardMessageBarVisible {
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
                        onSpeak: model.boardShowSpeakButton ? {
                            let sentence = boardSentenceText
                            guard !sentence.isEmpty else { return }
                            model.speakBoardSentence(sentence, boardSetId: boardSetId)
                        } : nil,
                        animationNamespace: sentenceAnimationNamespace,
                        animatedTokenId: activeSentenceAnimation?.tokenId
                    )
                    .padding(.horizontal)
                }

                boardPreview(isEditMode: mode == .edit)
                    .padding(.horizontal)
            }

        }
        .task(id: boardPredictionTaskId) {
            await model.refreshBoardPredictions(context: boardSentenceText)
        }
        .fullScreenCover(isPresented: $isFullscreen) {
            VStack(spacing: 12) {
                HStack {
                    if model.boardMessageBarVisible {
                        SentenceBoxView(
                            phrases: boardSentenceTokens,
                            onDelete: { index in
                                guard boardSentenceTokens.indices.contains(index) else { return }
                                boardSentenceTokens.remove(at: index)
                            },
                        onSpeak: model.boardShowSpeakButton ? {
                            let sentence = boardSentenceText
                            guard !sentence.isEmpty else { return }
                            model.speakBoardSentence(sentence, boardSetId: boardSetId)
                        } : nil
                    )
                    }
                    Button("common.done") { isFullscreen = false }
                        .font(.headline)
                }
                boardPreview(isEditMode: false)
            }
            .padding(16)
            .background(Color(.systemBackground))
        }
    }

    @ViewBuilder
    private func boardPreview(isEditMode: Bool) -> some View {
        if let board = model.selectedBoard {
            let rows = max(1, Int(board.grid?.rows ?? 1))
            let cols = max(1, Int(board.grid?.columns ?? 1))
            let gridGap: CGFloat = 8
            let gridInset: CGFloat = 8

            GeometryReader { geometry in
                let availableHeight = max(0, geometry.size.height - (gridInset * 2))
                let availableWidth = max(0, geometry.size.width - (gridInset * 2))
                let isCompact = board.compactGrid

                // Mirror Compose sizing: non-compact boards fill the full available
                // height (fraction 1), compact/keyboard boards size to their content
                // (fraction computed from a minimum cell height of 72pt).
                let minCellHeight: CGFloat = 72
                let minContentHeight = (minCellHeight * CGFloat(rows)) + (gridGap * CGFloat(rows - 1))
                let defaultFraction: CGFloat = isCompact
                    ? min(max(minContentHeight / max(minContentHeight, availableHeight), 0.15), 1)
                    : 1
                let rawGridHeightFraction = board.gridHeightFraction?.floatValue
                let gridHeightFraction: CGFloat = (rawGridHeightFraction?.isFinite == true) && (rawGridHeightFraction ?? 0) > 0
                    ? CGFloat(rawGridHeightFraction ?? 1)
                    : defaultFraction
                let fraction = min(max(gridHeightFraction, 0.15), 1)

                let contentHeight = max(0, availableHeight * fraction)
                let cellHeight = max(0, (contentHeight - (gridGap * CGFloat(rows - 1))) / CGFloat(rows))
                let cellWidth = max(0, (availableWidth - (gridGap * CGFloat(cols - 1))) / CGFloat(cols))
                let contentWidth = max(0, availableWidth)

                ScrollView(.vertical, showsIndicators: false) {
                    ZStack(alignment: .topLeading) {
                        ForEach(model.boardFieldItems) { field in
                            let x = CGFloat(field.column) * (cellWidth + gridGap)
                            let y = CGFloat(field.row) * (cellHeight + gridGap)
                            let spanWidth = (cellWidth * CGFloat(field.columnSpan)) + (gridGap * CGFloat(field.columnSpan - 1))
                            let spanHeight = (cellHeight * CGFloat(field.rowSpan)) + (gridGap * CGFloat(field.rowSpan - 1))
                            boardCellButton(
                                row: field.row,
                                col: field.column,
                                isEditMode: isEditMode,
                                width: max(0, spanWidth),
                                height: max(0, spanHeight),
                                rowSpan: field.rowSpan,
                                columnSpan: field.columnSpan
                            )
                            .offset(x: x, y: y)
                        }
                    }
                    .frame(width: contentWidth, height: contentHeight, alignment: .topLeading)
                    .padding(gridInset)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(
                    model.highContrastMode
                        ? Color(.systemBackground)
                        : colorFromHex(board.backgroundColor, fallback: Color(.secondarySystemBackground))
                )
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 24)
        }
    }

    private func boardCellButton(row: Int, col: Int, isEditMode: Bool, width: CGFloat, height: CGFloat, rowSpan: Int = 1, columnSpan: Int = 1) -> some View {
        let cell = model.cellAt(row: row, col: col)
        let cornerRadius = boardCellCornerRadius(cell, width: width, height: height)
        let isLinked = trimmed(cell?.linkedBoardId) != nil
        let sourceCellId = "\(row):\(col)"
        let hiddenInRunMode = !model.boardButtonIsVisible(
            hidden: cell?.hidden == true,
            isEditMode: isEditMode,
            showHiddenButtons: showHiddenButtons
        )

        return ZStack(alignment: .topTrailing) {
            Button {
                if isEditMode && model.canEditSelectedBoardSet {
                    openCellEditor(row: row, col: col, existing: cell)
                } else {
                    guard model.holdToSelectMillis <= 0 else { return }
                    if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
                    activateBoardCell(cell, row: row, col: col, sourceCellId: sourceCellId)
                }
            } label: {
                Group {
                    if let animation = activeSentenceAnimation, animation.sourceCellId == sourceCellId {
                        boardCellContent(row: row, col: col, cell: cell, isLinked: isLinked, isEditMode: isEditMode, width: width, height: height, rowSpan: rowSpan, columnSpan: columnSpan)
                            .matchedGeometryEffect(id: animation.tokenId, in: sentenceAnimationNamespace, isSource: true)
                            .zIndex(3)
                    } else {
                        boardCellContent(row: row, col: col, cell: cell, isLinked: isLinked, isEditMode: isEditMode, width: width, height: height, rowSpan: rowSpan, columnSpan: columnSpan)
                    }
                }
            }
            .buttonStyle(.plain)
            .frame(width: width, height: height)
            .simultaneousGesture(
                LongPressGesture(minimumDuration: max(0.01, model.holdToSelectMillis / 1_000))
                    .onEnded { _ in
                        guard !isEditMode, model.holdToSelectMillis > 0 else { return }
                        if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
                        activateBoardCell(cell, row: row, col: col, sourceCellId: sourceCellId)
                    }
            )
            .onHover { hovering in
                let targetId = "board:\(cell?.buttonId ?? sourceCellId)"
                if hovering {
                    model.accessEnter(targetId)
                    if model.auditoryFishingEnabled, !model.inputIsPaused,
                       let preview = trimmed(cell?.vocalization) ?? trimmed(cell?.label) {
                        model.speak(preview)
                    }
                } else {
                    model.accessExit(targetId)
                }
            }
            .onAppear {
                guard !isEditMode, cell != nil else { return }
                let targetId = "board:\(cell?.buttonId ?? sourceCellId)"
                model.registerAccessTarget(targetId) {
                    activateBoardCell(cell, row: row, col: col, sourceCellId: sourceCellId)
                }
            }
            .onDisappear { model.unregisterAccessTarget("board:\(cell?.buttonId ?? sourceCellId)") }
            .accessTargetFocus(model: model, targetId: "board:\(cell?.buttonId ?? sourceCellId)")
            .overlay {
                let targetId = "board:\(cell?.buttonId ?? sourceCellId)"
                if !isEditMode && model.accessTargetId == targetId && model.pointerEmphasisStyle != "System" {
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .stroke(Color.accentColor, lineWidth: 3 * model.pointerEmphasisScale)
                        .padding(6 / model.pointerEmphasisScale)
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                }
                if !isEditMode && model.accessTargetId == targetId && model.accessDwellProgress > 0 {
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .trim(from: 0, to: model.accessDwellProgress)
                        .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                }
            }
            .accessibilityLabel(Text(boardCellDisplayLabel(cell)))
            .accessibilityHint(Text(boardCellAccessibilityHint(cell)))
            .accessibilitySortPriority(Double(max(0, 10_000 - (row * 100 + col))))

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
            if cell?.hidden == true && !isEditMode && showHiddenButtons {
                Image(systemName: "eye.fill")
                    .foregroundStyle(.blue)
                    .padding(6)
                    .accessibilityLabel(Text("board_workspace.temporarily_revealed"))
            }
            if !isEditMode, isLinked, let linkedBoardId = trimmed(cell?.linkedBoardId) {
                let isHomeLink = linkedBoardId == model.selectedBoardSet?.rootBoardId
                Image(systemName: isHomeLink ? "house.fill" : "arrowshape.turn.up.right.fill")
                    .font(.caption)
                    .foregroundStyle(Color.primary.opacity(0.8))
                    .padding(4)
                    .background(.regularMaterial, in: Circle())
                    .overlay(Circle().stroke(Color(.separator), lineWidth: 1))
                    .accessibilityLabel(Text(isHomeLink ? "boardset.cell.action.home" : String(format: NSLocalizedString("board_cell_opens_board", comment: ""), model.boardDisplayName(id: linkedBoardId))))
            }
        }
        .opacity(hiddenInRunMode ? 0 : (cell?.hidden == true && isEditMode ? 0.5 : 1))
        .allowsHitTesting(!hiddenInRunMode)
        .accessibilityHidden(hiddenInRunMode || (model.scanningEnabled && !model.scanPhraseGridEnabled))
    }

    private func boardCellContent(row: Int, col: Int, cell: BoardCellInfo?, isLinked: Bool, isEditMode: Bool, width: CGFloat, height: CGFloat, rowSpan: Int = 1, columnSpan: Int = 1) -> some View {
        let labelScale = model.boardFieldFontScale(rowSpan: rowSpan, columnSpan: columnSpan)
        if cell == nil && isEditMode {
            return AnyView(
                ZStack {
                    RoundedRectangle(cornerRadius: 0)
                        .fill(Color.clear)
                        .overlay(
                            RoundedRectangle(cornerRadius: 0)
                                .strokeBorder(Color(.separator), style: StrokeStyle(lineWidth: 1, dash: [4]))
                        )
                    Image(systemName: "plus")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                }
                .frame(width: width, height: height)
            )
        }
        let isHighlighted = model.highlightedButtonId != nil && model.highlightedButtonId == cell?.buttonId
        return AnyView(
            VStack(alignment: .leading, spacing: 4) {
            if model.boardLabelAtTop && model.boardShowLabels {
                boardCellLabel(cell, fontScale: labelScale)
            }

            if model.boardShowSymbols, let imageUrl = trimmed(cell?.imageUrl), let url = URL(string: imageUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    case .failure(_):
                        EmptyView()
                    case .empty:
                        ProgressView()
                            .frame(maxWidth: .infinity, minHeight: 24)
                    @unknown default:
                        EmptyView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            if !model.boardLabelAtTop && model.boardShowLabels {
                boardCellLabel(cell, fontScale: labelScale)
            }

            if let linkedBoardId = trimmed(cell?.linkedBoardId) {
                Label(model.boardDisplayName(id: linkedBoardId), systemImage: "arrowshape.turn.up.right")
                    .font(.caption2)
                    .foregroundStyle(contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.secondary))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if let vocalization = trimmed(cell?.vocalization), vocalization != trimmed(cell?.label) {
                Text(vocalization)
                    .font(.caption2)
                    .foregroundStyle(contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.secondary))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(8)
        .frame(width: width, height: height, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height))
                .fill(model.highContrastMode ? Color(.systemBackground) : colorFromHex(cell?.resolvedBackgroundColor, fallback: Color(.tertiarySystemBackground)))
        )
        .overlay(
            RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height))
                .stroke(isHighlighted
                    ? (model.highContrastMode ? Color.white : contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.accentColor))
                    : (model.highContrastMode ? Color.primary : colorFromHex(cell?.borderColor, fallback: isLinked ? .accentColor : Color(.separator))),
                    lineWidth: isHighlighted ? 4 : (model.highContrastMode ? 2 : (isLinked ? 1.5 : 1)))
        )
        )
    }

    private func boardCellCornerRadius(_ cell: BoardCellInfo?, width: CGFloat, height: CGFloat) -> CGFloat {
        switch cell?.shape {
        case "rounded", "speech", "thought": return 10
        case "pill": return min(width, height) / 2
        default: return 0
        }
    }

    private func boardCellLabel(_ cell: BoardCellInfo?, fontScale: CGFloat = 1) -> some View {
        Text(boardCellDisplayLabel(cell))
            .font(.subheadline)
            .fontWeight(cell == nil ? .regular : .semibold)
            .lineLimit(2)
            .foregroundStyle(model.highContrastMode ? Color.primary : contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.primary))
            .frame(maxWidth: .infinity, alignment: .leading)
            .scaleEffect(fontScale, anchor: .leading)
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
                case .backgroundColor:
                    Section {
                        Toggle("boardset.background_custom", isOn: $useCustomManagementBackground)
                        if useCustomManagementBackground {
                            ColorPicker(
                                "boardset.background_color",
                                selection: $managementBackgroundColor,
                                supportsOpacity: false
                            )
                        }
                    } footer: {
                        Text("boardset.background_footer")
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
                            case .backgroundColor:
                                await model.setSelectedBoardBackgroundColor(
                                    useCustomManagementBackground ? hexFromColor(managementBackgroundColor) : nil
                                )
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
        case .backgroundColor: "boardset.background_color"
        }
    }

    private var createBoardsetSheet: some View {
        NavigationStack {
            Form {
                Section("boardset.name") {
                    TextField(NSLocalizedString("boardset.name_placeholder", comment: ""), text: $createBoardsetName)
                }
                Section("boardset.create_kind") {
                    Picker("boardset.create_kind", selection: $createBoardsetKind) {
                        Text("boardset.create_blank").tag(BoardSetCreationKind.blank)
                        Text("boardset.create_keyboard_qwerty").tag(BoardSetCreationKind.qwerty)
                        Text("boardset.create_keyboard_alphabetical").tag(BoardSetCreationKind.alphabetical)
                    }
                }
                if createBoardsetKind == .blank {
                    Section("boardset.grid") {
                        Stepper(value: $createBoardsetRows, in: 1...12) {
                            Text(String(format: NSLocalizedString("boardset.rows", comment: ""), createBoardsetRows))
                        }
                        Stepper(value: $createBoardsetColumns, in: 1...12) {
                            Text(String(format: NSLocalizedString("boardset.columns", comment: ""), createBoardsetColumns))
                        }
                    }
                }
                if model.isImportingQuickCore {
                    Section("boardset.quick_core.downloading") {
                        if let progress = model.quickCoreDownloadProgress {
                            ProgressView(value: progress)
                        } else {
                            ProgressView()
                        }
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
                        showCreateBoardsetSheet = false
                        Task {
                            if let quickCoreTitle = createBoardsetKind.quickCoreTitle {
                                await model.importQuickCorePreset(
                                    name: createBoardsetName.isEmpty ? quickCoreTitle : createBoardsetName,
                                    slug: createBoardsetKind.rawValue
                                )
                            } else if createBoardsetKind == .blank {
                                await model.createBoardSet(name: createBoardsetName, rows: createBoardsetRows, columns: createBoardsetColumns)
                            } else {
                                await model.createKeyboardBoardSet(name: createBoardsetName, preset: createBoardsetKind.rawValue)
                            }
                            createBoardsetName = ""
                            createBoardsetRows = 4
                            createBoardsetColumns = 4
                            createBoardsetKind = .blank
                        }
                    }
                    .disabled(model.isImportingQuickCore)
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
                if model.selectedBoardKeyboardLayout != nil {
                    Section("boardset.keyboard_layout") {
                        Picker("boardset.keyboard_layout", selection: $addBoardKeyboardLayout) {
                            Text("boardset.create_keyboard_qwerty").tag("qwerty")
                            Text("boardset.create_keyboard_alphabetical").tag("alphabetical")
                            Text("boardset.keyboard_symbols").tag("symbols")
                        }
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
                            if model.selectedBoardKeyboardLayout != nil {
                                await model.addKeyboardBoardToSelectedSet(
                                    name: addBoardName,
                                    rows: addBoardRows,
                                    columns: addBoardColumns,
                                    layout: addBoardKeyboardLayout
                                )
                            } else {
                                await model.addBoardToSelectedSet(name: addBoardName, rows: addBoardRows, columns: addBoardColumns)
                            }
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

                if model.canEditSelectedBoardSet, !model.isKeyboardBoard {
                    Section {
                        Picker("boardset.cell.merge_size", selection: $editingSpan) {
                            Text("boardset.cell.merge_1x1").tag(GridFieldSpanSelection.single)
                            ForEach(editingSpanOptions, id: \.self) { span in
                                Text("\(span.rows) × \(span.columns)")
                                    .tag(GridFieldSpanSelection.custom(span))
                            }
                        }
                        .pickerStyle(.menu)
                    } header: {
                        Text("boardset.cell.merge")
                    } footer: {
                        Text("boardset.cell.merge_footer")
                    }
                }

                if model.selectedBoardKeyboardLayout != nil {
                    Section("boardset.cell.keyboard_action") {
                        TextField("boardset.cell.insert_text", text: $editingInsertedText)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                            .onChange(of: editingInsertedText) { _, text in
                                editingInsertedTextFollowsLabel = false
                                if !text.isEmpty { editingActions = [] }
                            }
                        ForEach(BoardKeyAction.allCases.filter { $0 != .none }) { action in
                            Toggle(
                                action.localizationKey,
                                isOn: Binding(
                                    get: { editingActions.contains(action.rawValue) },
                                    set: { enabled in
                                        if enabled {
                                            editingInsertedText = ""
                                            editingInsertedTextFollowsLabel = false
                                            if !editingActions.contains(action.rawValue) {
                                                editingActions.append(action.rawValue)
                                            }
                                        } else {
                                            editingActions.removeAll { $0 == action.rawValue }
                                        }
                                    }
                                )
                            )
                        }
                        if editingActions.count > 1 {
                            ForEach(Array(editingActions.enumerated()), id: \.offset) { index, rawAction in
                                HStack {
                                    Text(boardKeyActionLabel(rawAction))
                                    Spacer()
                                    Button {
                                        moveEditingAction(at: index, by: -1)
                                    } label: {
                                        Image(systemName: "arrow.up")
                                    }
                                    .disabled(index == 0)
                                    Button {
                                        moveEditingAction(at: index, by: 1)
                                    } label: {
                                        Image(systemName: "arrow.down")
                                    }
                                    .disabled(index == editingActions.count - 1)
                                }
                            }
                        }
                    }
                }

                Section("phrase.symbol.section") {
                    Picker("phrase.symbol.source", selection: $editingSymbolSource) {
                        Text("phrase.symbol.source.opensymbols").tag(CellSymbolSource.openSymbols)
                        Text("phrase.symbol.source.user_photos").tag(CellSymbolSource.userPhotos)
                    }
                    .pickerStyle(.segmented)

                    if editingSymbolSource == .openSymbols {
                        Picker("phrase.symbol.package", selection: $editingSymbolPackage) {
                            ForEach(CellSymbolPackageFilter.allCases) { option in
                                Text(option.localizationKey).tag(option)
                            }
                        }
                        .pickerStyle(.segmented)

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

                        if !editingSymbolResults.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 10) {
                                    ForEach(editingSymbolResults.prefix(30), id: \.id) { symbol in
                                        Button {
                                            editingSelectedSymbolUrl = symbol.image_url
                                            shouldClearEditingSymbol = false
                                        } label: {
                                            VStack(spacing: 4) {
                                                if let imageUrl = symbol.image_url, let url = URL(string: imageUrl) {
                                                    AsyncImage(url: url) { phase in
                                                        if case .success(let image) = phase {
                                                            image.resizable().scaledToFit()
                                                        } else if case .empty = phase {
                                                            ProgressView()
                                                        } else {
                                                            Image(systemName: "photo").foregroundStyle(.secondary)
                                                        }
                                                    }
                                                    .frame(width: 56, height: 56)
                                                }
                                                Text(symbol.name).font(.caption2).lineLimit(1)
                                                Text(LocalizedStringKey("phrase.symbol.package.\(symbol.source)"))
                                                    .font(.caption2)
                                                    .foregroundStyle(.secondary)
                                            }
                                            .frame(width: 92, height: 116)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
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

                Section("boardset.cell.word_type") {
                    Picker("boardset.cell.word_type", selection: $editingWordType) {
                        Text("boardset.cell.word_type.automatic").tag("")
                        ForEach(["pronoun", "verb", "descriptor", "noun", "social", "other"], id: \.self) { type in
                            Text(LocalizedStringKey("boardset.cell.word_type.\(type)")).tag(type)
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
                            let followedText = trimmed(editingVocalization) ?? trimmed(editingLabel) ?? ""
                            let insertedText = editingInsertedTextFollowsLabel ? followedText : editingInsertedText
                            let actions = editingActions + (insertedText.isEmpty ? [] : ["+\(insertedText)"])
                            await model.upsertSelectedBoardCell(
                                row: editingRow,
                                col: editingCol,
                                label: editingLabel,
                                vocalization: editingVocalization,
                                backgroundColor: backgroundColorHex,
                                borderColor: borderColorHex,
                                linkedBoardId: editingLinkedBoardId,
                                imageUrl: editingSelectedSymbolUrl,
                                clearImage: shouldClearEditingSymbol,
                                actions: actions,
                                wordType: editingWordType
                            )
                            if case .custom(let span) = editingSpan, editingCellHasButton {
                                await model.resizeSelectedBoardField(
                                    row: editingRow,
                                    col: editingCol,
                                    rows: span.rows,
                                    columns: span.columns
                                )
                            }
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
        let tokens = boardSentenceTokens.map(\.text)
        return model.boardJoinSentenceText(tokens: tokens, spellingMode: model.selectedBoardUsesSpellingMode)
    }

    private var boardPredictionTaskId: String {
        let predictors = model.boardCells
            .filter { $0.actions.contains(where: { $0.lowercased().hasPrefix(":prediction") }) }
            .map(\.buttonId)
            .joined(separator: ",")
        return "\(model.selectedBoardId ?? "")|\(predictors)|\(boardSentenceText)"
    }

    private func activateBoardCell(_ cell: BoardCellInfo?, row: Int, col: Int, sourceCellId: String) {
        guard let cell else { return }
        if trimmed(cell.linkedBoardId) != nil {
            if let current = model.selectedBoardId {
                model.pushBoardNavigationStack(current)
            }
            Task { await model.activateSelectedBoardCell(row: row, col: col) }
            return
        }
        if cell.actions.isEmpty {
            let behavior = model.boardActivationBehavior
            let shouldAdd = behavior != "SpeakOnly"
            let shouldSpeak = model.shouldSpeakSelectionImmediately
            if shouldSpeak, let sound = trimmed(cell.soundDataUrl) {
                model.playBoardButtonSound(sound)
            }
            if shouldAdd {
                appendCellToSentenceIfNeeded(cell, sourceCellId: sourceCellId)
            }
            Task {
                if shouldSpeak {
                    await model.activateSelectedBoardCell(row: row, col: col)
                }
                await model.activateBoardSelectionHighlight(buttonId: cell.buttonId)
            }
            return
        }

        var isContentActivation = false
        for rawAction in cell.actions {
            let action = rawAction.trimmingCharacters(in: .whitespacesAndNewlines)
            switch action.lowercased() {
            case ":space":
                isContentActivation = true
                appendCellToSentenceIfNeeded(cell, textOverride: " ", sourceCellId: sourceCellId)
            case ":backspace":
                backspaceBoardSentence()
            case ":clear":
                boardSentenceTokens.removeAll()
            case ":speak":
                let sentence = boardSentenceText
                if !sentence.isEmpty, let boardSetId = model.selectedBoardSetId {
                    model.speakBoardSentence(sentence, boardSetId: boardSetId)
                }
            case ":home":
                if let rootId = model.selectedBoardSet?.rootBoardId {
                    Task { await model.selectBoard(id: rootId) }
                }
            case ":native-keyboard":
                nativeKeyboardDraft = boardSentenceText
                showNativeKeyboardSheet = true
            case ":prediction", ":predictions":
                if let suggestion = model.boardPrediction(for: cell.buttonId) {
                    isContentActivation = true
                    let insertion = predictionInsertion(sentence: boardSentenceText, suggestion: suggestion)
                    appendCellToSentenceIfNeeded(cell, textOverride: insertion, sourceCellId: sourceCellId)
                }
            case ":spell":
                isContentActivation = true
                if let text = trimmed(cell.vocalization) ?? trimmed(cell.label) {
                    appendCellToSentenceIfNeeded(cell, textOverride: text, sourceCellId: sourceCellId)
                }
            default:
                if action.hasPrefix("+") {
                    isContentActivation = true
                    appendCellToSentenceIfNeeded(cell, textOverride: String(action.dropFirst()), sourceCellId: sourceCellId)
                }
            }
        }
        if isContentActivation {
            if let sound = trimmed(cell.soundDataUrl) {
                model.playBoardButtonSound(sound)
            }
            Task { await model.activateBoardSelectionHighlight(buttonId: cell.buttonId) }
            Task { await model.applyBoardReturnBehavior() }
        }
    }

    private func appendCellToSentenceIfNeeded(_ cell: BoardCellInfo?, textOverride: String? = nil, sourceCellId: String? = nil) {
        guard let cell else { return }
        if trimmed(cell.linkedBoardId) != nil { return }

        let title = trimmed(textOverride) ?? trimmed(cell.label) ?? trimmed(cell.vocalization)
        let spokenText = textOverride ?? trimmed(cell.vocalization) ?? trimmed(cell.label)
        guard let title, let spokenText, !spokenText.isEmpty else { return }

        let tokenId = UUID().uuidString
        activeSentenceAnimation = ActiveSentenceAnimation(sourceCellId: sourceCellId ?? cell.id, tokenId: tokenId)

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

    private func backspaceBoardSentence() {
        guard !boardSentenceTokens.isEmpty else { return }
        let texts = boardSentenceTokens.map { $0.text }
        let trimmed = model.boardBackspaceSentence(
            texts: texts,
            spellingMode: model.selectedBoardUsesSpellingMode
        )
        if trimmed.count < texts.count {
            boardSentenceTokens.removeLast()
        } else if let lastText = trimmed.last {
            boardSentenceTokens[boardSentenceTokens.count - 1].text = lastText
            boardSentenceTokens[boardSentenceTokens.count - 1].title = lastText
        }
    }

    private func predictionInsertion(sentence: String, suggestion: String) -> String {
        model.nGramPredictionInsertion(sentence: sentence, suggestion: suggestion)
    }

    private func boardCellDisplayLabel(_ cell: BoardCellInfo?) -> String {
        guard let cell else { return NSLocalizedString("boardset.cell.empty", comment: "") }
        if cell.actions.contains(where: { $0.lowercased() == ":prediction" || $0.lowercased() == ":predictions" }),
           let prediction = model.boardPrediction(for: cell.buttonId) {
            return prediction
        }
        return trimmed(cell.label) ?? NSLocalizedString("boardset.cell.empty", comment: "")
    }

    private func boardCellAccessibilityHint(_ cell: BoardCellInfo?) -> String {
        guard let action = cell?.actions.first else { return "" }
        switch action.lowercased() {
        case ":space": return NSLocalizedString("boardset.cell.action.space", comment: "")
        case ":backspace": return NSLocalizedString("boardset.cell.action.backspace", comment: "")
        case ":clear": return NSLocalizedString("boardset.cell.action.clear", comment: "")
        case ":home": return NSLocalizedString("boardset.cell.action.home", comment: "")
        case ":speak": return NSLocalizedString("boardset.cell.action.speak", comment: "")
        case ":prediction", ":predictions": return NSLocalizedString("boardset.cell.action.prediction", comment: "")
        default: return ""
        }
    }

    private func moveEditingAction(at index: Int, by offset: Int) {
        let target = index + offset
        guard editingActions.indices.contains(index), editingActions.indices.contains(target) else { return }
        editingActions.swapAt(index, target)
    }

    private func boardKeyActionLabel(_ rawAction: String) -> String {
        guard let action = BoardKeyAction(rawValue: rawAction) else { return rawAction }
        switch action {
        case .none: return NSLocalizedString("boardset.cell.action.none", comment: "")
        case .spell: return NSLocalizedString("boardset.cell.action.spell", comment: "")
        case .space: return NSLocalizedString("boardset.cell.action.space", comment: "")
        case .backspace: return NSLocalizedString("boardset.cell.action.backspace", comment: "")
        case .clear: return NSLocalizedString("boardset.cell.action.clear", comment: "")
        case .home: return NSLocalizedString("boardset.cell.action.home", comment: "")
        case .speak: return NSLocalizedString("boardset.cell.action.speak", comment: "")
        case .prediction: return NSLocalizedString("boardset.cell.action.prediction", comment: "")
        }
    }

    private func openCellEditor(row: Int, col: Int, existing: BoardCellInfo?) {
        editingRow = row
        editingCol = col
        editingCellHasButton = existing != nil
        editingLabel = existing?.label ?? ""
        editingVocalization = existing?.vocalization ?? ""
        editingSpan = .single
        editingSpanOptions = []
        Task {
            let options = await model.availableFieldSpanOptions(row: row, col: col)
            let currentField = model.boardFieldItems.first { $0.row == row && $0.column == col }
            await MainActor.run {
                editingSpanOptions = options
                if let field = currentField, existing != nil,
                   let match = options.first(where: { $0.rows == field.rowSpan && $0.columns == field.columnSpan }) {
                    editingSpan = .custom(match)
                }
            }
        }
        let directText = existing?.actions.first(where: { $0.hasPrefix("+") }).map { String($0.dropFirst()) } ?? ""
        editingInsertedText = directText
        editingInsertedTextFollowsLabel = !directText.isEmpty && directText == (trimmed(existing?.vocalization) ?? trimmed(existing?.label) ?? "")
        editingActions = existing?.actions.filter { !$0.hasPrefix("+") } ?? []
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
        editingWordType = existing?.wordType ?? ""
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

    private func contrastingColor(for hex: String?, fallback: Color) -> Color {
        var value = (hex ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("#") { value.removeFirst() }
        guard value.count == 6, let parsed = UInt64(value, radix: 16) else { return fallback }
        func linear(_ channel: UInt64) -> Double {
            let value = Double(channel) / 255.0
            return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        let luminance = 0.2126 * linear((parsed >> 16) & 0xFF)
            + 0.7152 * linear((parsed >> 8) & 0xFF)
            + 0.0722 * linear(parsed & 0xFF)
        let blackContrast = (luminance + 0.05) / 0.05
        let whiteContrast = 1.05 / (luminance + 0.05)
        return blackContrast >= whiteContrast ? .black : .white
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

        let bridge = KoinBridge()
        bridge.setOpenSymbolsProxyUrl(url: resolveCellOpenSymbolsProxyUrl())

        await MainActor.run {
            isSearchingCellSymbols = true
            editingSymbolError = nil
            editingSymbolResults = []
        }

        guard let result = try? await bridge.openSymbolsSearch(
            query: trimmed,
            locale: Locale.current.identifier,
            symbolPackage: editingSymbolPackage.rawValue,
            prioritizeArasaac: false
        ) else {
            await MainActor.run {
                isSearchingCellSymbols = false
                editingSymbolError = NSLocalizedString("phrase.symbol.error.search_failed", comment: "")
            }
            return
        }

        await MainActor.run {
            if result.errorCode.isEmpty {
                editingSymbolResults = result.symbols.map {
                    CellOpenSymbolsSymbolResult(
                        id: $0.id,
                        name: $0.name ?? "",
                        image_url: $0.imageUrl,
                        source: $0.source
                    )
                }
            } else {
                editingSymbolError = NSLocalizedString("phrase.symbol.error.\(result.errorCode)", comment: "")
            }
            isSearchingCellSymbols = false
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

    @MainActor
    private func requestEnterEditing(boardSetId: String) async {
        if await model.editingIsAuthorized() {
            route = .workspace(boardSetId: boardSetId, mode: .edit)
        } else {
            pendingEditingBoardSetId = boardSetId
            editingAccessCode = ""
            editingAccessError = false
            showEditingAccessSheet = true
        }
    }

    private func exportBoardSet(id: String) async {
        let result = try? await IosDiBridge().boardsFacade().shareBoardSetAsObz(id: id)
        let message: String
        if let result, result.success {
            message = result.message
        } else {
            message = result?.message ?? NSLocalizedString("boardset.export_obz_failed", comment: "")
        }
        await MainActor.run {
            model.boardStatusMessage = message
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
    let onExport: () -> Void
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

                Button(action: onExport) {
                    Label("boardset.export_obz", systemImage: "square.and.arrow.up")
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
