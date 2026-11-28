import SwiftUI
import Shared
import PhotosUI
import UniformTypeIdentifiers
import UIKit
import AudioToolbox

struct SymbolBoardWorkspaceView: View {
    @ObservedObject var model: IosViewModel
    @Binding var isEditingScreen: Bool
    @Environment(\.horizontalSizeClass) var horizontalSizeClass
    /// Opens the app settings panel, which ContentView owns.
    var onShowSettings: () -> Void = {}

    init(
        model: IosViewModel,
        isEditingScreen: Binding<Bool> = .constant(false),
        onShowSettings: @escaping () -> Void = {}
    ) {
        self.model = model
        self._isEditingScreen = isEditingScreen
        self.onShowSettings = onShowSettings
    }
    @Namespace var sentenceAnimationNamespace

    @State var backgroundPhoto: PhotosPickerItem?
    @State var showBackgroundEditor = false
    @State var importingBackground = false
    @State var backgroundFit = "Fill"
    @State var pdfUrl: URL?
    @State var exportingPdf = false
    @State var route: BoardSetRoute = .library
    @State var hasAppliedStartupDestination = false
    @State var showCreateBoardsetSheet = false
    @State var showSpeechImport = false
    @State var speechName = ""
    @State var speechText = ""
    @State var speechParagraphsPerPage = 4
    @State var speechImportFailed = false
    @State var showAddBoardSheet = false

    @State var createBoardsetName: String = ""
    @State var createBoardsetRows: Int = 4
    @State var createBoardsetColumns: Int = 4
    @State var createBoardsetKind: BoardSetCreationKind = .blank

    @State var addBoardName: String = ""
    @State var addBoardRows: Int = 4
    @State var addBoardColumns: Int = 4
    @State var addBoardKeyboardLayout: String = "qwerty"

    @State var deleteTargetSet: BoardSetInfo? = nil
    @State var pendingDeleteRemote = false
    @State var exportTargetSetId: String? = nil
    @State var showEditCellSheet: Bool = false
    @State var managementSheet: BoardManagementSheet? = nil
    @State var managementName: String = ""
    @State var managementRows: Int = 4
    @State var managementColumns: Int = 4
    @State var managementBackgroundColor: Color = Color(.secondarySystemBackground)
    @State var useCustomManagementBackground = false
    @State var showDeleteBoardConfirmation: Bool = false
    @State var editingRow: Int = 0
    @State var editingCol: Int = 0
    @State var editingSpan: GridFieldSpanSelection = .single
    @State var editingSpanOptions: [GridFieldSpanInfo] = []
    @State var editingCellHasButton: Bool = false
    @State var editingLabel: String = ""
    @State var editingVocalization: String = ""
    @State var editingInsertedText: String = ""
    @State var editingActions: [String] = []
    @State var editingInsertedTextFollowsLabel = false
    @State var editingBackgroundPickerColor: Color = Color(.tertiarySystemBackground)
    @State var editingWordType: String = ""
    @State var editingHidden = false
    @State var editingMathMode = false
    @State var editingShape = "rounded"
    @State var editingLanguage = ""
    @State var editingOpensPage = false
    @State var editingRecordingPath: String? = nil
    @State var editingClearsRecording = false
    @State var isRecordingCell = false
    @State var cellMicDenied = false
    @State var cellRecorder = AudioRecorder()
    @State var editingBorderPickerColor: Color = Color(.separator)
    @State var settingsTarget: BoardSettingsTarget? = nil
    @State var selectedFieldAnchor: BoardCellAnchor? = nil
    @State var selectedFieldSpans: [GridFieldSpanInfo] = []
    @State var fieldDrag: FieldDragState? = nil
    @State var resizePreview: GridFieldSpanInfo? = nil
    @State var resizePreviewValid = true
    @State var suppressNextCellTap = false
    @State var touchExitController = TouchExitController()
    @State var touchExitHighlightedButtonId: String? = nil
    @GestureState var touchExitGestureActive = false
    /// Set when Touch Exit is cancelled while a finger is still down (Page or layout change);
    /// that finger can no longer highlight or activate anything until it lifts.
    @State var touchExitLatch = TouchExitLiftLatch()
    @State var useCustomBackgroundColor: Bool = false
    @State var useCustomBorderColor: Bool = false
    @State var editingLinkedBoardId: String = ""
    @State var editingSymbolSource: CellSymbolSource = .openSymbols
    @State var editingSymbolQuery: String = ""
    @State var editingSymbolPackage: CellSymbolPackageFilter = .all
    @State var editingSelectedSymbolUrl: String? = nil
    @State var editingSelectedPhotoItem: PhotosPickerItem? = nil
    @State var isImportingCellSymbolFile: Bool = false
    @State var editingSymbolResults: [CellOpenSymbolsSymbolResult] = []
    @State var isSearchingCellSymbols: Bool = false
    @State var editingSymbolError: String? = nil
    @State var shouldClearEditingSymbol: Bool = false
    @State var showNativeKeyboardSheet = false
    @State var nativeKeyboardDraft = ""
    @State var showWordFinderSheet = false
    @State var activeSentenceAnimation: ActiveSentenceAnimation? = nil
    @State var showHiddenButtons = false
    @State var showEditingAccessSheet = false
    @State var pinMode: EditingAccessPinMode = .unlock
    @State var pendingEditingBoardSetId: String? = nil
    @State var pendingDeleteBoardSetId: String? = nil
    @State var pendingBoardLockId: String? = nil
    @State var pendingBoardLockUnlocks = false
    @State var showRemoteScreen = false
    @State var remoteScreenId: String?
    @State var pendingRemoteScreen = false
    @State var openRemoteAfterUnlock = false
    @ObservedObject var cloudShares = CloudKitScreenShareService.shared
    @Environment(\.scenePhase) var scenePhase

