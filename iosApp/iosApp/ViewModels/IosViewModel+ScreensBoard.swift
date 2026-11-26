import Foundation
import Shared

extension IosViewModel {
    func setPageBackground(imageUrl: String?, fit: String) async {
        guard canEditSelectedBoardSet, await editingIsAuthorized(),
              let setId = selectedBoardSetId, let pageId = selectedBoardId else { return }
        do {
            _ = try await boardsFacade.setBoardBackgroundImage(boardSetId: setId, boardId: pageId, imageUrl: imageUrl, fit: fit)
            await loadSelectedBoard()
            boardStatusMessage = nil
        } catch { boardStatusMessage = NSLocalizedString("boardset.background_failed", comment: "") }
    }

    func swipePage(by offset: Int, touchExitOwnsGrid: Bool? = nil) async {
        let touchExitBlocksSwipe = touchExitOwnsGrid ?? (
            selectOnTouchExit &&
            selectedBoardId.map { boardCellsReady(for: $0) } == true &&
            boardCells.contains { !$0.hidden }
        )
        guard TouchExitControllerKt.pageSwipeIsActive(
                isEditMode: false,
                swipeBetweenPages: swipeBetweenPages,
                holdToSelectMillis: Int64(holdToSelectMillis),
                selectOnTouchDown: selectOnTouchDown,
                selectOnTouchExit: touchExitBlocksSwipe,
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
        registerAccessTarget(targetId, handlesHaptics: true) { [weak self] in
            guard let self,
                  self.boardCellsReady(for: pageId),
                  let current = self.cellAt(row: row, col: col),
                  current.buttonId == buttonId else { return }
            action(current)
        }
    }

    func loadSelectedBoard() async {
        loadedBoardCellsPageId = nil
        guard let id = selectedBoardId else {
            selectedBoard = nil
            boardCells = []
            boardFieldItems = []
            selectedBoardKeyboardLayout = nil
            selectedBoardUsesSpellingMode = false
            boardPredictionsByButtonId = [:]
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
            return
        }
        do {
            let loadedBoard = try await boardsFacade.getBoard(id: id)
            guard selectedBoardId == id else { return }
            selectedBoard = loadedBoard
            boardBackgroundImageUrl = selectedBoard.flatMap { boardsFacade.backgroundImageUrl(board: $0) }
            boardBackgroundImageFit = selectedBoard.map { boardsFacade.backgroundImageFit(board: $0) } ?? "Fill"
            await refreshSelectedBoardMetadata()
            let resolved = try? await boardsFacade.resolveBoardSettings(boardId: id)
            guard selectedBoardId == id else { return }
            resolvedBoardShowLabels = resolved?.showLabels
            resolvedBoardShowSymbols = resolved?.showSymbols
            resolvedBoardLabelAtTop = resolved?.labelAtTop
            resolvedBoardShowMessageBar = resolved?.showMessageBar
            resolvedBoardShowSpeakButton = resolved?.showSpeakButton
            resolvedBoardMessageBarActions = resolved?.messageBarActions
            resolvedBoardActivationBehavior = resolved?.activationBehavior
            resolvedBoardSpeechPolicy = resolved?.speechPolicy
            resolvedBoardReturnBehavior = resolved?.returnBehavior
            resolvedBoardCellGapDp = resolved?.cellGapDp?.intValue
            resolvedBoardOuterMarginDp = resolved?.outerMarginDp?.intValue
            let boardName = selectedBoard?.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !boardName.isEmpty {
                boardNamesById[id] = boardName
            }
            await refreshBoardCells()
        } catch {
            guard selectedBoardId == id else { return }
            selectedBoard = nil
            loadedBoardCellsPageId = nil
            boardCells = []
            boardFieldItems = []
            selectedBoardKeyboardLayout = nil
            selectedBoardUsesSpellingMode = false
            boardPredictionsByButtonId = [:]
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

    func refreshSelectedBoardMetadata() async {
        guard let board = selectedBoard else {
            selectedBoardKeyboardLayout = nil
            selectedBoardUsesSpellingMode = false
            return
        }
        selectedBoardKeyboardLayout = boardsFacade.boardKeyboardLayout(board: board)
        selectedBoardUsesSpellingMode = boardsFacade.boardUsesSpellingMode(board: board)
    }

    func refreshBoardCells() async {
        guard let boardId = selectedBoardId else {
            loadedBoardCellsPageId = nil
            boardCells = []
            boardFieldItems = []
            return
        }

        do {
            let cells = try await boardsFacade.listBoardCells(boardId: boardId)
            let fields = try await boardsFacade.listBoardFieldItems(boardId: boardId)
            guard selectedBoardId == boardId else { return }
            boardCells = cells.map { cell in
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
            boardFieldItems = fields.map { field in
                BoardFieldItem(
                    row: Int(field.row),
                    column: Int(field.column),
                    rowSpan: Int(field.rowSpan),
                    columnSpan: Int(field.columnSpan),
                    buttonId: field.buttonId
                )
            }
            loadedBoardCellsPageId = boardId
            rememberScreenButtonLooks()
        } catch {
            guard selectedBoardId == boardId else { return }
            loadedBoardCellsPageId = nil
            boardCells = []
            boardFieldItems = []
        }
    }

    func upsertSelectedBoardCell(
        row: Int,
        col: Int,
        label: String?,
        vocalization: String?,
        backgroundColor: String?,
        borderColor: String?,
        linkedBoardId: String?,
        imageUrl: String?,
        clearImage: Bool,
        actions: [String],
        wordType: String?,
        hidden: Bool = false,
        shape: String? = nil,
        language: String? = nil,
        mathMode: Bool = false,
        recordingPath: String? = nil,
        clearRecording: Bool = false
    ) async {
        guard let boardId = selectedBoardId else {
            boardStatusMessage = NSLocalizedString("boardset.error.no_board", comment: "")
            return
        }
        guard canEditSelectedBoardSet else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }

        let normalizedLabel = normalizedOptionalText(label)
        let normalizedVocalization = normalizedOptionalText(vocalization)
        let normalizedBackground = normalizedOptionalText(backgroundColor)
        let normalizedBorder = normalizedOptionalText(borderColor)
        let normalizedLink = normalizedOptionalText(linkedBoardId)
        let normalizedImageUrl = normalizedOptionalText(imageUrl)

        do {
            let updatedBoard = try await boardsFacade.upsertBoardCellButton(
                boardId: boardId,
                row: Int32(row),
                col: Int32(col),
                label: normalizedLabel,
                vocalization: normalizedVocalization,
                backgroundColor: normalizedBackground,
                borderColor: normalizedBorder,
                linkedBoardId: normalizedLink,
                imageUrl: normalizedImageUrl,
                clearImage: clearImage,
                actions: actions,
                wordType: normalizedOptionalText(wordType),
                applyAppearance: true,
                hidden: hidden,
                shape: shape,
                language: normalizedOptionalText(language),
                mathMode: mathMode,
                recordingPath: normalizedOptionalText(recordingPath),
                clearRecording: clearRecording
            )

            selectedBoard = updatedBoard
            await refreshBoardCells()
            if let setId = selectedBoardSetId { _ = try? await boardsFacade.touchBoardSet(id: setId) }
            touchSelectedBoardSet(statusKey: "boardset.status.cell_saved")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.cell_update_failed")
        }
    }

    func clearSelectedBoardCell(row: Int, col: Int) async {
        guard let boardId = selectedBoardId else {
            boardStatusMessage = NSLocalizedString("boardset.error.no_board", comment: "")
            return
        }
        guard canEditSelectedBoardSet else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }

        do {
            let updatedBoard = try await boardsFacade.clearBoardCellButton(
                boardId: boardId,
                row: Int32(row),
                col: Int32(col)
            )

            selectedBoard = updatedBoard
            await refreshBoardCells()
            if let setId = selectedBoardSetId { _ = try? await boardsFacade.touchBoardSet(id: setId) }
            touchSelectedBoardSet(statusKey: "boardset.status.cell_cleared")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.cell_clear_failed")
        }
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
        if checkDebounce && !acceptActivation(targetId: screenButtonDebounceKey(pageId: pageId, buttonId: cell.buttonId)) {
            return
        }

        if let textToSpeak = normalizedOptionalText(cell.vocalization) ?? normalizedOptionalText(cell.label) {
            speak(textToSpeak)
        }
    }

    func activateBoardSelectionHighlight(buttonId: String) async {
        guard selectionHighlightMillis > 0 else { return }
        selectionHighlight.activate(targetId: buttonId, nowMillis: Int64(ProcessInfo.processInfo.systemUptime * 1000))
        selectionHighlightGeneration += 1
        let generation = selectionHighlightGeneration
        highlightedButtonId = buttonId
        let duration = selectionHighlightMillis
        try? await Task.sleep(nanoseconds: UInt64(duration) * 1_000_000)
        guard generation == selectionHighlightGeneration else { return }
        highlightedButtonId = nil
    }

    // #118 / #405: shared per-target activation debounce paired with UIKit confirm/reject haptics.
    func acceptActivation(targetId: String) -> Bool {
        let accepted = selectionDebouncer.tryActivate(
            targetId: targetId,
            nowMillis: nowMillis(),
            debounceMillis: Int64(selectionDebounceMillis)
        )
        AccessHaptics.perform(accepted ? .confirm : .reject)
        return accepted
    }

    func refreshBoardPredictions(context: String) async {
        stopBoardPredictions()
        var seenIds = Set<String>()
        let predictorIds = boardCells
            .filter { cell in cell.actions.contains { $0.lowercased() == ":prediction" || $0.lowercased() == ":predictions" } }
            .map(\.buttonId)
            .filter { seenIds.insert($0).inserted }
        guard !predictorIds.isEmpty else { return }
        boardPredictionSubscription = predictionFacade.observe(
            context: context, maxWords: Int32(predictorIds.count), maxLetters: 0
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

    func renameSelectedBoardSet(_ name: String) async {
        guard let set = selectedBoardSet, canEditSelectedBoardSet else { return }
        let normalized = normalizedBoardsetName(name)
        do {
            let updated = try await boardsFacade.renameBoardSet(boardSetId: set.id, name: normalized)
            await loadBoardSets()
            selectedBoardSetId = updated.id
            boardStatusMessage = NSLocalizedString("boardset.status.saved", comment: "")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
        }
    }

    func renameSelectedBoard(_ name: String) async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId, canEditSelectedBoardSet else { return }
        let normalized = normalizedBoardsetName(name)
        do {
            let board = try await boardsFacade.renameBoard(boardSetId: set.id, boardId: boardId, name: normalized)
            selectedBoard = board
            boardNamesById[boardId] = normalized
            await loadBoardSets()
            selectedBoardSetId = set.id
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
        }
    }

    func resizeSelectedBoard(rows: Int, columns: Int) async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId, canEditSelectedBoardSet else { return }
        do {
            let board = try await boardsFacade.resizeBoard(
                boardSetId: set.id,
                boardId: boardId,
                rows: Int32(min(max(rows, 1), 12)),
                columns: Int32(min(max(columns, 1), 12))
            )
            selectedBoard = board
            await refreshBoardCells()
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = boardId
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
        }
    }

    func setSelectedBoardBackgroundColor(_ color: String?) async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId, canEditSelectedBoardSet else { return }
        do {
            let board = try await boardsFacade.setBoardBackgroundColor(
                boardSetId: set.id,
                boardId: boardId,
                backgroundColor: normalizedOptionalText(color)
            )
            selectedBoard = board
            await loadBoardSets()
            selectedBoardSetId = set.id
            selectedBoardId = boardId
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
        }
    }

    func loadBoardSettings(forPage: Bool) async -> Shared.IosBoardSettingsDraft? {
        guard let setId = selectedBoardSetId, let boardId = selectedBoardId else { return nil }
        return try? await boardsFacade.boardSettingsDraft(boardSetId: setId, boardId: boardId, forPage: forPage)
    }

    /// Saves the selected Screen's message bar buttons, in order.
    func setMessageBarActions(_ actions: [String]) async {
        guard let setId = selectedBoardSetId, canEditSelectedBoardSet else { return }
        do {
            try await boardsFacade.updateMessageBarActions(boardSetId: setId, actions: actions)
            await loadSelectedBoard()
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
        }
    }

    func saveBoardSettings(
        forPage: Bool,
        showLabels: String,
        showSymbols: String,
        labelAtTop: String,
        showMessageBar: String,
        showSpeakButton: String,
        messageBarEditable: String,
        activationBehavior: String,
        returnBehavior: String,
        cacheSpeech: String,
        cellGapDp: Int? = nil,
        outerMarginDp: Int? = nil
    ) async -> Bool {
        guard let setId = selectedBoardSetId, let boardId = selectedBoardId, canEditSelectedBoardSet else { return false }
        do {
            try await boardsFacade.updateBoardSettings(
                boardSetId: setId,
                boardId: boardId,
                forPage: forPage,
                showLabels: showLabels,
                showSymbols: showSymbols,
                labelAtTop: labelAtTop,
                showMessageBar: showMessageBar,
                showSpeakButton: showSpeakButton,
                messageBarEditable: messageBarEditable,
                activationBehavior: activationBehavior,
                returnBehavior: returnBehavior,
                cacheSpeech: cacheSpeech,
                cellGapDp: cellGapDp.map { KotlinInt(value: Int32($0)) },
                outerMarginDp: outerMarginDp.map { KotlinInt(value: Int32($0)) }
            )
            await loadSelectedBoard()
            return true
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
            return false
        }
    }

    func makeSelectedBoardRoot() async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId, boardId != set.rootBoardId else { return }
        do {
            let updated = try await boardsFacade.setRootBoard(boardSetId: set.id, boardId: boardId)
            await loadBoardSets()
            selectedBoardSetId = updated.id
            selectedBoardId = boardId
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
        }
    }

