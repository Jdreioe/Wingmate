import Foundation
import Shared

/// Feature-scoped observable model for the Screens workspace: Screen library, Page navigation,
/// Grid cells/fields, Screen & Page editing, exports, predictions, and speech cache sync (#187).
@MainActor
final class ScreensModel: ObservableObject {
    @Published private(set) var isCreatingBoardSet: Bool = false
    @Published var boardSets: [BoardSetInfo] = []
    @Published var selectedBoardSetId: String? = nil
    @Published var selectedBoardId: String? = nil
    @Published var selectedBoard: Shared.ObfBoard? = nil {
        didSet { if oldValue != selectedBoard { boardLayoutRevision &+= 1 } }
    }
    @Published var loadedBoardCellsPageId: String? = nil
    @Published var boardCellsLoadFailedPageId: String? = nil
    /// Bumped whenever anything that positions Screen Buttons changes (board, cells, fields,
    /// keyguard spacing). A Touch Exit gesture captures it at render and rejects moves and lifts
    /// once the model has moved past the layout the Communicator was looking at.
    var boardLayoutRevision: Int = 0
    /// Bumped by every Page load so an older same-Page completion cannot overwrite a newer one.
    private var boardLoadGeneration: Int = 0
    @Published var boardCells: [BoardCellInfo] = [] {
        didSet { if oldValue != boardCells { boardLayoutRevision &+= 1 } }
    }
    @Published var boardFieldItems: [BoardFieldItem] = [] {
        didSet { if oldValue != boardFieldItems { boardLayoutRevision &+= 1 } }
    }
    @Published private(set) var selectedBoardKeyboardLayout: String? = nil
    @Published private(set) var selectedBoardUsesSpellingMode: Bool = false
    @Published private(set) var boardPredictionsByButtonId: [String: String] = [:]
    @Published var boardNamesById: [String: String] = [:]
    @Published var boardStatusMessage: String? = nil
    @Published private(set) var boardBackgroundImageUrl: String? = nil
    @Published private(set) var boardBackgroundImageFit: String = "Fill"
    @Published private(set) var resolvedBoardShowLabels: Bool? = nil
    @Published private(set) var resolvedBoardShowSymbols: Bool? = nil
    @Published private(set) var resolvedBoardLabelAtTop: Bool? = nil
    @Published private(set) var resolvedBoardShowMessageBar: Bool? = nil
    @Published private(set) var resolvedBoardShowSpeakButton: Bool? = nil
    @Published private(set) var resolvedBoardMessageBarActions: [String]? = nil
    @Published private(set) var resolvedBoardSpeechPolicy: String? = nil
    @Published private(set) var resolvedBoardActivationBehavior: String? = nil
    @Published private(set) var resolvedBoardReturnBehavior: String? = nil
    @Published var resolvedBoardCellGapDp: Int? = nil {
        didSet { if oldValue != resolvedBoardCellGapDp { boardLayoutRevision &+= 1 } }
    }
    @Published var resolvedBoardOuterMarginDp: Int? = nil {
        didSet { if oldValue != resolvedBoardOuterMarginDp { boardLayoutRevision &+= 1 } }
    }
    @Published private(set) var boardStack: [String] = []

    let service: any ScreensService
    private let settings: AppSettingsModel
    let editingAccess: EditingAccessModel
    private let onSpeak: (String) -> Void
    private let onRememberButtonLooks: (String, [(buttonId: String, label: String?, vocalization: String?, imageUrl: String?)]) -> Void

    private var boardPredictionSubscription: (any CancellableSubscription)?
    var sentenceCachingTasks: [String: Task<Void, Never>] = [:]
    var lockToggleTask: Task<Void, Never>?
    private var speechCacheTask: Task<Void, Never>?
    private var hasStartedConnectivityMonitor = false

    init(
        service: any ScreensService = SharedScreensService(),
        settings: AppSettingsModel,
        editingAccess: EditingAccessModel,
        onSpeak: @escaping (String) -> Void = { _ in },
        onRememberButtonLooks: @escaping (String, [(buttonId: String, label: String?, vocalization: String?, imageUrl: String?)]) -> Void = { _, _ in }
    ) {
        self.service = service
        self.settings = settings
        self.editingAccess = editingAccess
        self.onSpeak = onSpeak
        self.onRememberButtonLooks = onRememberButtonLooks
    }