    struct ActiveSentenceAnimation: Equatable {
        let sourceCellId: String
        let tokenId: String
    }

    var body: some View {
        VStack(spacing: 0) {
            if exportingPdf { ProgressView("boardset.print_preparing").padding(8) }
            if let status = model.screens.boardStatusMessage, !status.isEmpty {
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
                    if let status = model.screens.boardStatusMessage { Text(status).foregroundStyle(.secondary) }
                    if importingBackground { ProgressView() }
                    PhotosPicker(selection: $backgroundPhoto, matching: .images) {
                        Label("boardset.background_choose", systemImage: "photo")
                    }
                    Picker("boardset.background_fit", selection: $backgroundFit) {
                        Text("boardset.background_fill").tag("Fill")
                        Text("boardset.background_fit").tag("Fit")
                    }
                    .onChange(of: backgroundFit) { _, fit in
                        if let source = model.screens.boardBackgroundImageUrl {
                            Task { await model.screens.setPageBackground(imageUrl: source, fit: fit) }
                        }
                    }
                    Button("boardset.background_remove", role: .destructive) {
                        Task { await model.screens.setPageBackground(imageUrl: nil, fit: backgroundFit) }
                    }.disabled(model.screens.boardBackgroundImageUrl == nil)
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
                        model.screens.boardStatusMessage = NSLocalizedString("boardset.background_failed", comment: "")
                        return
                    }
                    let url = try await Task.detached {
                        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("WingmateSymbols", isDirectory: true)
                        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                        let url = directory.appendingPathComponent("\(UUID().uuidString).png")
                        try data.write(to: url, options: .atomic)
                        return url
                    }.value
                    await model.screens.setPageBackground(imageUrl: url.absoluteString, fit: backgroundFit)
                } catch { model.screens.boardStatusMessage = NSLocalizedString("boardset.background_failed", comment: "") }
            }
        }
        .onChange(of: route) { _, newRoute in
            model.screens.stopWordFinderGuidance()
            if case .workspace(_, .edit) = newRoute {
                isEditingScreen = true
                model.screens.resetStayOnCurrentPage()
            } else {
                isEditingScreen = false
                if case .library = newRoute {
                    model.screens.resetStayOnCurrentPage()
                }
            }
        }
        .onDisappear {
            isEditingScreen = false
        }
        .task {
            await model.screens.loadBoardSets()
            if !hasAppliedStartupDestination {
                hasAppliedStartupDestination = true
                if let startupId = model.settings.startupBoardSetId,
                   model.screens.boardSets.contains(where: { $0.id == startupId }) {
                    await model.screens.selectBoardSet(id: startupId)
                    route = .workspace(boardSetId: startupId, mode: .run)
                }
            }
        }
        .task {
            // Linked Screens sync with no input: on open, then every 45 s outside edit mode.
            await model.screens.syncRemoteScreensAutomatic(force: true)
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 45_000_000_000)
                guard !Task.isCancelled else { break }
                if case .workspace(_, .edit) = route { continue }
                await model.screens.syncRemoteScreensAutomatic(force: false)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.screens.syncRemoteScreensAutomatic(force: false) }
            }
        }
        .sheet(isPresented: $showRemoteScreen, onDismiss: { cloudShares.pendingShare = nil }) {
            RemoteScreenSheet(
                target: model.screens.boardSets.first { $0.id == remoteScreenId },
                acceptedShare: remoteScreenId == nil ? cloudShares.pendingShare : nil,
                refresh: { await model.screens.loadBoardSets() }
            )
        }
        .onChange(of: cloudShares.pendingShare) { _, share in
            if let share { openAcceptedShare(share) }
        }
        .onAppear {
            if let share = cloudShares.pendingShare { openAcceptedShare(share) }
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
            BoardSettingsSheet(screens: model.screens, forPage: target.forPage) {
                settingsTarget = nil
            }
        }
        .sheet(isPresented: $showWordFinderSheet) {
            let includeHidden: Bool = {
                if case .workspace(_, .edit) = route { return true }
                return showHiddenButtons
            }()
            WordFinderSheet(
                screens: model.screens,
                includeHidden: includeHidden,
                onSelect: { match in
                    showWordFinderSheet = false
                    selectedFieldAnchor = nil
                    Task { await model.screens.startWordFinderGuidance(match) }
                },
                onDismiss: { showWordFinderSheet = false }
            )
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
                            model.communication.onInputChanged(nativeKeyboardDraft)
                            showNativeKeyboardSheet = false
                        }
                    }
                }
            }
        }
        .sheet(item: $managementSheet) { sheet in
            boardManagementSheet(sheet)
        }
        .sheet(isPresented: $showEditingAccessSheet, onDismiss: {
            if openRemoteAfterUnlock {
                openRemoteAfterUnlock = false
                showRemoteScreen = true
            }
        }) {
            EditingAccessPinSheet(editingAccess: model.editingAccess, mode: pinMode, onSuccess: {
                showEditingAccessSheet = false
                Task { await finishEditingAccessSuccess() }
            }, onCancel: {
                showEditingAccessSheet = false
                pendingEditingBoardSetId = nil
                pendingDeleteBoardSetId = nil
                pendingBoardLockId = nil
                pendingRemoteScreen = false
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
            if let target = deleteTargetSet, target.remoteOffersDelete {
                Button(
                    String(
                        format: NSLocalizedString("board_sets.library.delete_with_remote", comment: ""),
                        NSLocalizedString(target.remoteKind == "onedrive" ? "remote_screen.provider.onedrive" : "remote_screen.provider.google_drive", comment: "")
                    ),
                    role: .destructive
                ) { confirmDeleteScreen(target, deleteRemote: true) }
                Button(NSLocalizedString("board_sets.library.delete_here_only", comment: ""), role: .destructive) {
                    confirmDeleteScreen(target, deleteRemote: false)
                }
            } else {
                Button(NSLocalizedString("common.delete", comment: ""), role: .destructive) {
                    if let target = deleteTargetSet { confirmDeleteScreen(target, deleteRemote: false) }
                }
            }
            Button(NSLocalizedString("common.cancel", comment: ""), role: .cancel) {
                deleteTargetSet = nil
            }
        } message: {
            Text(String(format: NSLocalizedString("board_sets.library.delete_message", comment: ""), deleteTargetSet?.name ?? ""))
        }
        .confirmationDialog(
            "boardset.export_obz",
            isPresented: Binding(
                get: { exportTargetSetId != nil },
                set: { if !$0 { exportTargetSetId = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let id = exportTargetSetId {
                Button("boardset.export_format_obz") {
                    exportTargetSetId = nil
                    Task { await exportBoardSet(id: id) }
                }
                Button("boardset.export_gridset") {
                    exportTargetSetId = nil
                    Task { await exportBoardSetAsGridset(id: id) }
                }
            }
            Button("common.cancel", role: .cancel) {
                exportTargetSetId = nil
            }
        }
        .alert("boardset.delete_board", isPresented: $showDeleteBoardConfirmation) {
            Button("common.cancel", role: .cancel) {}
            Button("common.delete", role: .destructive) {
                Task { await model.screens.deleteSelectedBoard() }
            }
        } message: {
            Text("boardset.delete_board.message")
        }
    }
}