    func deleteSelectedBoard() async {
        guard let set = selectedBoardSet, let boardId = selectedBoardId,
              boardId != set.rootBoardId, set.boardIds.count > 1 else { return }
        do {
            let updated = try await boardsFacade.deleteBoard(boardSetId: set.id, boardId: boardId)
            await loadBoardSets()
            selectedBoardSetId = updated.id
            selectedBoardId = updated.rootBoardId
            await loadSelectedBoard()
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.delete_board_failed")
        }
    }

    func setSelectedBoardSetLocked(_ locked: Bool) {
        guard let set = selectedBoardSet, set.isLocked != locked else { return }
        Task {
            do {
                _ = try await boardsFacade.toggleBoardSetLocked(id: set.id)
                await loadBoardSets()
                selectedBoardSetId = set.id
                boardStatusMessage = locked
                    ? NSLocalizedString("boardset.status.locked", comment: "")
                    : NSLocalizedString("boardset.status.unlocked", comment: "")
            } catch {
                await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
            }
        }
    }

    /// Deletes a Screen; with `deleteRemote` its Google Drive / OneDrive file goes to that trash too.
    func deleteBoardSet(id: String, deleteRemote: Bool = false) async {
        do {
            if deleteRemote {
                let result = try await IosDiBridge().remoteScreenActions().deleteScreen(screenId: id, deleteRemote: true)
                guard result.status == "ok" else {
                    boardStatusMessage = result.message
                    return
                }
            } else {
                try await boardsFacade.deleteBoardSet(id: id)
            }
            await loadBoardSets()
            if selectedBoardSetId == id {
                selectedBoardSetId = boardSets.first?.id
                if let set = selectedBoardSet {
                    selectedBoardId = set.rootBoardId
                    await loadSelectedBoard()
                } else {
                    selectedBoardId = nil
                    selectedBoard = nil
                    loadedBoardCellsPageId = nil
                    boardCells = []
                    boardFieldItems = []
                }
            }
            boardStatusMessage = NSLocalizedString("board_sets.status.deleted", comment: "")
        } catch {
            await reportBoardFailure(error, messageKey: "board_sets.error.delete_failed")
        }
    }

    func duplicateBoardSet(id: String) async {
        do {
            let dup = try await boardsFacade.duplicateBoardSet(id: id)
            let info = boardSetInfo(from: dup)
            await loadBoardSets()
            selectedBoardSetId = info.id
            selectedBoardId = info.rootBoardId
            await loadSelectedBoard()
            boardStatusMessage = NSLocalizedString("board_sets.status.duplicated", comment: "")
        } catch {
            await reportBoardFailure(error, messageKey: "board_sets.error.duplicate_failed")
        }
    }

    /// Shows why a Screen edit failed. A Screen or Page that no longer exists reloads the list instead.
    func reportBoardFailure(_ error: Error, messageKey: String) async {
        if error.isNativeCancellation { return }
        if error.nativeFailure?.kind == NativeFailureKind.notfound {
            await loadBoardSets()
            boardStatusMessage = NSLocalizedString("boardset.error.not_found", comment: "")
            return
        }
        boardStatusMessage = NSLocalizedString(messageKey, comment: "")
    }
}
