import Foundation
import Shared

extension IosViewModel {
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

    var isKeyboardBoard: Bool {
        selectedBoardKeyboardLayout != nil
    }

    func availableFieldSpanOptions(row: Int, col: Int) async -> [GridFieldSpanInfo] {
        guard let boardId = selectedBoardId else { return [] }
        let spans = (try? await boardsFacade.availableFieldSpans(
            boardId: boardId,
            row: Int32(row),
            col: Int32(col)
        )) ?? []
        return spans.map { GridFieldSpanInfo(rows: Int($0.rows), columns: Int($0.columns)) }
    }

    func resizeSelectedBoardField(row: Int, col: Int, rows: Int, columns: Int) async {
        guard let boardId = selectedBoardId else { return }
        do {
            try await boardsFacade.resizeBoardField(
                boardId: boardId,
                row: Int32(row),
                col: Int32(col),
                rowSpan: Int32(rows),
                columnSpan: Int32(columns)
            )
            await refreshBoardCells()
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.cell_update_failed")
        }
    }

    /// Moves the Button at the source cell onto the target cell, swapping when both spans fit.
    /// False means the shared grid refused the move, so nothing was saved.
    func moveBoardField(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) async -> Bool {
        guard let boardId = selectedBoardId else { return false }
        do {
            let moved = try await boardsFacade.moveBoardField(
                boardId: boardId,
                fromRow: Int32(fromRow),
                fromCol: Int32(fromCol),
                toRow: Int32(toRow),
                toCol: Int32(toCol)
            )
            if moved.boolValue {
                await refreshBoardCells()
            }
            return moved.boolValue
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.cell_update_failed")
            return false
        }
    }

    // MARK: - Boardsets (Symbol-First)
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

    func setBoardSetSentenceCaching(id: String, enabled: Bool) {
        guard var set = boardSets.first(where: { $0.id == id }) else { return }
        set.cacheWholeSentences = enabled
        updateBoardSet(set)
        Task {
            if let updated = try? await boardsFacade.updateBoardSetSentenceCaching(id: id, enabled: enabled) {
                updateBoardSet(boardSetInfo(from: updated))
            }
        }
    }

    func normalizedBoardsetName(_ input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? NSLocalizedString("boardset.default_name", comment: "") : trimmed
    }

    func normalizedOptionalText(_ input: String?) -> String? {
        let trimmed = (input ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    func touchSelectedBoardSet(statusKey: String) {
        if var set = selectedBoardSet {
            set.updatedAt = Date().timeIntervalSince1970
            updateBoardSet(set)
        }
        boardStatusMessage = NSLocalizedString(statusKey, comment: "")
    }

    func refreshBoardNames(for set: BoardSetInfo?) async {
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
                if let board = try await boardsFacade.getBoard(id: boardId) {
                    let name = board.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    if !name.isEmpty {
                        cache[boardId] = name
                    }
                }
            } catch {
                // Keep existing cache value if lookup fails.
            }
        }

        boardNamesById = cache
    }