    deinit {
        boardPredictionSubscription?.cancel()
        sentenceCachingTasks.values.forEach { $0.cancel() }
        lockToggleTask?.cancel()
        speechCacheTask?.cancel()
    }

    func cancelPendingTasks() {
        stopBoardPredictions()
        sentenceCachingTasks.values.forEach { $0.cancel() }
        sentenceCachingTasks.removeAll()
        lockToggleTask?.cancel()
        lockToggleTask = nil
        speechCacheTask?.cancel()
        speechCacheTask = nil
    }

    // MARK: - Resolved Screen & Page State

    var boardShowLabels: Bool { resolvedBoardShowLabels ?? settings.showButtonLabels }
    var boardShowSymbols: Bool { resolvedBoardShowSymbols ?? settings.showButtonSymbols }
    var boardLabelAtTop: Bool { resolvedBoardLabelAtTop ?? settings.labelAtTop }
    var boardMessageBarVisible: Bool { resolvedBoardShowMessageBar ?? settings.boardShowMessageBar }
    var boardSpeakButtonVisible: Bool { resolvedBoardShowSpeakButton ?? settings.boardShowSpeakButton }

    /// The Screen's message bar buttons in order (`Speak`, `Hold`, `DeleteLast`, `Clear`).
    var boardMessageBarActions: [String] {
        resolvedBoardMessageBarActions ?? (settings.boardShowSpeakButton ? ["Speak"] : [])
    }

    var boardActivationBehavior: String { resolvedBoardActivationBehavior ?? "SpeakAndAdd" }
    var boardReturnBehavior: String { resolvedBoardReturnBehavior ?? "Stay" }

    var shouldSpeakSelectionImmediately: Bool {
        settings.shouldSpeakSelectionImmediately(
            resolvedBoardSpeechPolicy: resolvedBoardSpeechPolicy,
            activationBehavior: boardActivationBehavior
        )
    }

    var selectedBoardSet: BoardSetInfo? {
        guard let id = selectedBoardSetId else { return nil }
        return boardSets.first(where: { $0.id == id })
    }

    var selectedBoardSetLocked: Bool {
        selectedBoardSet?.isLocked ?? false
    }

    var canEditSelectedBoardSet: Bool {
        !selectedBoardSetLocked
    }

    var isKeyboardBoard: Bool {
        selectedBoardKeyboardLayout != nil
    }

    var canNavigateBoardBack: Bool { !boardStack.isEmpty }

    var isOnStartPage: Bool {
        guard let root = selectedBoardSet?.rootBoardId else { return true }
        return selectedBoardId == root
    }

    func boardDisplayName(id: String) -> String {
        if let selectedBoard, selectedBoard.id == id {
            let selectedName = selectedBoard.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !selectedName.isEmpty {
                return selectedName
            }
        }
        if let cachedName = boardNamesById[id], !cachedName.isEmpty {
            return cachedName
        }
        return id
    }

    func cellAt(row: Int, col: Int) -> BoardCellInfo? {
        boardCells.first(where: { $0.row == row && $0.col == col })
    }

    // MARK: - Connectivity & Speech Cache

    func startSpeechCacheConnectivityMonitoring() {
        guard !hasStartedConnectivityMonitor else { return }
        hasStartedConnectivityMonitor = true
        ConnectivityMonitor.shared.onChange { [weak self] online in
            guard let self else { return }
            self.handleConnectivityChanged(online: online)
        }
    }

    func handleConnectivityChanged(online: Bool) {
        service.updateBoardSetSpeechCacheOnline(online: online)
        speechCacheTask?.cancel()
        guard online else {
            speechCacheTask = nil
            return
        }
        speechCacheTask = Task { [weak self] in
            guard let self else { return }
            await self.warmSpeechCache(retryPending: true)
        }
    }

