import SwiftUI
import Shared
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

    /// Resolved catalog text. The key is built as a String first: interpolating inside
    /// LocalizedStringKey looks up a format key and shows the raw key.
    var localizedTitle: String { Self.title(for: rawValue) }

    static func title(for source: String) -> String {
        switch source {
        case Self.all.rawValue: return NSLocalizedString("phrase.symbol.package.all", comment: "")
        case Self.opensymbols.rawValue: return NSLocalizedString("phrase.symbol.package.opensymbols", comment: "")
        case Self.mulberry.rawValue: return NSLocalizedString("phrase.symbol.package.mulberry", comment: "")
        case Self.arasaac.rawValue: return NSLocalizedString("phrase.symbol.package.arasaac", comment: "")
        default: return source
        }
    }
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

private struct BoardCellAnchor: Equatable {
    var row: Int
    var column: Int
}

private enum FieldResizeEdge {
    case corner
    case end
    case bottom
}

private struct BoardGridMetrics {
    var cellWidth: CGFloat = 1
    var cellHeight: CGFloat = 1
    var gap: CGFloat = 8
    var rows: Int = 1
    var columns: Int = 1
}

private struct FieldDragState: Equatable {
    var buttonId: String
    var fromRow: Int
    var fromColumn: Int
    var translation: CGSize
    var targetRow: Int
    var targetColumn: Int
}

private enum BoardSetCreationKind: String, CaseIterable, Identifiable {
    case blank
    case qwerty
    case alphabetical

    var id: String { rawValue }
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

private enum BoardWorkspaceMode: Equatable {
    case run
    case edit
}

private enum BoardSettingsTarget: String, Identifiable {
    case screen
    case page

    var id: String { rawValue }
    var forPage: Bool { self == .page }
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

private struct FieldEditActions: ViewModifier {
    var enabled: Bool
    var moveUp: () -> Void
    var moveDown: () -> Void
    var moveLeft: () -> Void
    var moveRight: () -> Void
    var wider: () -> Void
    var narrower: () -> Void
    var taller: () -> Void
    var shorter: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .accessibilityAction(named: Text("boardset.cell.move.up"), moveUp)
                .accessibilityAction(named: Text("boardset.cell.move.down"), moveDown)
                .accessibilityAction(named: Text("boardset.cell.move.left"), moveLeft)
                .accessibilityAction(named: Text("boardset.cell.move.right"), moveRight)
                .accessibilityAction(named: Text("boardset.cell.size.wider"), wider)
                .accessibilityAction(named: Text("boardset.cell.size.narrower"), narrower)
                .accessibilityAction(named: Text("boardset.cell.size.taller"), taller)
                .accessibilityAction(named: Text("boardset.cell.size.shorter"), shorter)
        } else {
            content
        }
    }
}

struct SymbolBoardWorkspaceView: View {
    @ObservedObject var model: IosViewModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// Opens the app settings panel, which ContentView owns.
    var onShowSettings: () -> Void = {}
    @Namespace private var sentenceAnimationNamespace

    @State private var backgroundPhoto: PhotosPickerItem?
    @State private var showBackgroundEditor = false
    @State private var importingBackground = false
    @State private var backgroundFit = "Fill"
    @State private var pdfUrl: URL?
    @State private var exportingPdf = false
    @State private var route: BoardSetRoute = .library
    @State private var hasAppliedStartupDestination = false
    @State private var showCreateBoardsetSheet = false
    @State private var showSpeechImport = false
    @State private var speechName = ""
    @State private var speechText = ""
    @State private var speechParagraphsPerPage = 4
    @State private var speechImportFailed = false
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
    @State private var editingHidden = false
    @State private var editingMathMode = false
    @State private var editingShape = "rounded"
    @State private var editingLanguage = ""
    @State private var editingOpensPage = false
    @State private var editingRecordingPath: String? = nil
    @State private var editingClearsRecording = false
    @State private var isRecordingCell = false
    @State private var cellMicDenied = false
    @State private var cellRecorder = AudioRecorder()
    @State private var editingBorderPickerColor: Color = Color(.separator)
    @State private var settingsTarget: BoardSettingsTarget? = nil
    @State private var selectedFieldAnchor: BoardCellAnchor? = nil
    @State private var selectedFieldSpans: [GridFieldSpanInfo] = []
    @State private var fieldDrag: FieldDragState? = nil
    @State private var resizePreview: GridFieldSpanInfo? = nil
    @State private var resizePreviewValid = true
    @State private var suppressNextCellTap = false
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
    @State private var showNativeKeyboardSheet = false
    @State private var nativeKeyboardDraft = ""
    @State private var activeSentenceAnimation: ActiveSentenceAnimation? = nil
    @State private var showHiddenButtons = false
    @State private var showEditingAccessSheet = false
    @State private var pinMode: EditingAccessPinMode = .unlock
    @State private var pendingEditingBoardSetId: String? = nil
    @State private var pendingDeleteBoardSetId: String? = nil
    @State private var pendingBoardLockId: String? = nil
    @State private var pendingBoardLockUnlocks = false

    private struct ActiveSentenceAnimation: Equatable {
        let sourceCellId: String
        let tokenId: String
    }