    /// Home-page thumbnail for the library. Hidden buttons are omitted so the
    /// preview matches the page in use.
    func loadScreenPreview(rootBoardId: String) async -> ScreenPreview? {
        do {
            guard let board = try await boardsFacade.getBoard(id: rootBoardId) else { return nil }
            let rows = max(1, Int(board.grid?.rows ?? 1))
            let columns = max(1, Int(board.grid?.columns ?? 1))
            let fields = try await boardsFacade.listBoardFieldItems(boardId: rootBoardId)
            let cells = try await boardsFacade.listBoardCells(boardId: rootBoardId)
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

    func loadBoardSets() async {
        do {
            let sharedSets = try await boardsFacade.listBoardSets()
            boardSets = sharedSets
                .map { boardSetInfo(from: $0) }
                .sorted { $0.updatedAt > $1.updatedAt }
        } catch {
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
            selectedBoard = nil
            loadedBoardCellsPageId = nil
            boardCells = []
            boardFieldItems = []
            boardNamesById = [:]
        }
    }

    func previewSpeech(name: String, text: String, paragraphsPerPage: Int) -> [SpeechImportPage] {
        (try? boardsFacade.previewSpeech(name: name, text: text, paragraphsPerPage: Int32(paragraphsPerPage))) ?? []
    }

    func importSpeech(name: String, text: String, paragraphsPerPage: Int) async -> String? {
        guard !isCreatingBoardSet else { return nil }
        isCreatingBoardSet = true
        defer { isCreatingBoardSet = false }
        do {
            let set = try await boardsFacade.importSpeech(
                name: name, text: text, paragraphsPerPage: Int32(paragraphsPerPage),
                previousLabel: NSLocalizedString("speech_import.previous", comment: ""),
                nextLabel: NSLocalizedString("speech_import.next", comment: "")
            )
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = set.rootBoardId
            await loadSelectedBoard()
            return set.id
        } catch {
            return nil
        }
    }

    /// Pulls and pushes linked Screens in the background; communication never waits on it.
    func syncRemoteScreensAutomatic(force: Bool = false) async {
        let actions = IosDiBridge().remoteScreenActions()
        actions.startAutoSync()
        do {
            let changed = try await actions.syncAllAutomatic(force: force)
            if changed.boolValue {
                await loadBoardSets()
            }
        } catch {
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
            let sharedSet = try await boardsFacade.createBoardSet(
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
            boardStatusMessage = NSLocalizedString("boardset.error.create_failed", comment: "")
        }
    }

    func createKeyboardBoardSet(name: String, preset: String) async {
        isCreatingBoardSet = true
        defer { isCreatingBoardSet = false }
        let boardsetName = normalizedBoardsetName(name)
        do {
            let sharedSet = try await boardsFacade.createKeyboardBoardSet(name: boardsetName, preset: preset)
            let set = boardSetInfo(from: sharedSet)
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = set.rootBoardId
            await loadSelectedBoard()
            boardStatusMessage = NSLocalizedString("boardset.status.created", comment: "")
        } catch {
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
            let board = try await boardsFacade.createBoard(
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
            let board = try await boardsFacade.createKeyboardBoard(
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
        loadedBoardCellsPageId = nil
        selectedBoardId = id
        await loadSelectedBoard()
    }

    var canNavigateBoardBack: Bool { !boardStack.isEmpty }

    var isOnStartPage: Bool {
        guard let root = selectedBoardSet?.rootBoardId else { return true }
        return selectedBoardId == root
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
        let behavior = boardReturnBehavior
        let result = boardsFacade.boardReturnBehavior(
            behavior: behavior,
            currentBoardId: selectedBoardId,
            boardStack: boardStack,
            rootBoardId: selectedBoardSet?.rootBoardId ?? ""
        )
        let nextBoardId = result.boardId
        let nextStack = result.boardStack
        boardStack = nextStack
        guard let nextBoardId, nextBoardId != selectedBoardId else { return }
        selectedBoardId = nextBoardId
        await loadSelectedBoard()
    }

    func nGramPredictionInsertion(sentence: String, suggestion: String) -> String {
        boardsFacade.nGramPredictionInsertion(sentence: sentence, suggestion: suggestion)
    }

    func boardButtonIsVisible(hidden: Bool, isEditMode: Bool, showHiddenButtons: Bool) -> Bool {
        boardsFacade.boardButtonIsVisible(hidden: hidden, isEditMode: isEditMode, showHiddenButtons: showHiddenButtons)
    }

    func boardFieldFontScale(rowSpan: Int, columnSpan: Int) -> CGFloat {
        CGFloat(boardsFacade.boardFieldFontScale(rowSpan: Int32(rowSpan), columnSpan: Int32(columnSpan)))
    }
}