    func warmSpeechCache(retryPending: Bool = false) async {
        do {
            try await service.cacheAllBoardSetFields()
            if retryPending {
                try await service.retryBoardSetSpeechCaching()
            }
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            // Speech cache warming is best-effort background work; offline or transient network
            // failures are retried automatically when connectivity changes.
        }
    }

    // MARK: - Screen Library & Navigation

    func loadBoardSets() async {
        do {
            let sharedSets = try await service.listBoardSets()
            boardSets = sharedSets
                .map { boardSetInfo(from: $0) }
                .sorted { $0.updatedAt > $1.updatedAt }
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            boardSets = []
            boardStatusMessage = NSLocalizedString("board_sets.error.load_failed", comment: "")
        }

        if selectedBoardSetId == nil || !boardSets.contains(where: { $0.id == selectedBoardSetId }) {
            selectedBoardSetId = boardSets.first?.id
        }

        if let set = selectedBoardSet {
            if selectedBoardId == nil || !set.boardIds.contains(selectedBoardId ?? "") {
                selectedBoardId = set.rootBoardId
            }
            await loadSelectedBoard()
            await refreshBoardNames(for: set)
        } else {
            selectedBoardId = nil
            clearSelectedBoardState()
            boardNamesById = [:]
        }
    }

    func loadScreenPreview(rootBoardId: String) async -> ScreenPreview? {
        do {
            guard let board = try await service.getBoard(id: rootBoardId) else { return nil }
            let rows = max(1, Int(board.grid?.rows ?? 1))
            let columns = max(1, Int(board.grid?.columns ?? 1))
            let fields = try await service.listBoardFieldItems(boardId: rootBoardId)
            let cells = try await service.listBoardCells(boardId: rootBoardId)
            struct AnchorCell {
                var label: String?
                var imageUrl: String?
                var color: String?
                var hidden: Bool
            }
            var byAnchor: [String: AnchorCell] = [:]
            for cell in cells {
                byAnchor["\(Int(cell.row)):\(Int(cell.col))"] = AnchorCell(
                    label: cell.label,
                    imageUrl: cell.imageUrl,
                    color: cell.resolvedBackgroundColor ?? cell.backgroundColor,
                    hidden: cell.hidden
                )
            }
            let visible = fields.compactMap { field -> ScreenPreviewCell? in
                guard let buttonId = field.buttonId, !buttonId.isEmpty,
                      let cell = byAnchor["\(Int(field.row)):\(Int(field.column))"],
                      !cell.hidden else { return nil }
                let label = cell.label?.trimmingCharacters(in: .whitespacesAndNewlines)
                let image = cell.imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
                return ScreenPreviewCell(
                    row: Int(field.row),
                    column: Int(field.column),
                    rowSpan: max(1, Int(field.rowSpan)),
                    columnSpan: max(1, Int(field.columnSpan)),
                    colorHex: cell.color,
                    label: (label?.isEmpty == false) ? label : nil,
                    imageUrl: (image?.isEmpty == false) ? image : nil
                )
            }
            return ScreenPreview(
                rows: rows,
                columns: columns,
                backgroundHex: board.backgroundColor,
                cells: visible
            )
        } catch {
            return nil
        }
    }

    func previewSpeech(name: String, text: String, paragraphsPerPage: Int) -> [SpeechImportPage] {
        do {
            return try service.previewSpeech(name: name, text: text, paragraphsPerPage: Int32(paragraphsPerPage))
        } catch {
            return []
        }
    }