    var body: some View {
        VStack(spacing: 0) {
            if exportingPdf { ProgressView("boardset.print_preparing").padding(8) }
            if let status = model.boardStatusMessage, !status.isEmpty {
                Text(status).font(.footnote).padding(8).accessibilityAddTraits(.updatesFrequently)
            }
            switch route {
            case .library:
                libraryView
            case .workspace(let boardSetId, let mode):
                workspaceView(boardSetId: boardSetId, mode: mode)
            }
        }
        .sheet(isPresented: $showBackgroundEditor) {
            NavigationStack {
                Form {
                    if let status = model.boardStatusMessage { Text(status).foregroundStyle(.secondary) }
                    if importingBackground { ProgressView() }
                    PhotosPicker(selection: $backgroundPhoto, matching: .images) {
                        Label("boardset.background_choose", systemImage: "photo")
                    }
                    Picker("boardset.background_fit", selection: $backgroundFit) {
                        Text("boardset.background_fill").tag("Fill")
                        Text("boardset.background_fit").tag("Fit")
                    }
                    .onChange(of: backgroundFit) { _, fit in
                        if let source = model.boardBackgroundImageUrl {
                            Task { await model.setPageBackground(imageUrl: source, fit: fit) }
                        }
                    }
                    Button("boardset.background_remove", role: .destructive) {
                        Task { await model.setPageBackground(imageUrl: nil, fit: backgroundFit) }
                    }.disabled(model.boardBackgroundImageUrl == nil)
                }
                .disabled(importingBackground)
                .navigationTitle("boardset.background_image")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("common.done") { showBackgroundEditor = false }.disabled(importingBackground) } }
            }
            .interactiveDismissDisabled(importingBackground)
        }
        .sheet(isPresented: Binding(get: { pdfUrl != nil }, set: { if !$0 { pdfUrl = nil } })) {
            if let pdfUrl { ScreenPdfShareSheet(url: pdfUrl) }
        }
        .onChange(of: backgroundPhoto) { _, item in
            guard let item, !importingBackground else { return }
            importingBackground = true
            Task {
                defer { importingBackground = false; backgroundPhoto = nil }
                do {
                    guard let original = try await item.loadTransferable(type: Data.self), let data = UIImage(data: original)?.pngData() else {
                        model.boardStatusMessage = NSLocalizedString("boardset.background_failed", comment: "")
                        return
                    }
                    let url = try await Task.detached {
                        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("WingmateSymbols", isDirectory: true)
                        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                        let url = directory.appendingPathComponent("\(UUID().uuidString).png")
                        try data.write(to: url, options: .atomic)
                        return url
                    }.value
                    await model.setPageBackground(imageUrl: url.absoluteString, fit: backgroundFit)
                } catch { model.boardStatusMessage = NSLocalizedString("boardset.background_failed", comment: "") }
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
        .sheet(isPresented: $showSpeechImport) {
            speechImportSheet
        }
        .sheet(isPresented: $showCreateBoardsetSheet) {
            createBoardsetSheet
        }
        .sheet(isPresented: $showAddBoardSheet) {
            addBoardSheet
        }
        .sheet(isPresented: $showEditCellSheet) {
            editCellSheet
                .presentationBackground(Color(.systemGroupedBackground))
        }
        .sheet(item: $settingsTarget) { target in
            BoardSettingsSheet(model: model, forPage: target.forPage) {
                settingsTarget = nil
            }
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
                            model.onInputChanged(nativeKeyboardDraft)
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
            EditingAccessPinSheet(model: model, mode: pinMode, onSuccess: {
                showEditingAccessSheet = false
                Task { await finishEditingAccessSuccess() }
            }, onCancel: {
                showEditingAccessSheet = false
                pendingEditingBoardSetId = nil
                pendingDeleteBoardSetId = nil
                pendingBoardLockId = nil
            })
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
            Button(NSLocalizedString("common.delete", comment: ""), role: .destructive) {
                if let target = deleteTargetSet {
                    Task {
                        if await model.editingIsAuthorized() {
                            await model.deleteBoardSet(id: target.id)
                        } else {
                            pendingDeleteBoardSetId = target.id
                            pendingEditingBoardSetId = nil
                            pendingBoardLockId = nil
                            pinMode = .unlock
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
        .alert("boardset.delete_board", isPresented: $showDeleteBoardConfirmation) {
            Button("common.cancel", role: .cancel) {}
            Button("common.delete", role: .destructive) {
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
                                    Task { await exportBoardSet(id: set.id) }
                                },
                                onPrint: { printScreen(id: set.id) },
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
                .foregroundStyle(.primary)
                .padding(.horizontal)

            Text("board_sets.library.empty_guidance")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
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
            Spacer(minLength: 40)
        }
    }

    // MARK: - Workspace View (Run & Edit Modes)
    @ViewBuilder
    private func workspaceView(boardSetId: String, mode: BoardWorkspaceMode) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Neither mode shows the app's navigation bar. Edit mode has one row:
            // back, "Editing <Screen>" (its menu), the Pages (the current one has a menu), Add Page and Done. Use
            // mode has only the slim menu row above the message bar.
            if mode == .edit {
            VStack(alignment: .leading, spacing: 4) {
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

                // The Screen's own actions sit on its title.
                Menu {
                    Button {
                        managementName = model.selectedBoardSet?.name ?? ""
                        managementSheet = .renameSet
                    } label: {
                        Label("boardset.rename_set", systemImage: "pencil")
                    }
                    Button {
                        settingsTarget = .screen
                    } label: { Label("board_settings.screen_title", systemImage: "gearshape") }
                } label: {
                    HStack(spacing: 4) {
                        Text(String(format: NSLocalizedString("board_workspace.editing", comment: ""), model.selectedBoardSet?.name ?? ""))
                            .lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption.weight(.semibold)).accessibilityHidden(true)
                    }
                    .font(.headline)
                    .foregroundStyle(Color.primary)
                    .frame(minHeight: 44)
                }
                .layoutPriority(1)
                .accessibilityAddTraits(.isHeader)

                pageStrip(boardSetId: boardSetId)

                Button(action: {
                    route = .workspace(boardSetId: boardSetId, mode: .run)
                }) {
                    Text(NSLocalizedString("board_workspace.finish", comment: ""))
                        .font(.headline)
                        .foregroundColor(.blue)
                        .frame(minHeight: 44)
                }
            }
            .padding(.horizontal)
            .padding(.top, 8)
            }
            }
            if model.selectedBoardSet != nil {
                // Use mode's top, Grid 3 style: a slim menu row where the clock was,
                // then the message bar with the Screen's own buttons. Edit mode shows
                // the same bar so those buttons can be chosen in place.
                if mode == .run {
                    HStack {
                        workspaceMenu(boardSetId: boardSetId)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal)
                    if model.boardMessageBarVisible {
                    HStack(alignment: .center, spacing: 8) {
                    ScreensMessageBar(
                        model: model,
                        tokens: boardSentenceTokens,
                        showSpeakControl: false,
                        onDelete: { index in
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                                model.removeMessagePart(at: index)
                            }
                            activeSentenceAnimation = nil
                        },
                        onSpeak: { model.speakBoardMessage(boardSetId: boardSetId) },
                        animationNamespace: sentenceAnimationNamespace,
                        animatedTokenId: activeSentenceAnimation?.tokenId,
                        showsPageNavigation: (model.selectedBoardSet?.boardIds.count ?? 0) > 1,
                        canGoBack: model.canNavigateBoardBack,
                        atStartPage: model.isOnStartPage,
                        onBack: { Task { await model.navigateBoardBack() } },
                        onStartPage: { Task { await model.navigateBoardHome() } },
                        onSpeakPart: { model.speakMessagePart(at: $0) }
                    )
                    messageControls(boardSetId: boardSetId, editing: false)
                    }
                    .padding(.horizontal)
                    }
                } else if model.boardMessageBarVisible {
                    HStack(alignment: .center, spacing: 8) {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(Color(.secondarySystemFill))
                            .frame(maxWidth: .infinity)
                            .frame(height: horizontalSizeClass == .regular ? 120 : 56)
                            .accessibilityHidden(true)
                        messageControls(boardSetId: boardSetId, editing: true)
                    }
                    .padding(.horizontal)
                }

                boardPreview(isEditMode: mode == .edit)
                    .padding(.horizontal)
            }

        }
        .animation(.easeInOut(duration: 0.2), value: model.selectedBoardId)
        .task(id: boardPredictionTaskId) {
            await model.refreshBoardPredictions(context: boardSentenceText)
        }
        .onDisappear { model.stopBoardPredictions() }
        .onChange(of: model.selectedBoardId) { _, _ in
            guard mode == .run else { return }
            UIAccessibility.post(
                notification: .screenChanged,
                argument: model.selectedBoard?.name ?? NSLocalizedString("boardset.untitled_board", comment: "")
            )
        }
        .toolbar(.hidden, for: .navigationBar)
        .statusBarHidden(true)
    }

    private func pageChip(id: String, isStartPage: Bool, isSelected: Bool) -> some View {
        HStack(spacing: 5) {
            if isStartPage {
                Image(systemName: "house.fill").font(.caption2)
            }
            Text(model.boardDisplayName(id: id))
                .font(.subheadline)
                .fontWeight(isSelected ? .bold : .regular)
            if isSelected {
                Image(systemName: "chevron.down").font(.caption2.weight(.semibold)).accessibilityHidden(true)
            }
        }
        .foregroundStyle(Color.primary)
        .padding(.horizontal, 12)
        .frame(minHeight: 36)
        .background(isSelected ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground))
        .clipShape(Capsule())
    }

    /// Actions for the current Page, opened from its chip.
    @ViewBuilder
    private func pageActions(isStartPage: Bool) -> some View {
        Button {
            managementName = model.selectedBoard?.name ?? ""
            managementSheet = .renameBoard
        } label: {
            Label("boardset.rename_board", systemImage: "pencil")
        }
        Button {
            managementRows = max(1, Int(model.selectedBoard?.grid?.rows ?? 4))
            managementColumns = max(1, Int(model.selectedBoard?.grid?.columns ?? 4))
            managementSheet = .resizeBoard
        } label: {
            Label("boardset.resize_board", systemImage: "square.grid.3x3")
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
        Button {
            backgroundFit = model.boardBackgroundImageFit
            showBackgroundEditor = true
        } label: { Label("boardset.background_image", systemImage: "photo") }
        Button {
            settingsTarget = .page
        } label: { Label("board_settings.page_title", systemImage: "slider.horizontal.3") }
        if !isStartPage {
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
    }

    /// Edit mode's Pages, for quick switching, ending with Add Page. The current
    /// Page's chip opens its actions.
    private func pageStrip(boardSetId: String) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let selectedSet = model.selectedBoardSet {
                    ForEach(selectedSet.boardIds, id: \.self) { id in
                        if model.selectedBoardId == id {
                            Menu { pageActions(isStartPage: id == selectedSet.rootBoardId) } label: {
                                pageChip(id: id, isStartPage: id == selectedSet.rootBoardId, isSelected: true)
                            }
                            .accessibilityAddTraits(.isSelected)
                        } else {
                            Button(action: {
                                Task { await model.selectBoard(id: id, resetStack: true) }
                            }) {
                                pageChip(id: id, isStartPage: id == selectedSet.rootBoardId, isSelected: false)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                Button(action: {
                    addBoardKeyboardLayout = model.selectedBoardKeyboardLayout ?? "qwerty"
                    showAddBoardSheet = true
                }) {
                    Label("boardset.add_board", systemImage: "plus")
                        .font(.subheadline)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 36)
                        .overlay(Capsule().strokeBorder(Color(.separator), style: StrokeStyle(lineWidth: 1, dash: [4])))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }
            .padding(.horizontal, 8)
        }
    }

    /// The Screen's 1–4 buttons beside the message bar. They always fill the same
    /// 2×2 area, so fewer buttons are bigger: one fills it, two stack, three give
    /// the first a full-height column. In edit mode each slot is a menu that
    /// changes or removes its action, and a + slot adds one.
    @ViewBuilder
    private func messageControls(boardSetId: String, editing: Bool) -> some View {
        let actions = model.boardMessageBarActions
        let slots: [String?] = actions.map { Optional($0) } + (editing && actions.count < 4 ? [nil] : [])
        let gap: CGFloat = 8
        let full = controlSize * 2 + gap
        let half = controlSize
        let slot = { (index: Int, size: CGSize) in
            messageSlot(slots[index], index: index, actions: actions, editing: editing, boardSetId: boardSetId, size: size)
        }
        switch slots.count {
        case 0:
            EmptyView()
        case 1:
            slot(0, CGSize(width: full, height: full))
        case 2:
            VStack(spacing: gap) {
                slot(0, CGSize(width: full, height: half))
                slot(1, CGSize(width: full, height: half))
            }
        case 3:
            HStack(spacing: gap) {
                slot(0, CGSize(width: half, height: full))
                VStack(spacing: gap) {
                    slot(1, CGSize(width: half, height: half))
                    slot(2, CGSize(width: half, height: half))
                }
            }
        default:
            Grid(horizontalSpacing: gap, verticalSpacing: gap) {
                GridRow {
                    slot(0, CGSize(width: half, height: half))
                    slot(1, CGSize(width: half, height: half))
                }
                GridRow {
                    slot(2, CGSize(width: half, height: half))
                    slot(3, CGSize(width: half, height: half))
                }
            }
        }
    }

    @ViewBuilder
    private func messageSlot(_ action: String?, index: Int, actions: [String], editing: Bool, boardSetId: String, size: CGSize) -> some View {
        if let action {
            if editing {
                messageActionEditor(index: index, action: action, actions: actions, size: size)
            } else {
                messageActionButton(action, boardSetId: boardSetId, size: size)
            }
        } else {
            addMessageActionMenu(actions: actions, size: size)
        }
    }

    private static let messageBarActionChoices = ["Speak", "Hold", "DeleteLast", "Clear"]

    private func messageActionTitle(_ action: String) -> LocalizedStringKey {
        switch action {
        case "Speak": "playback.play"
        case "Hold": "screens.message_bar.hold"
        case "DeleteLast": "screens.message_bar.delete_last"
        default: "screens.message_bar.clear_all"
        }
    }

    private func messageActionIcon(_ action: String) -> String {
        switch action {
        case "Speak": "play.fill"
        case "Hold": "bookmark"
        case "DeleteLast": "delete.left"
        default: "trash"
        }
    }

    @ViewBuilder
    private func messageActionButton(_ action: String, boardSetId: String, size: CGSize) -> some View {
        let hasMessage = !model.messageParts.isEmpty
        let scanHidden = model.scanningEnabled && !model.scanPlaybackAreaEnabled
        switch action {
        case "Speak":
            let speaking = ["Playing", "Preparing", "Paused"].contains(model.playback.name)
            controlButton(speaking ? "playback.stop" : "playback.play", systemImage: speaking ? "stop.fill" : "play.fill", prominent: true, size: size) {
                if speaking { model.stopSpeech() } else { model.speakBoardMessage(boardSetId: boardSetId) }
            }
            .disabled(!speaking && !hasMessage)
            .accessibilityHidden(scanHidden)
        case "Hold":
            let isHolding = model.heldMessageText?.isEmpty == false
            controlButton(isHolding ? "typing.held_message.swap" : "screens.message_bar.hold", systemImage: isHolding ? "bookmark.fill" : "bookmark", size: size) {
                withAnimation(.easeOut(duration: 0.2)) { model.toggleHoldThatThought() }
            }
            .disabled(!isHolding && !hasMessage)
            .accessibilityHint(Text(LocalizedStringKey(isHolding ? "accessibility.playback.restore_thought_hint" : "")))
            .accessibilityHidden(scanHidden)
        case "DeleteLast":
            controlButton("screens.message_bar.delete_last", systemImage: "delete.left", size: size) {
                withAnimation(.easeOut(duration: 0.2)) { model.removeLastMessagePart() }
                activeSentenceAnimation = nil
            }
            .disabled(!hasMessage)
            .accessibilityHidden(scanHidden)
        default:
            controlButton("screens.message_bar.clear_all", systemImage: "trash", size: size) {
                withAnimation(.easeOut(duration: 0.2)) { model.clearMessage() }
                activeSentenceAnimation = nil
            }
            .disabled(!hasMessage)
            .accessibilityHidden(scanHidden)
        }
    }

    /// One button slot in edit mode: pick what it does, or remove it (one always stays).
    private func messageActionEditor(index: Int, action: String, actions: [String], size: CGSize) -> some View {
        Menu {
            ForEach(Self.messageBarActionChoices, id: \.self) { choice in
                Button {
                    var next = actions
                    next[index] = choice
                    Task { await model.setMessageBarActions(next) }
                } label: {
                    Label(messageActionTitle(choice), systemImage: choice == action ? "checkmark" : messageActionIcon(choice))
                }
                .disabled(choice != action && actions.contains(choice))
            }
            Divider()
            Button(role: .destructive) {
                var next = actions
                next.remove(at: index)
                Task { await model.setMessageBarActions(next) }
            } label: {
                Label("screens.message_bar.remove_button", systemImage: "minus.circle")
            }
            .disabled(actions.count <= 1)
        } label: {
            controlLabel(systemImage: messageActionIcon(action), prominent: action == "Speak", size: size)
        }
        .accessibilityLabel(Text(messageActionTitle(action)))
        .accessibilityHint(Text("screens.message_bar.edit_hint"))
    }

    private func addMessageActionMenu(actions: [String], size: CGSize) -> some View {
        Menu {
            ForEach(Self.messageBarActionChoices.filter { !actions.contains($0) }, id: \.self) { choice in
                Button {
                    Task { await model.setMessageBarActions(actions + [choice]) }
                } label: {
                    Label(messageActionTitle(choice), systemImage: messageActionIcon(choice))
                }
            }
        } label: {
            Image(systemName: "plus")
                .font(.system(size: iconSize(for: size), weight: .semibold))
                .frame(width: size.width, height: size.height)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(Color(.separator), style: StrokeStyle(lineWidth: 1, dash: [4]))
                )
        }
        .accessibilityLabel(Text("screens.message_bar.add_button"))
    }

    /// Icons grow with their slot, but a wide or tall slot keeps a square-sized icon.
    private func iconSize(for size: CGSize) -> CGFloat { min(size.width, size.height) * 0.4 }

    private func controlLabel(systemImage: String, prominent: Bool, size: CGSize) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: iconSize(for: size), weight: .semibold))
            .foregroundStyle(prominent ? Color.white : Color.accentColor)
            .frame(width: size.width, height: size.height)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(prominent ? Color.accentColor : Color(.secondarySystemBackground))
            )
    }

    private var controlSize: CGFloat { horizontalSizeClass == .regular ? 56 : 48 }

    private func controlButton(
        _ key: LocalizedStringKey,
        systemImage: String,
        prominent: Bool = false,
        size: CGSize,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            controlLabel(systemImage: systemImage, prominent: prominent, size: size)
        }
        .buttonStyle(CommunicationButtonStyle(touchDown: model.selectOnTouchDown))
        .accessibilityLabel(Text(key))
    }

    /// The one corner menu in use mode. It holds everything except the board and
    /// the message bar, so the board can fill the screen.
    private func workspaceMenu(boardSetId: String) -> some View {
        Menu {
            Button {
                if model.canEditSelectedBoardSet {
                    Task { await requestEnterEditing(boardSetId: boardSetId) }
                } else if let set = model.selectedBoardSet {
                    Task { await beginScreenLockToggle(set) }
                }
            } label: { Label("board_sets.library.edit", systemImage: "pencil") }
            Button { showHiddenButtons.toggle() } label: {
                Label(
                    LocalizedStringKey(showHiddenButtons ? "board_workspace.hide_hidden" : "board_workspace.show_hidden"),
                    systemImage: showHiddenButtons ? "eye.slash" : "eye"
                )
            }
            Button { printScreen(id: boardSetId) } label: {
                Label("boardset.print_pdf", systemImage: "printer")
            }
            .disabled(exportingPdf)
            Divider()
            Button { route = .library } label: {
                Label("board_workspace.exit_library", systemImage: "square.grid.2x2")
            }
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { model.boardModeEnabled = false }
            } label: { Label("toolbar.switch_phrase_mode", systemImage: "text.bubble") }
            Button(action: onShowSettings) {
                Label("toolbar.settings_panel", systemImage: "slider.horizontal.3")
            }
        } label: {
            Label("board_workspace.menu", systemImage: "line.3.horizontal")
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 8)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(Text("board_workspace.menu"))
        .accessibilityHidden(model.scanningEnabled && !model.scanTopBarEnabled)
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
                            let occupied = model.cellAt(row: field.row, col: field.column) != nil
                            let x = CGFloat(field.column) * (cellWidth + gridGap)
                            let y = CGFloat(field.row) * (cellHeight + gridGap)
                            let spanWidth = (cellWidth * CGFloat(field.columnSpan)) + (gridGap * CGFloat(field.columnSpan - 1))
                            let spanHeight = (cellHeight * CGFloat(field.rowSpan)) + (gridGap * CGFloat(field.rowSpan - 1))
                            // Use mode leaves empty cells blank. They are not buttons.
                            if occupied || isEditMode {
                                boardCellButton(
                                    row: field.row,
                                    col: field.column,
                                    isEditMode: isEditMode,
                                    width: max(0, spanWidth),
                                    height: max(0, spanHeight),
                                    rowSpan: field.rowSpan,
                                    columnSpan: field.columnSpan,
                                    metrics: BoardGridMetrics(
                                        cellWidth: cellWidth,
                                        cellHeight: cellHeight,
                                        gap: gridGap,
                                        rows: rows,
                                        columns: cols
                                    )
                                )
                                .offset(fieldDragOffset(for: field.buttonId, fallback: CGSize(width: x, height: y)))
                            } else {
                                Color.clear
                                    .frame(width: max(0, spanWidth), height: max(0, spanHeight))
                                    .offset(x: x, y: y)
                                    .accessibilityHidden(true)
                                    .allowsHitTesting(false)
                            }
                        }
                        if isEditMode {
                            fieldEditChrome(
                                cellWidth: cellWidth,
                                cellHeight: cellHeight,
                                gap: gridGap,
                                rows: rows,
                                columns: cols
                            )
                        }
                    }
                    .coordinateSpace(name: "boardGrid")
                    .frame(width: contentWidth, height: contentHeight, alignment: .topLeading)
                    .padding(gridInset)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background {
                    if !model.highContrastMode { PageBackgroundView(source: model.boardBackgroundImageUrl, fit: model.boardBackgroundImageFit) }
                }
                .background(
                    model.highContrastMode
                        ? Color(.systemBackground)
                        : colorFromHex(board.backgroundColor, fallback: Color(.secondarySystemBackground))
                )
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .scrollDisabled(model.selectOnTouchDown && !isEditMode)
                .simultaneousGesture(DragGesture(minimumDistance: 24).onEnded { value in
                    guard !isEditMode, abs(value.translation.width) >= 64,
                          abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                    Task { await model.swipePage(by: value.translation.width < 0 ? 1 : -1) }
                })
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .id(board.id)
            .transition(.opacity)
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 24)
        }
    }

    private func boardCellButton(row: Int, col: Int, isEditMode: Bool, width: CGFloat, height: CGFloat, rowSpan: Int = 1, columnSpan: Int = 1, metrics: BoardGridMetrics = BoardGridMetrics()) -> some View {
        let cell = model.cellAt(row: row, col: col)
        let cornerRadius = boardCellCornerRadius(cell, width: width, height: height)
        let isLinked = trimmed(cell?.linkedBoardId) != nil
        let sourceCellId = "\(row):\(col)"
        let anchor = BoardCellAnchor(row: row, column: col)
        let isSelected = isEditMode && selectedFieldAnchor == anchor && cell != nil
        let hiddenInRunMode = !model.boardButtonIsVisible(
            hidden: cell?.hidden == true,
            isEditMode: isEditMode,
            showHiddenButtons: showHiddenButtons
        )

        return ZStack(alignment: .topTrailing) {
            Button {
                if suppressNextCellTap {
                    suppressNextCellTap = false
                    return
                }
                if isEditMode && model.canEditSelectedBoardSet {
                    if cell == nil {
                        openCellEditor(row: row, col: col, existing: nil)
                    } else if selectedFieldAnchor == anchor {
                        openCellEditor(row: row, col: col, existing: cell)
                    } else {
                        selectField(anchor)
                    }
                } else {
                    guard model.holdToSelectMillis <= 0 else { return }
                    if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
                    activateBoardCell(cell, row: row, col: col, sourceCellId: sourceCellId)
                }
            } label: {
                // One view in both states, so starting the fly-in does not rebuild
                // the cell (and reload its symbol image).
                let flyingTokenId = activeSentenceAnimation.flatMap { $0.sourceCellId == sourceCellId ? $0.tokenId : nil }
                boardCellContent(row: row, col: col, cell: cell, isLinked: isLinked, isEditMode: isEditMode, width: width, height: height, rowSpan: rowSpan, columnSpan: columnSpan)
                    .matchedGeometryEffect(id: flyingTokenId ?? "cell-\(sourceCellId)", in: sentenceAnimationNamespace, isSource: true)
                    .zIndex(flyingTokenId == nil ? 0 : 3)
            }
            .buttonStyle(CommunicationButtonStyle(touchDown: model.selectOnTouchDown && model.holdToSelectMillis <= 0 && !isEditMode))
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
            .accessibilityLabel(Text(cell == nil
                ? NSLocalizedString("boardset.cell.add", comment: "")
                : boardCellDisplayLabel(cell)))
            .accessibilityHint(Text(cell == nil ? "" : boardCellAccessibilityHint(cell)))
            .accessibilitySortPriority(Double(max(0, 10_000 - (row * 100 + col))))
            .accessibilityAddTraits(isSelected ? .isSelected : [])
            .accessibilityAction(.default) {
                if isEditMode && model.canEditSelectedBoardSet {
                    openCellEditor(row: row, col: col, existing: cell)
                } else if cell != nil {
                    activateBoardCell(cell, row: row, col: col, sourceCellId: sourceCellId)
                }
            }
            .modifier(FieldEditActions(
                enabled: isEditMode && cell != nil && model.canEditSelectedBoardSet,
                moveUp: { Task { await moveField(from: anchor, directionRow: -1, directionColumn: 0) } },
                moveDown: { Task { await moveField(from: anchor, directionRow: 1, directionColumn: 0) } },
                moveLeft: { Task { await moveField(from: anchor, directionRow: 0, directionColumn: -1) } },
                moveRight: { Task { await moveField(from: anchor, directionRow: 0, directionColumn: 1) } },
                wider: { Task { await resizeField(anchor, deltaRows: 0, deltaColumns: 1) } },
                narrower: { Task { await resizeField(anchor, deltaRows: 0, deltaColumns: -1) } },
                taller: { Task { await resizeField(anchor, deltaRows: 1, deltaColumns: 0) } },
                shorter: { Task { await resizeField(anchor, deltaRows: -1, deltaColumns: 0) } }
            ))
            .simultaneousGesture(editMoveGesture(anchor: anchor, cell: cell, isEditMode: isEditMode, metrics: metrics))

            if isSelected, model.canEditSelectedBoardSet {
                Button(role: .destructive) {
                    selectedFieldAnchor = nil
                    Task { await model.clearSelectedBoardCell(row: row, col: col) }
                } label: {
                    Image(systemName: "trash.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.red)
                        .background(Color(.systemBackground), in: Circle())
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("boardset.cell.clear"))
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
                    .accessibilityLabel(Text(isHomeLink ? NSLocalizedString("boardset.cell.action.home", comment: "") : String(format: NSLocalizedString("board_cell_opens_board", comment: ""), model.boardDisplayName(id: linkedBoardId))))
            }
        }
        .opacity(hiddenInRunMode ? 0 : (cell?.hidden == true && isEditMode ? 0.5 : 1))
        .allowsHitTesting(!hiddenInRunMode)
        .accessibilityHidden(hiddenInRunMode || (model.scanningEnabled && !model.scanPhraseGridEnabled))
    }

    private func boardCellContent(row: Int, col: Int, cell: BoardCellInfo?, isLinked: Bool, isEditMode: Bool, width: CGFloat, height: CGFloat, rowSpan: Int = 1, columnSpan: Int = 1) -> some View {
        // Labels follow the height of one grid cell, so they grow with the grid
        // instead of staying at a fixed point size; merged fields scale up further.
        let unitHeight = height / CGFloat(max(1, rowSpan))
        let labelSize = min(max(unitHeight * 0.2, 15), 40) * model.boardFieldFontScale(rowSpan: rowSpan, columnSpan: columnSpan)
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
                        .font(.largeTitle)
                        .foregroundStyle(Color.accentColor)
                        .accessibilityHidden(true)
                }
                .frame(width: width, height: height)
            )
        }
        let isHighlighted = model.highlightedButtonId != nil && model.highlightedButtonId == cell?.buttonId
        return AnyView(
            VStack(alignment: .center, spacing: 4) {
            if model.boardLabelAtTop && model.boardShowLabels {
                boardCellLabel(cell, size: labelSize)
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
                boardCellLabel(cell, size: labelSize)
            }

            if let linkedBoardId = trimmed(cell?.linkedBoardId) {
                Label(model.boardDisplayName(id: linkedBoardId), systemImage: "arrowshape.turn.up.right")
                    .font(.caption2)
                    .foregroundStyle(contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.secondary))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity)
            } else if let vocalization = trimmed(cell?.vocalization), vocalization != trimmed(cell?.label) {
                Text(vocalization)
                    .font(.caption2)
                    .foregroundStyle(contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.secondary))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(8)
        .frame(width: width, height: height)
        .background(
            RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height))
                .fill(model.highContrastMode ? Color(.systemBackground) : colorFromHex(cell?.resolvedBackgroundColor, fallback: Color(.tertiarySystemBackground)))
        )
        .overlay(
            RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height))
                .stroke(model.highContrastMode ? Color.primary : colorFromHex(cell?.borderColor, fallback: isLinked ? .accentColor : Color(.separator)),
                    lineWidth: model.highContrastMode ? 2 : (isLinked ? 1.5 : 1))
        )
        .overlay {
            if isHighlighted {
                SelectionHighlightOutline(shape: RoundedRectangle(cornerRadius: boardCellCornerRadius(cell, width: width, height: height)))
            }
        }
        )
    }

    private func boardCellCornerRadius(_ cell: BoardCellInfo?, width: CGFloat, height: CGFloat) -> CGFloat {
        switch cell?.shape {
        // Rounded is the default shape (Android 945427e9), at 16pt like Typing tiles.
        case "rounded", "speech", "thought": return 16
        case "pill": return min(width, height) / 2
        default: return 0
        }
    }

    @ViewBuilder
    private func boardCellLabel(_ cell: BoardCellInfo?, size: CGFloat) -> some View {
        let text = boardCellDisplayLabel(cell)
        if !text.isEmpty {
            Text(text)
                .font(.system(size: size, weight: .semibold))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.5)
                .foregroundStyle(model.highContrastMode ? Color.primary : contrastingColor(for: cell?.resolvedBackgroundColor, fallback: Color.primary))
                .frame(maxWidth: .infinity)
        }
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

    private var speechImportSheet: some View {
        let pages = model.previewSpeech(name: speechName, text: speechText, paragraphsPerPage: speechParagraphsPerPage)
        return NavigationStack {
            Form {
                Section {
                    Text("speech_import.help")
                    TextField(NSLocalizedString("speech_import.name", comment: ""), text: $speechName)
                    TextEditor(text: $speechText)
                        .frame(minHeight: 180)
                        .accessibilityLabel(Text("speech_import.text"))
                    Stepper(value: $speechParagraphsPerPage, in: 1...8) {
                        Text(String(format: NSLocalizedString("speech_import.count", comment: ""), speechParagraphsPerPage))
                    }
                }
                Section {
                    ForEach(Array(pages.enumerated()), id: \.offset) { index, page in
                        VStack(alignment: .leading, spacing: 12) {
                            Text("\(index + 1). \(page.name)").font(.headline)
                            ForEach(Array(page.paragraphs.enumerated()), id: \.offset) { _, paragraph in
                                Text(paragraph)
                            }
                        }
                    }
                } header: {
                    Text(String(format: NSLocalizedString("speech_import.preview", comment: ""), pages.count))
                }
            }
            .disabled(model.isCreatingBoardSet)
            .navigationTitle(Text("speech_import.title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { showSpeechImport = false }
                        .disabled(model.isCreatingBoardSet)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.isCreatingBoardSet {
                        ProgressView()
                    } else {
                        Button("speech_import.create") {
                            speechImportFailed = false
                            Task {
                                if let id = await model.importSpeech(
                                    name: speechName, text: speechText, paragraphsPerPage: speechParagraphsPerPage
                                ) {
                                    showSpeechImport = false
                                    speechName = ""
                                    speechText = ""
                                    route = .workspace(boardSetId: id, mode: .run)
                                } else {
                                    speechImportFailed = true
                                }
                            }
                        }
                        .disabled(speechName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || pages.isEmpty)
                    }
                }
            }
            .alert("speech_import.error", isPresented: $speechImportFailed) {
                Button("common.ok", role: .cancel) { }
            }
            .interactiveDismissDisabled(model.isCreatingBoardSet)
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
                            if createBoardsetKind == .blank {
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

                Section {
                    Toggle("boardset.cell.hidden", isOn: $editingHidden)
                } footer: {
                    Text("boardset.cell.hidden_footer")
                }

                if model.ttsEngine.contains("AZURE") {
                    Section {
                        Toggle("boardset.cell.math", isOn: $editingMathMode)
                    } footer: {
                        Text("boardset.cell.math_footer")
                    }
                }

                if model.canEditSelectedBoardSet, editingCellHasButton {
                    Section {
                        HStack(spacing: 12) {
                            fieldNudgeButton("boardset.cell.move.up", systemImage: "arrow.up") {
                                Task { await moveField(from: BoardCellAnchor(row: editingRow, column: editingCol), directionRow: -1, directionColumn: 0, fromEditor: true) }
                            }
                            fieldNudgeButton("boardset.cell.move.down", systemImage: "arrow.down") {
                                Task { await moveField(from: BoardCellAnchor(row: editingRow, column: editingCol), directionRow: 1, directionColumn: 0, fromEditor: true) }
                            }
                            fieldNudgeButton("boardset.cell.move.left", systemImage: "arrow.left") {
                                Task { await moveField(from: BoardCellAnchor(row: editingRow, column: editingCol), directionRow: 0, directionColumn: -1, fromEditor: true) }
                            }
                            fieldNudgeButton("boardset.cell.move.right", systemImage: "arrow.right") {
                                Task { await moveField(from: BoardCellAnchor(row: editingRow, column: editingCol), directionRow: 0, directionColumn: 1, fromEditor: true) }
                            }
                        }
                        .frame(maxWidth: .infinity)
                    } header: {
                        Text("boardset.cell.move")
                    } footer: {
                        Text("boardset.cell.move.footer")
                    }

                    Section {
                        spanAdjustRow(
                            title: "boardset.cell.size.width",
                            value: currentEditingSpan.columns,
                            decreaseKey: "boardset.cell.size.narrower",
                            increaseKey: "boardset.cell.size.wider",
                            canDecrease: spanIsAllowed(rows: currentEditingSpan.rows, columns: currentEditingSpan.columns - 1),
                            canIncrease: spanIsAllowed(rows: currentEditingSpan.rows, columns: currentEditingSpan.columns + 1),
                            decrease: { Task { await resizeField(BoardCellAnchor(row: editingRow, column: editingCol), deltaRows: 0, deltaColumns: -1, fromEditor: true) } },
                            increase: { Task { await resizeField(BoardCellAnchor(row: editingRow, column: editingCol), deltaRows: 0, deltaColumns: 1, fromEditor: true) } }
                        )
                        spanAdjustRow(
                            title: "boardset.cell.size.height",
                            value: currentEditingSpan.rows,
                            decreaseKey: "boardset.cell.size.shorter",
                            increaseKey: "boardset.cell.size.taller",
                            canDecrease: spanIsAllowed(rows: currentEditingSpan.rows - 1, columns: currentEditingSpan.columns),
                            canIncrease: spanIsAllowed(rows: currentEditingSpan.rows + 1, columns: currentEditingSpan.columns),
                            decrease: { Task { await resizeField(BoardCellAnchor(row: editingRow, column: editingCol), deltaRows: -1, deltaColumns: 0, fromEditor: true) } },
                            increase: { Task { await resizeField(BoardCellAnchor(row: editingRow, column: editingCol), deltaRows: 1, deltaColumns: 0, fromEditor: true) } }
                        )
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
                    }
                }

                Section {
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
                                    .accessibilityLabel(Text("boardset.cell.action.move_up"))
                                    Button {
                                        moveEditingAction(at: index, by: 1)
                                    } label: {
                                        Image(systemName: "arrow.down")
                                    }
                                    .disabled(index == editingActions.count - 1)
                                    .accessibilityLabel(Text("boardset.cell.action.move_down"))
                                }
                            }
                        }
                } header: {
                    Text("boardset.cell.special_actions")
                } footer: {
                    Text("boardset.cell.special_actions_footer")
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
                                Text(option.localizedTitle).tag(option)
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
                                                Text(CellSymbolPackageFilter.title(for: symbol.source))
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

                Section {
                    Picker("boardset.cell.behavior", selection: $editingOpensPage) {
                        Text("boardset.cell.behavior.speak").tag(false)
                        Text("boardset.cell.behavior.open_page").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .disabled(linkableBoardIds.isEmpty)
                    if editingOpensPage {
                        Picker("boardset.cell.linked_board", selection: $editingLinkedBoardId) {
                            Text("boardset.cell.choose_page").tag("")
                            ForEach(linkableBoardIds, id: \.self) { boardId in
                                Text(model.boardDisplayName(id: boardId)).tag(boardId)
                            }
                        }
                    }
                } header: {
                    Text("boardset.cell.behavior")
                }

                Section("boardset.cell.shape") {
                    Picker("boardset.cell.shape", selection: $editingShape) {
                        Text("boardset.cell.shape.rounded").tag("rounded")
                        Text("boardset.cell.shape.square").tag("square")
                        Text("boardset.cell.shape.pill").tag("pill")
                        Text("boardset.cell.shape.speech").tag("speech")
                        Text("boardset.cell.shape.thought").tag("thought")
                    }
                }

                Section("boardset.cell.language") {
                    Picker("boardset.cell.language", selection: $editingLanguage) {
                        Text("boardset.cell.language.default").tag("")
                        ForEach(cellLanguageChoices, id: \.tag) { choice in
                            Text(choice.title).tag(choice.tag)
                        }
                    }
                }

                Section {
                    if isRecordingCell {
                        Button("common.stop", role: .destructive) {
                            Task { await stopCellRecording() }
                        }
                    } else {
                        Button(LocalizedStringKey(editingRecordingPath == nil ? "phrase.record" : "phrase.record.replace")) {
                            AVAudioApplication.requestRecordPermission { granted in
                                Task { @MainActor in
                                    guard granted else { cellMicDenied = true; return }
                                    cellMicDenied = false
                                    _ = try? await cellRecorder.startRecording()
                                    isRecordingCell = true
                                }
                            }
                        }
                    }
                    if let path = editingRecordingPath {
                        Button("phrase.play_recording") { cellRecorder.play(url: URL(fileURLWithPath: path)) }
                        Button("common.clear", role: .destructive) {
                            editingRecordingPath = nil
                            editingClearsRecording = true
                        }
                    }
                    if cellMicDenied {
                        Text("phrase.mic_permission_denied")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text("phrase.recording")
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
                        Text("boardset.cell.word_type.pronoun").tag("pronoun")
                        Text("boardset.cell.word_type.verb").tag("verb")
                        Text("boardset.cell.word_type.descriptor").tag("descriptor")
                        Text("boardset.cell.word_type.noun").tag("noun")
                        Text("boardset.cell.word_type.social").tag("social")
                        Text("boardset.cell.word_type.other").tag("other")
                    }
                }
            }
            .navigationTitle(Text("boardset.cell.edit_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarBackground(Color(.systemGroupedBackground), for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") {
                        if isRecordingCell { Task { await stopCellRecording() } }
                        showEditCellSheet = false
                    }
                }
                if editingCellHasButton {
                    ToolbarItem(placement: .bottomBar) {
                        Button("boardset.cell.clear", role: .destructive) {
                            Task {
                                await model.clearSelectedBoardCell(row: editingRow, col: editingCol)
                                showEditCellSheet = false
                            }
                        }
                        .disabled(!model.canEditSelectedBoardSet)
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
                                linkedBoardId: editingOpensPage ? editingLinkedBoardId : "",
                                imageUrl: editingSelectedSymbolUrl,
                                clearImage: shouldClearEditingSymbol,
                                actions: actions,
                                wordType: editingWordType,
                                hidden: editingHidden,
                                shape: editingShape,
                                language: editingLanguage,
                                mathMode: editingMathMode,
                                recordingPath: editingClearsRecording ? nil : editingRecordingPath,
                                clearRecording: editingClearsRecording
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
                    .disabled(!model.canEditSelectedBoardSet || trimmed(editingLabel) == nil || (editingOpensPage && trimmed(editingLinkedBoardId) == nil))
                }
            }
        }
    }

    private var cellLanguageChoices: [(tag: String, title: String)] {
        var choices: [(tag: String, title: String)] = []
        let primary = model.primaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        if !primary.isEmpty {
            choices.append((primary, String(format: NSLocalizedString("boardset.cell.language.primary", comment: ""), primary)))
        }
        let secondary = model.secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        if !secondary.isEmpty, secondary != primary {
            choices.append((secondary, String(format: NSLocalizedString("boardset.cell.language.secondary", comment: ""), secondary)))
        }
        let current = editingLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        if !current.isEmpty, !choices.contains(where: { $0.tag == current }) {
            choices.append((current, current))
        }
        return choices
    }

    private func stopCellRecording() async {
        if let url = try? await cellRecorder.stopRecording() {
            editingRecordingPath = url.path
            editingClearsRecording = false
        }
        isRecordingCell = false
    }

    private var linkableBoardIds: [String] {
        guard let set = model.selectedBoardSet else { return [] }
        return set.boardIds.filter { $0 != model.selectedBoardId }
    }

    /// The shared Message, as shown in the Screens message bar: each Button with its
    /// label and symbol, wherever it came from. Token ids are part indices, so deleting
    /// a token removes the matching Message part.
    private var boardSentenceTokens: [SentencePhraseToken] {
        model.messageParts.enumerated().map { index, part in
            let text = part.displayText.trimmingCharacters(in: .whitespacesAndNewlines)
            let source = part.source as? Shared.MessagePartSourceScreenButton
            let looks = source.flatMap { model.screenButtonLooks["\($0.pageId)|\($0.buttonId)"] }
            return SentencePhraseToken(
                id: "part-\(index)",
                phraseId: source?.buttonId ?? "",
                text: text,
                title: looks.map { $0.label.isEmpty ? text : $0.label } ?? text,
                imageUrl: looks?.imageUrl
            )
        }
    }

    private var boardSentenceText: String { model.input }

    private var boardPredictionTaskId: String {
        let predictors = model.boardCells
            .filter { $0.actions.contains(where: { $0.lowercased().hasPrefix(":prediction") }) }
            .map(\.buttonId)
            .joined(separator: ",")
        return "\(model.selectedBoardId ?? "")|\(predictors)|\(boardSentenceText)"
    }

    private func activateBoardCell(_ cell: BoardCellInfo?, row: Int, col: Int, sourceCellId: String) {
        guard let cell else { return }
        if cell.actions.isEmpty, trimmed(cell.linkedBoardId) != nil {
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
            // A Button's own sound replaces its spoken text, so it is said once.
            if shouldSpeak { playCellSound(cell) }
            if shouldAdd {
                appendCellToSentenceIfNeeded(cell, sourceCellId: sourceCellId)
            }
            Task {
                if shouldSpeak && trimmed(cell.soundPath) == nil && trimmed(cell.soundDataUrl) == nil {
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
                model.clearMessage()
            case ":speak":
                if let boardSetId = model.selectedBoardSetId {
                    model.speakBoardMessage(boardSetId: boardSetId)
                }
            case ":home":
                Task { await model.navigateBoardHome() }
            case ":pause":
                model.pauseSpeech()
            case ":resume":
                model.resumeSpeech()
            case ":stop":
                model.stopSpeech()
            case ":hold-message":
                model.toggleHoldThatThought()
            case ":secondary-language":
                model.boardStatusMessage = NSLocalizedString("boardset.status.unsupported_action", comment: "")
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
            playCellSound(cell)
            Task { await model.activateBoardSelectionHighlight(buttonId: cell.buttonId) }
            Task { await model.applyBoardReturnBehavior() }
        }
    }

    private func appendCellToSentenceIfNeeded(_ cell: BoardCellInfo?, textOverride: String? = nil, sourceCellId: String? = nil) {
        guard let cell else { return }
        let text = textOverride ?? trimmed(cell.vocalization) ?? trimmed(cell.label)
        guard let text, !text.isEmpty else { return }

        // The new token first renders on top of the tapped cell, then flies to its
        // place in the message bar on the very next frame.
        var placement = Transaction()
        placement.disablesAnimations = true
        var tokenId = ""
        withTransaction(placement) {
            model.appendScreenPart(text: text, buttonId: cell.buttonId, languageTag: cell.language, mathMode: cell.mathMode)
            // Read after appending: in spelling mode a letter may join the last part.
            tokenId = "part-\(model.messageParts.count - 1)"
            activeSentenceAnimation = ActiveSentenceAnimation(sourceCellId: sourceCellId ?? cell.id, tokenId: tokenId)
        }
        DispatchQueue.main.async {
            guard activeSentenceAnimation?.tokenId == tokenId else { return }
            withAnimation(.spring(response: 0.38, dampingFraction: 0.82)) {
                activeSentenceAnimation = nil
            }
        }
    }

    private func playCellSound(_ cell: BoardCellInfo) {
        if let path = trimmed(cell.soundPath) {
            model.playBoardButtonFile(path)
        } else if let sound = trimmed(cell.soundDataUrl) {
            model.playBoardButtonSound(sound)
        }
    }

    private func backspaceBoardSentence() {
        model.removeLastMessagePart()
    }

    private func predictionInsertion(sentence: String, suggestion: String) -> String {
        model.nGramPredictionInsertion(sentence: sentence, suggestion: suggestion)
    }

    private func boardCellDisplayLabel(_ cell: BoardCellInfo?) -> String {
        guard let cell else { return "" }
        if cell.actions.contains(where: { $0.lowercased() == ":prediction" || $0.lowercased() == ":predictions" }),
           let prediction = model.boardPrediction(for: cell.buttonId) {
            return prediction
        }
        return trimmed(cell.label) ?? trimmed(cell.vocalization) ?? ""
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

    private var currentEditingSpan: GridFieldSpanInfo {
        if case .custom(let span) = editingSpan { return span }
        return GridFieldSpanInfo(rows: 1, columns: 1)
    }

    private func spanIsAllowed(rows: Int, columns: Int) -> Bool {
        rows >= 1 && columns >= 1 && editingSpanOptions.contains(GridFieldSpanInfo(rows: rows, columns: columns))
    }

    private func fieldNudgeButton(_ key: LocalizedStringKey, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title2)
                .frame(minWidth: 56, minHeight: 56)
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(Text(key))
    }

    private func spanAdjustRow(
        title: LocalizedStringKey,
        value: Int,
        decreaseKey: LocalizedStringKey,
        increaseKey: LocalizedStringKey,
        canDecrease: Bool,
        canIncrease: Bool,
        decrease: @escaping () -> Void,
        increase: @escaping () -> Void
    ) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: decrease) {
                Image(systemName: "minus")
                    .frame(minWidth: 56, minHeight: 56)
            }
            .buttonStyle(.bordered)
            .disabled(!canDecrease)
            .accessibilityLabel(Text(decreaseKey))
            Text("\(value)")
                .font(.title3)
                .frame(minWidth: 32)
                .accessibilityHidden(true)
            Button(action: increase) {
                Image(systemName: "plus")
                    .frame(minWidth: 56, minHeight: 56)
            }
            .buttonStyle(.bordered)
            .disabled(!canIncrease)
            .accessibilityLabel(Text(increaseKey))
        }
    }

    private func selectField(_ anchor: BoardCellAnchor) {
        selectedFieldAnchor = anchor
        Task {
            let options = await model.availableFieldSpanOptions(row: anchor.row, col: anchor.column)
            await MainActor.run {
                guard selectedFieldAnchor == anchor else { return }
                selectedFieldSpans = options
            }
        }
    }

    private func fieldDragOffset(for buttonId: String?, fallback: CGSize) -> CGSize {
        guard let buttonId, let drag = fieldDrag, drag.buttonId == buttonId else { return fallback }
        return CGSize(width: fallback.width + drag.translation.width, height: fallback.height + drag.translation.height)
    }

    private func cellAddress(at location: CGPoint, metrics: BoardGridMetrics) -> (row: Int, column: Int)? {
        let strideX = metrics.cellWidth + metrics.gap
        let strideY = metrics.cellHeight + metrics.gap
        guard strideX > 0, strideY > 0 else { return nil }
        let column = Int(location.x / strideX)
        let row = Int(location.y / strideY)
        guard row >= 0, column >= 0, row < metrics.rows, column < metrics.columns else { return nil }
        let xInCell = location.x - CGFloat(column) * strideX
        let yInCell = location.y - CGFloat(row) * strideY
        guard xInCell <= metrics.cellWidth + 0.5, yInCell <= metrics.cellHeight + 0.5 else { return nil }
        return (row, column)
    }

    private func editMoveGesture(anchor: BoardCellAnchor, cell: BoardCellInfo?, isEditMode: Bool, metrics: BoardGridMetrics) -> some Gesture {
        let enabled = isEditMode && cell != nil && model.canEditSelectedBoardSet
        return LongPressGesture(minimumDuration: enabled ? 0.35 : 10_000)
            .sequenced(before: DragGesture(minimumDistance: 8, coordinateSpace: .named("boardGrid")))
            .onChanged { value in
                guard enabled, let cell else { return }
                if case .second(true, let drag?) = value {
                    suppressNextCellTap = true
                    let target = cellAddress(at: drag.location, metrics: metrics)
                    fieldDrag = FieldDragState(
                        buttonId: cell.buttonId,
                        fromRow: anchor.row,
                        fromColumn: anchor.column,
                        translation: drag.translation,
                        targetRow: target?.row ?? anchor.row,
                        targetColumn: target?.column ?? anchor.column
                    )
                }
            }
            .onEnded { value in
                guard enabled, let cell else { return }
                guard case .second(true, let drag?) = value else { return }
                let target = cellAddress(at: drag.location, metrics: metrics) ?? (anchor.row, anchor.column)
                let buttonId = cell.buttonId
                fieldDrag = nil
                suppressNextCellTap = true
                guard target.0 != anchor.row || target.1 != anchor.column else { return }
                Task {
                    let moved = await model.moveBoardField(
                        fromRow: anchor.row,
                        fromCol: anchor.column,
                        toRow: target.0,
                        toCol: target.1
                    )
                    if moved, let item = model.boardFieldItems.first(where: { $0.buttonId == buttonId }) {
                        selectField(BoardCellAnchor(row: item.row, column: item.column))
                    } else if !moved {
                        model.boardStatusMessage = NSLocalizedString("boardset.cell.move.blocked", comment: "")
                    }
                }
            }
    }

    private func fieldEditChrome(cellWidth: CGFloat, cellHeight: CGFloat, gap: CGFloat, rows: Int, columns: Int) -> some View {
        let contentWidth = cellWidth * CGFloat(columns) + gap * CGFloat(max(0, columns - 1))
        let contentHeight = cellHeight * CGFloat(rows) + gap * CGFloat(max(0, rows - 1))
        return ZStack(alignment: .topLeading) {
        if let drag = fieldDrag {
            let target = model.boardFieldItems.first { item in
                drag.targetRow >= item.row && drag.targetRow < item.row + item.rowSpan &&
                    drag.targetColumn >= item.column && drag.targetColumn < item.column + item.columnSpan
            }
            if let target, target.buttonId != drag.buttonId {
                let width = cellWidth * CGFloat(target.columnSpan) + gap * CGFloat(target.columnSpan - 1)
                let height = cellHeight * CGFloat(target.rowSpan) + gap * CGFloat(target.rowSpan - 1)
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.accentColor, lineWidth: 3)
                    .frame(width: max(0, width), height: max(0, height))
                    .offset(
                        x: CGFloat(target.column) * (cellWidth + gap),
                        y: CGFloat(target.row) * (cellHeight + gap)
                    )
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        if let anchor = selectedFieldAnchor,
           let field = model.boardFieldItems.first(where: { $0.row == anchor.row && $0.column == anchor.column && $0.buttonId != nil }) {
            let span = resizePreview ?? GridFieldSpanInfo(rows: field.rowSpan, columns: field.columnSpan)
            let width = cellWidth * CGFloat(span.columns) + gap * CGFloat(max(0, span.columns - 1))
            let height = cellHeight * CGFloat(span.rows) + gap * CGFloat(max(0, span.rows - 1))
            RoundedRectangle(cornerRadius: 12)
                .stroke(resizePreview == nil || resizePreviewValid ? Color.accentColor : Color.red, lineWidth: 3)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill((resizePreviewValid ? Color.accentColor : Color.red).opacity(resizePreview == nil ? 0 : 0.18))
                )
                .frame(width: max(0, width), height: max(0, height))
                .offset(x: CGFloat(field.column) * (cellWidth + gap), y: CGFloat(field.row) * (cellHeight + gap))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            resizeHandle(.end, field: field, cellWidth: cellWidth, cellHeight: cellHeight, gap: gap, rows: rows, columns: columns, span: span)
            resizeHandle(.bottom, field: field, cellWidth: cellWidth, cellHeight: cellHeight, gap: gap, rows: rows, columns: columns, span: span)
            resizeHandle(.corner, field: field, cellWidth: cellWidth, cellHeight: cellHeight, gap: gap, rows: rows, columns: columns, span: span)
        }
        }
        .frame(width: max(0, contentWidth), height: max(0, contentHeight), alignment: .topLeading)
    }

    private func resizeHandle(
        _ edge: FieldResizeEdge,
        field: BoardFieldItem,
        cellWidth: CGFloat,
        cellHeight: CGFloat,
        gap: CGFloat,
        rows: Int,
        columns: Int,
        span: GridFieldSpanInfo
    ) -> some View {
        let width = cellWidth * CGFloat(span.columns) + gap * CGFloat(max(0, span.columns - 1))
        let height = cellHeight * CGFloat(span.rows) + gap * CGFloat(max(0, span.rows - 1))
        let originX = CGFloat(field.column) * (cellWidth + gap)
        let originY = CGFloat(field.row) * (cellHeight + gap)
        let centerX: CGFloat = switch edge {
        case .bottom: originX + width / 2
        case .end, .corner: originX + width
        }
        let centerY: CGFloat = switch edge {
        case .end: originY + height / 2
        case .bottom, .corner: originY + height
        }
        let metrics = BoardGridMetrics(
            cellWidth: cellWidth,
            cellHeight: cellHeight,
            gap: gap,
            rows: rows,
            columns: columns
        )
        return Circle()
            .fill(Color.accentColor)
            .frame(width: 44, height: 44)
            .overlay {
                Image(systemName: edge == .corner ? "arrow.up.left.and.arrow.down.right" : "circle.fill")
                    .font(edge == .corner ? .body : .caption2)
                    .foregroundStyle(Color(.systemBackground))
                    .accessibilityHidden(true)
            }
            .offset(x: centerX - 22, y: centerY - 22)
            .accessibilityLabel(Text("boardset.cell.merge"))
            .accessibilityAddTraits(.isButton)
            .gesture(
                DragGesture(minimumDistance: 4, coordinateSpace: .named("boardGrid"))
                    .onChanged { value in
                        guard let address = cellAddress(at: value.location, metrics: metrics) else {
                            resizePreviewValid = false
                            return
                        }
                        let next = snappedSpan(edge: edge, field: field, row: address.row, column: address.column)
                        resizePreview = next
                        resizePreviewValid = selectedFieldSpans.contains(next) || next == GridFieldSpanInfo(rows: field.rowSpan, columns: field.columnSpan)
                    }
                    .onEnded { value in
                        let address = cellAddress(at: value.location, metrics: metrics)
                        let next = address.map { snappedSpan(edge: edge, field: field, row: $0.row, column: $0.column) }
                        resizePreview = nil
                        guard let next, selectedFieldSpans.contains(next),
                              next.rows != field.rowSpan || next.columns != field.columnSpan else {
                            if next != nil && (next?.rows != field.rowSpan || next?.columns != field.columnSpan) {
                                model.boardStatusMessage = NSLocalizedString("boardset.cell.size.blocked", comment: "")
                            }
                            return
                        }
                        Task {
                            await model.resizeSelectedBoardField(
                                row: field.row,
                                col: field.column,
                                rows: next.rows,
                                columns: next.columns
                            )
                            selectField(BoardCellAnchor(row: field.row, column: field.column))
                        }
                    }
            )
    }

    private func snappedSpan(edge: FieldResizeEdge, field: BoardFieldItem, row: Int, column: Int) -> GridFieldSpanInfo {
        GridFieldSpanInfo(
            rows: edge == .end ? field.rowSpan : max(1, row - field.row + 1),
            columns: edge == .bottom ? field.columnSpan : max(1, column - field.column + 1)
        )
    }

    private func moveField(from anchor: BoardCellAnchor, directionRow: Int, directionColumn: Int, fromEditor: Bool = false) async {
        guard let field = model.boardFieldItems.first(where: { $0.row == anchor.row && $0.column == anchor.column && $0.buttonId != nil }) else { return }
        let targetRow = anchor.row + (directionRow > 0 ? field.rowSpan : directionRow)
        let targetColumn = anchor.column + (directionColumn > 0 ? field.columnSpan : directionColumn)
        guard targetRow >= 0, targetColumn >= 0 else {
            model.boardStatusMessage = NSLocalizedString("boardset.cell.move.blocked", comment: "")
            return
        }
        let buttonId = field.buttonId
        let moved = await model.moveBoardField(fromRow: anchor.row, fromCol: anchor.column, toRow: targetRow, toCol: targetColumn)
        guard moved, let buttonId, let item = model.boardFieldItems.first(where: { $0.buttonId == buttonId }) else {
            model.boardStatusMessage = NSLocalizedString("boardset.cell.move.blocked", comment: "")
            return
        }
        let next = BoardCellAnchor(row: item.row, column: item.column)
        selectField(next)
        if fromEditor {
            editingRow = next.row
            editingCol = next.column
            await reloadEditingSpans(anchor: next)
        }
    }

    private func resizeField(_ anchor: BoardCellAnchor, deltaRows: Int, deltaColumns: Int, fromEditor: Bool = false) async {
        guard let field = model.boardFieldItems.first(where: { $0.row == anchor.row && $0.column == anchor.column && $0.buttonId != nil }) else { return }
        let options = await model.availableFieldSpanOptions(row: anchor.row, col: anchor.column)
        let next = GridFieldSpanInfo(rows: field.rowSpan + deltaRows, columns: field.columnSpan + deltaColumns)
        guard options.contains(next) else {
            model.boardStatusMessage = NSLocalizedString("boardset.cell.size.blocked", comment: "")
            return
        }
        await model.resizeSelectedBoardField(row: anchor.row, col: anchor.column, rows: next.rows, columns: next.columns)
        selectedFieldSpans = options
        if fromEditor {
            editingSpan = next.rows == 1 && next.columns == 1 ? .single : .custom(next)
            editingSpanOptions = await model.availableFieldSpanOptions(row: anchor.row, col: anchor.column)
        } else {
            selectField(anchor)
        }
    }

    private func reloadEditingSpans(anchor: BoardCellAnchor) async {
        let options = await model.availableFieldSpanOptions(row: anchor.row, col: anchor.column)
        let field = model.boardFieldItems.first { $0.row == anchor.row && $0.column == anchor.column }
        editingSpanOptions = options
        if let field, let match = options.first(where: { $0.rows == field.rowSpan && $0.columns == field.columnSpan }) {
            editingSpan = match.rows == 1 && match.columns == 1 ? .single : .custom(match)
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
        editingOpensPage = trimmed(existing?.linkedBoardId) != nil
        editingHidden = existing?.hidden ?? false
        editingMathMode = existing?.mathMode ?? false
        let shape = existing?.shape.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        editingShape = shape.isEmpty ? "rounded" : shape
        editingLanguage = existing?.language ?? ""
        editingRecordingPath = existing?.soundPath
        editingClearsRecording = false
        isRecordingCell = false
        cellMicDenied = false
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

        await MainActor.run {
            isSearchingCellSymbols = true
            editingSymbolError = nil
            editingSymbolResults = []
        }

        // Cancellation ends the search without an error.
        guard let result = await model.searchSymbols(query: trimmed, symbolPackage: editingSymbolPackage.rawValue) else {
            await MainActor.run { isSearchingCellSymbols = false }
            return
        }

        await MainActor.run {
            if result.isSuccess {
                editingSymbolResults = result.symbols.map {
                    CellOpenSymbolsSymbolResult(
                        id: $0.id,
                        name: $0.name,
                        image_url: $0.imageUrl,
                        source: $0.source
                    )
                }
            } else {
                editingSymbolError = NSLocalizedString(
                    "phrase.symbol.error.\(symbolSearchErrorCode(result.status))", comment: ""
                )
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

    /// Locking a screen for the first time asks the carer to choose a PIN.
    /// Unlocking always asks for that PIN. The device passcode is not used.
    @MainActor
    private func beginScreenLockToggle(_ set: BoardSetInfo) async {
        await model.refreshEditingAccess()
        pendingEditingBoardSetId = nil
        pendingDeleteBoardSetId = nil
        if !set.isLocked && model.editingAccessEnabled {
            await model.selectBoardSet(id: set.id)
            model.setSelectedBoardSetLocked(true)
            return
        }
        pendingBoardLockId = set.id
        pendingBoardLockUnlocks = set.isLocked
        pinMode = model.editingAccessEnabled ? .unlock : .create
        showEditingAccessSheet = true
    }

    @MainActor
    private func finishEditingAccessSuccess() async {
        if let lockId = pendingBoardLockId {
            let unlocks = pendingBoardLockUnlocks
            pendingBoardLockId = nil
            await model.selectBoardSet(id: lockId)
            model.setSelectedBoardSetLocked(!unlocks)
            return
        }
        if let deleteId = pendingDeleteBoardSetId {
            pendingDeleteBoardSetId = nil
            await model.deleteBoardSet(id: deleteId)
        } else if let id = pendingEditingBoardSetId {
            pendingEditingBoardSetId = nil
            route = .workspace(boardSetId: id, mode: .edit)
        }
    }

    @MainActor
    private func requestEnterEditing(boardSetId: String) async {
        if await model.editingIsAuthorized() {
            route = .workspace(boardSetId: boardSetId, mode: .edit)
        } else {
            pendingEditingBoardSetId = boardSetId
            pendingDeleteBoardSetId = nil
            pendingBoardLockId = nil
            pinMode = .unlock
            showEditingAccessSheet = true
        }
    }

    private func printScreen(id: String) {
        guard !exportingPdf else { return }
        exportingPdf = true
        model.boardStatusMessage = nil
        Task {
            defer { exportingPdf = false }
            do { pdfUrl = try await exportScreenPdf(id: id) }
            catch { model.boardStatusMessage = NSLocalizedString("boardset.print_failed", comment: "") }
        }
    }

    private func exportBoardSet(id: String) async {
        let result = try? await IosDiBridge().boardsFacade().shareBoardSetAsObz(id: id)
        let message: String
        if let result, result.isSuccess, let fileName = result.fileName {
            message = String(format: NSLocalizedString("boardset.export_obz_done", comment: ""), fileName)
        } else {
            message = NSLocalizedString("boardset.export_obz_failed", comment: "")
        }
        await MainActor.run {
            model.boardStatusMessage = message
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
    let onDelete: () -> Void

    @State private var preview: ScreenPreview?
    @State private var previewLoaded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 10) {
                    ScreenLibraryPreview(preview: preview, isLoaded: previewLoaded, isLocked: set.isLocked)
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

    private var openAccessibilityLabel: String {
        if set.isLocked {
            return "\(set.name), \(pageSummary), \(NSLocalizedString("board_sets.library.locked", comment: ""))"
        }
        return "\(set.name), \(pageSummary)"
    }
}

/// A miniature of a screen's home page. Empty cells stay blank, matching use mode.
private struct ScreenLibraryPreview: View {
    var preview: ScreenPreview?
    var isLoaded: Bool
    var isLocked: Bool

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