    func importSpeech(name: String, text: String, paragraphsPerPage: Int) async -> String? {
        guard !isCreatingBoardSet else { return nil }
        isCreatingBoardSet = true
        defer { isCreatingBoardSet = false }
        do {
            let set = try await service.importSpeech(
                name: name,
                text: text,
                paragraphsPerPage: Int32(paragraphsPerPage),
                previousLabel: NSLocalizedString("speech_import.previous", comment: ""),
                nextLabel: NSLocalizedString("speech_import.next", comment: "")
            )
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = set.rootBoardId
            await loadSelectedBoard()
            return set.id
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return nil }
            boardStatusMessage = NSLocalizedString("speech_import.error", comment: "")
            return nil
        }
    }

    /// Pulls and pushes linked Screens in the background; communication never waits on it.
    func syncRemoteScreensAutomatic(force: Bool = false) async {
        do {
            let changed = try await service.syncRemoteScreensAutomatic(force: force)
            if changed {
                await loadBoardSets()
            }
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            // Automatic sync is non-blocking; communication continues offline.
        }
    }

    func createBoardSet(name: String, rows: Int, columns: Int) async {
        isCreatingBoardSet = true
        defer { isCreatingBoardSet = false }
        let boardsetName = normalizedBoardsetName(name)
        let safeRows = min(max(rows, 1), 12)
        let safeColumns = min(max(columns, 1), 12)

        do {
            let sharedSet = try await service.createBoardSet(
                name: boardsetName,
                rows: Int32(safeRows),
                columns: Int32(safeColumns),
                rootBoardName: NSLocalizedString("boardset.default_root_page", comment: "")
            )
            let set = boardSetInfo(from: sharedSet)
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = set.rootBoardId
            await loadSelectedBoard()
            boardStatusMessage = NSLocalizedString("boardset.status.created", comment: "")
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
        }
    }

    func createKeyboardBoardSet(name: String, preset: String) async {
        isCreatingBoardSet = true
        defer { isCreatingBoardSet = false }
        let boardsetName = normalizedBoardsetName(name)
        do {
            let sharedSet = try await service.createKeyboardBoardSet(name: boardsetName, preset: preset)
            let set = boardSetInfo(from: sharedSet)
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = set.rootBoardId
            await loadSelectedBoard()
            boardStatusMessage = NSLocalizedString("boardset.status.created", comment: "")
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
        }
    }

    func addBoardToSelectedSet(name: String, rows: Int, columns: Int) async {
        guard let set = selectedBoardSet else { return }
        guard !set.isLocked else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }

        let boardName = normalizedBoardsetName(name)
        let safeRows = min(max(rows, 1), 12)
        let safeColumns = min(max(columns, 1), 12)

        do {
            let board = try await service.createBoard(
                boardSetId: set.id,
                name: boardName,
                rows: Int32(safeRows),
                columns: Int32(safeColumns)
            )
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = board.id
            await loadSelectedBoard()
            boardStatusMessage = NSLocalizedString("boardset.status.board_added", comment: "")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.create_failed")
        }
    }

    func addKeyboardBoardToSelectedSet(name: String, rows: Int, columns: Int, layout: String) async {
        guard let set = selectedBoardSet, !set.isLocked else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }
        do {
            let board = try await service.createKeyboardBoard(
                boardSetId: set.id,
                name: normalizedBoardsetName(name),
                rows: Int32(min(max(rows, 1), 12)),
                columns: Int32(min(max(columns, 1), 12)),
                layout: layout
            )
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = board.id
            await loadSelectedBoard()
            boardStatusMessage = NSLocalizedString("boardset.status.board_added", comment: "")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.create_failed")
        }
    }

    func selectBoardSet(id: String) async {
        guard boardSets.contains(where: { $0.id == id }) else { return }
        selectedBoardSetId = id
        boardStack = []
        if let set = selectedBoardSet {
            if selectedBoardId == nil || !set.boardIds.contains(selectedBoardId ?? "") {
                selectedBoardId = set.rootBoardId
            }
            await loadSelectedBoard()
            await refreshBoardNames(for: set)
        }
    }

    func selectBoard(id: String, resetStack: Bool = false) async {
        if let set = selectedBoardSet, !set.boardIds.contains(id) {
            return
        }
        if resetStack { boardStack = [] }
        if loadedBoardCellsPageId != id {
            loadedBoardCellsPageId = nil
        }
        boardCellsLoadFailedPageId = nil
        selectedBoardId = id
        await loadSelectedBoard()
    }

    /// Pops the Page the Communicator opened this one from.
    func navigateBoardBack() async {
        guard let previous = boardStack.last else { return }
        boardStack.removeLast()
        await selectBoard(id: previous)
    }

    /// Returns to the Screen's start Page and forgets the path taken to get here.
    func navigateBoardHome() async {
        guard let root = selectedBoardSet?.rootBoardId else { return }
        boardStack = []
        guard root != selectedBoardId else { return }
        await selectBoard(id: root)
    }

    func pushBoardNavigationStack(_ boardId: String) {
        guard !boardStack.contains(boardId) else { return }
        boardStack.append(boardId)
    }

    func applyBoardReturnBehavior() async {
        let result = service.boardReturnBehavior(
            behavior: boardReturnBehavior,
            currentBoardId: selectedBoardId,
            boardStack: boardStack,
            rootBoardId: selectedBoardSet?.rootBoardId ?? ""
        )
        let nextBoardId = result.boardId
        boardStack = result.boardStack
        guard let nextBoardId, nextBoardId != selectedBoardId else { return }
        selectedBoardId = nextBoardId
        await loadSelectedBoard()
    }

    func swipePage(by offset: Int, touchExitOwnsGrid: Bool = false) async {
        guard TouchExitControllerKt.pageSwipeIsActive(
                isEditMode: false,
                swipeBetweenPages: settings.swipeBetweenPages,
                holdToSelectMillis: Int64(settings.holdToSelectMillis),
                selectOnTouchDown: settings.selectOnTouchDown,
                selectOnTouchExit: touchExitOwnsGrid,
                disableOnHoldToSelect: true
              ),
              let ids = selectedBoardSet?.boardIds, let id = selectedBoardId,
              let index = ids.firstIndex(of: id), ids.indices.contains(index + offset) else { return }
        await selectBoard(id: ids[index + offset])
    }

    /// True only when the rendered board and its loaded cells both belong to the currently selected Page.
    func boardCellsReady(for pageId: String) -> Bool {
        selectedBoardId == pageId &&
            selectedBoard?.id == pageId &&
            loadedBoardCellsPageId == pageId
    }

    /// Scopes Screen Button debounce to `(pageId, buttonId)` so Pages that reuse the same
    /// OBF Button ID do not wrongly debounce legitimate selections after navigation.
    func screenButtonDebounceKey(pageId: String? = nil, buttonId: String) -> String {
        "board:\(pageId ?? selectedBoardId ?? ""):\(buttonId)"
    }

    /// Scopes Screen Button hover, dwell, and switch access targets to `(pageId, buttonId)`.
    func screenButtonAccessTargetId(pageId: String, buttonId: String) -> String {
        "board:\(pageId):\(buttonId)"
    }

    /// Registers a dwell/switch access target that resolves the live Screen Button at activation
    /// time and verifies both Page readiness and Button identity before invoking [action].
    func registerScreenButtonAccessTarget(
        pageId: String,
        row: Int,
        col: Int,
        buttonId: String,
        action: @escaping (BoardCellInfo) -> Void
    ) {
        let targetId = screenButtonAccessTargetId(pageId: pageId, buttonId: buttonId)
        settings.registerAccessTarget(targetId, handlesHaptics: true) { [weak self] in
            guard let self,
                  self.boardCellsReady(for: pageId),
                  let current = self.cellAt(row: row, col: col),
                  current.buttonId == buttonId else { return }
            action(current)
        }
    }

    // MARK: - Page Loading & Predictions

    func loadSelectedBoard() async {
        guard let id = selectedBoardId else {
            boardLoadGeneration &+= 1
            clearSelectedBoardState(failedPageId: nil)
            return
        }
        // Preserve readiness across same-Page reloads (settings save, lock toggle, background sync)
        // so the grid stays mounted and keeps dwell/switch focus; invalidate only on Page switch.
        if loadedBoardCellsPageId != id {
            loadedBoardCellsPageId = nil
        }
        boardCellsLoadFailedPageId = nil
        boardLoadGeneration &+= 1
        let generation = boardLoadGeneration
        do {
            // One snapshot from a single board read, so settings, Buttons, and field spans
            // always come from the same Page revision.
            let snapshot = try await service.loadBoardSnapshot(boardId: id)
            // A newer load (or a Page switch) started while this one was suspended.
            guard selectedBoardId == id, boardLoadGeneration == generation else { return }
            guard let snapshot else {
                clearSelectedBoardState(failedPageId: id)
                return
            }
            applyBoardSnapshot(snapshot, pageId: id)
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            guard selectedBoardId == id, boardLoadGeneration == generation else { return }
            clearSelectedBoardState(failedPageId: id)
        }
    }

    func refreshBoardCells() async {
        guard let boardId = selectedBoardId else {
            boardLoadGeneration &+= 1
            loadedBoardCellsPageId = nil
            boardCellsLoadFailedPageId = nil
            boardCells = []
            boardFieldItems = []
            return
        }

        boardLoadGeneration &+= 1
        let generation = boardLoadGeneration
        do {
            let snapshot = try await service.loadBoardSnapshot(boardId: boardId)
            guard selectedBoardId == boardId, boardLoadGeneration == generation else { return }
            guard let snapshot else {
                clearSelectedBoardState(failedPageId: boardId)
                return
            }
            applyBoardSnapshot(snapshot, pageId: boardId)
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            guard selectedBoardId == boardId, boardLoadGeneration == generation else { return }
            loadedBoardCellsPageId = nil
            boardCellsLoadFailedPageId = boardId
            boardCells = []
            boardFieldItems = []
        }
    }

    /// Publishes every piece of a Page snapshot with no suspension in between, so SwiftUI
    /// never renders new grid geometry against stale Buttons or settings.
    private func applyBoardSnapshot(_ snapshot: Shared.IosBoardSnapshot, pageId id: String) {
        let loadedBoard = snapshot.board
        let resolved = snapshot.settings
        selectedBoard = loadedBoard
        boardBackgroundImageUrl = service.backgroundImageUrl(board: loadedBoard)
        boardBackgroundImageFit = service.backgroundImageFit(board: loadedBoard)
        selectedBoardKeyboardLayout = service.boardKeyboardLayout(board: loadedBoard)
        selectedBoardUsesSpellingMode = service.boardUsesSpellingMode(board: loadedBoard)
        resolvedBoardShowLabels = resolved.showLabels
        resolvedBoardShowSymbols = resolved.showSymbols
        resolvedBoardLabelAtTop = resolved.labelAtTop
        resolvedBoardShowMessageBar = resolved.showMessageBar
        resolvedBoardShowSpeakButton = resolved.showSpeakButton
        resolvedBoardMessageBarActions = resolved.messageBarActions
        resolvedBoardActivationBehavior = resolved.activationBehavior
        resolvedBoardSpeechPolicy = resolved.speechPolicy
        resolvedBoardReturnBehavior = resolved.returnBehavior
        resolvedBoardCellGapDp = resolved.cellGapDp?.intValue
        resolvedBoardOuterMarginDp = resolved.outerMarginDp?.intValue
        let boardName = loadedBoard.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !boardName.isEmpty {
            boardNamesById[id] = boardName
        }
        boardCells = snapshot.cells.map { cell in
            BoardCellInfo(
                row: Int(cell.row),
                col: Int(cell.col),
                buttonId: cell.buttonId,
                label: cell.label,
                vocalization: cell.vocalization,
                backgroundColor: cell.backgroundColor,
                resolvedBackgroundColor: cell.resolvedBackgroundColor,
                wordType: cell.wordType,
                borderColor: cell.borderColor,
                linkedBoardId: cell.linkedBoardId,
                imageId: cell.imageId,
                imageUrl: cell.imageUrl,
                hidden: cell.hidden,
                actions: cell.actions,
                soundId: cell.soundId,
                soundDataUrl: cell.soundDataUrl,
                soundPath: cell.soundPath,
                shape: cell.shape,
                language: cell.language,
                mathMode: cell.mathMode
            )
        }
        boardFieldItems = snapshot.fieldItems.map { field in
            BoardFieldItem(
                row: Int(field.row),
                column: Int(field.column),
                rowSpan: Int(field.rowSpan),
                columnSpan: Int(field.columnSpan),
                buttonId: field.buttonId
            )
        }
        loadedBoardCellsPageId = id
        boardCellsLoadFailedPageId = nil
        onRememberButtonLooks(
            id,
            boardCells.map { ($0.buttonId, $0.label, $0.vocalization, $0.imageUrl) }
        )
    }

    /// Executes a resolved Screen Button's deferred navigation or speech only while [pageId]
    /// still owns the active loaded cells, preventing stale-Page activations during async handoff.
    func activateBoardCell(_ cell: BoardCellInfo, onPageId pageId: String, checkDebounce: Bool = true) async {
        guard boardCellsReady(for: pageId) else { return }

        if let linkedBoardId = normalizedOptionalText(cell.linkedBoardId),
           let set = selectedBoardSet,
           set.boardIds.contains(linkedBoardId) {
            await selectBoard(id: linkedBoardId)
            return
        }

        // #118: navigation is an explicit action; speech insertion is debounced per Page and Button.
        if checkDebounce && !settings.acceptActivation(targetId: screenButtonDebounceKey(pageId: pageId, buttonId: cell.buttonId)) {
            return
        }

        if let textToSpeak = normalizedOptionalText(cell.vocalization) ?? normalizedOptionalText(cell.label) {
            onSpeak(textToSpeak)
        }
    }

    func activateBoardSelectionHighlight(buttonId: String) async {
        settings.triggerSelectionHighlight(buttonId: buttonId)
    }

    func refreshBoardPredictions(context: String) async {
        stopBoardPredictions()
        var seenIds = Set<String>()
        let predictorIds = boardCells
            .filter { cell in cell.actions.contains { $0.lowercased() == ":prediction" || $0.lowercased() == ":predictions" } }
            .map(\.buttonId)
            .filter { seenIds.insert($0).inserted }
        guard !predictorIds.isEmpty else { return }
        boardPredictionSubscription = service.observePredictions(
            context: context,
            maxWords: Int32(predictorIds.count),
            maxLetters: 0
        ) { [weak self] update in
            // Unavailable predictions leave the predictor Buttons empty rather than stale.
            self?.boardPredictionsByButtonId = Dictionary(
                uniqueKeysWithValues: zip(predictorIds, update.predictions.words).map { ($0.0, $0.1) }
            )
        }
    }

    func stopBoardPredictions() {
        boardPredictionSubscription?.cancel()
        boardPredictionSubscription = nil
        // Publishing an unchanged value still re-renders the whole Screen.
        if !boardPredictionsByButtonId.isEmpty { boardPredictionsByButtonId = [:] }
    }

    func boardPrediction(for buttonId: String) -> String? {
        boardPredictionsByButtonId[buttonId]
    }

    func nGramPredictionInsertion(sentence: String, suggestion: String) -> String {
        service.nGramPredictionInsertion(sentence: sentence, suggestion: suggestion)
    }

    func boardButtonIsVisible(hidden: Bool, isEditMode: Bool, showHiddenButtons: Bool) -> Bool {
        service.boardButtonIsVisible(hidden: hidden, isEditMode: isEditMode, showHiddenButtons: showHiddenButtons)
    }

    func boardFieldFontScale(rowSpan: Int, columnSpan: Int) -> CGFloat {
        CGFloat(service.boardFieldFontScale(rowSpan: Int32(rowSpan), columnSpan: Int32(columnSpan)))
    }

    // MARK: - Helpers

    /// Shows why a Screen edit failed. A Screen or Page that no longer exists reloads the list instead.
    func reportBoardFailure(_ error: Error, messageKey: String) async {
        if error.isNativeCancellation || Task.isCancelled { return }
        if error.nativeFailure?.kind == NativeFailureKind.notfound {
            await loadBoardSets()
            boardStatusMessage = NSLocalizedString("boardset.error.not_found", comment: "")
            return
        }
        boardStatusMessage = NSLocalizedString(messageKey, comment: "")
    }

    func applyExportResult(_ result: Shared.BoardSetExportResult) {
        if result.isSuccess, let fileName = result.fileName {
            boardStatusMessage = String(format: NSLocalizedString("boardset.export_obz_done", comment: ""), fileName)
        } else {
            boardStatusMessage = NSLocalizedString("boardset.export_obz_failed", comment: "")
        }
    }

    func boardSetInfo(from set: Shared.ObfBoardSet) -> BoardSetInfo {
        BoardSetInfo(
            id: set.id,
            name: set.name,
            rootBoardId: set.rootBoardId,
            boardIds: set.boardIds,
            isLocked: set.isLocked,
            cacheWholeSentences: set.cacheWholeSentences,
            updatedAt: TimeInterval(set.updatedAt) / 1_000,
            remoteName: set.remoteLink?.resolvedDisplayName(),
            remoteWritable: set.remoteLink?.isWritable == true,
            remoteConflict: set.remoteLink?.hasConflict == true,
            remoteKind: set.remoteLink?.kind.wireValue,
            remoteLocation: set.remoteLink?.location,
            remoteOwnership: set.remoteLink?.resolvedOwnership?.wireValue,
            remoteOwnerName: set.remoteLink?.ownerName,
            remoteOffersDelete: set.remoteLink?.offersRemoteDelete == true
        )
    }

    func updateBoardSet(_ updated: BoardSetInfo) {
        guard let idx = boardSets.firstIndex(where: { $0.id == updated.id }) else { return }
        boardSets[idx] = updated
    }

    func normalizedBoardsetName(_ input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? NSLocalizedString("boardset.default_name", comment: "") : trimmed
    }

    func normalizedOptionalText(_ input: String?) -> String? {
        let trimmed = (input ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    func touchBoardSetIfSelected() async {
        guard let setId = selectedBoardSetId else { return }
        do {
            _ = try await service.touchBoardSet(id: setId)
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            // BoardsFacade already touches the containing Screen on cell edits; keep the save status intact.
        }
    }

    func touchSelectedBoardSet(statusKey: String) {
        if var set = selectedBoardSet {
            set.updatedAt = Date().timeIntervalSince1970
            updateBoardSet(set)
        }
        boardStatusMessage = NSLocalizedString(statusKey, comment: "")
    }

    private func refreshBoardNames(for set: BoardSetInfo?) async {
        guard let set else {
            boardNamesById = [:]
            return
        }

        var cache = boardNamesById
        for boardId in set.boardIds {
            if let selectedBoard, selectedBoard.id == boardId {
                let selectedName = selectedBoard.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !selectedName.isEmpty {
                    cache[boardId] = selectedName
                }
                continue
            }

            do {
                if let board = try await service.getBoard(id: boardId) {
                    let name = board.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    if !name.isEmpty {
                        cache[boardId] = name
                    }
                }
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return }
                // Keep existing cache value if a single page name lookup fails.
            }
        }

        boardNamesById = cache
    }

    private func refreshSelectedBoardMetadata() {
        guard let board = selectedBoard else {
            selectedBoardKeyboardLayout = nil
            selectedBoardUsesSpellingMode = false
            return
        }
        selectedBoardKeyboardLayout = service.boardKeyboardLayout(board: board)
        selectedBoardUsesSpellingMode = service.boardUsesSpellingMode(board: board)
    }

    func clearSelectedBoardState(failedPageId: String? = nil) {
        selectedBoard = nil
        loadedBoardCellsPageId = nil
        boardCellsLoadFailedPageId = failedPageId
        boardCells = []
        boardFieldItems = []
        selectedBoardKeyboardLayout = nil
        selectedBoardUsesSpellingMode = false
        boardPredictionsByButtonId = [:]
        boardBackgroundImageUrl = nil
        boardBackgroundImageFit = "Fill"
        clearResolvedBoardSettings()
    }

    private func clearResolvedBoardSettings() {
        resolvedBoardShowLabels = nil
        resolvedBoardShowSymbols = nil
        resolvedBoardLabelAtTop = nil
        resolvedBoardShowMessageBar = nil
        resolvedBoardShowSpeakButton = nil
        resolvedBoardMessageBarActions = nil
        resolvedBoardActivationBehavior = nil
        resolvedBoardSpeechPolicy = nil
        resolvedBoardReturnBehavior = nil
        resolvedBoardCellGapDp = nil
        resolvedBoardOuterMarginDp = nil
    }
}
