import Foundation
import Shared

extension ScreensModel {
    // MARK: - Grid & Button Editing

    func availableFieldSpanOptions(row: Int, col: Int) async -> [GridFieldSpanInfo] {
        guard let boardId = selectedBoardId else { return [] }
        do {
            let spans = try await service.availableFieldSpans(boardId: boardId, row: Int32(row), col: Int32(col))
            return spans.map { GridFieldSpanInfo(rows: Int($0.rows), columns: Int($0.columns)) }
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.cell_update_failed")
            return []
        }
    }

    func resizeSelectedBoardField(row: Int, col: Int, rows: Int, columns: Int) async {
        guard let boardId = selectedBoardId else { return }
        do {
            try await service.resizeBoardField(
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
            let moved = try await service.moveBoardField(
                boardId: boardId,
                fromRow: Int32(fromRow),
                fromCol: Int32(fromCol),
                toRow: Int32(toRow),
                toCol: Int32(toCol)
            )
            if moved {
                await refreshBoardCells()
            }
            return moved
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.cell_update_failed")
            return false
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

        do {
            _ = try await service.upsertBoardCellButton(
                boardId: boardId,
                row: Int32(row),
                col: Int32(col),
                label: normalizedOptionalText(label),
                vocalization: normalizedOptionalText(vocalization),
                backgroundColor: normalizedOptionalText(backgroundColor),
                borderColor: normalizedOptionalText(borderColor),
                linkedBoardId: normalizedOptionalText(linkedBoardId),
                imageUrl: normalizedOptionalText(imageUrl),
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

            // refreshBoardCells publishes the board with its cells from one snapshot.
            await refreshBoardCells()
            await touchBoardSetIfSelected()
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
            _ = try await service.clearBoardCellButton(
                boardId: boardId,
                row: Int32(row),
                col: Int32(col)
            )
            // refreshBoardCells publishes the board with its cells from one snapshot.
            await refreshBoardCells()
            await touchBoardSetIfSelected()
            touchSelectedBoardSet(statusKey: "boardset.status.cell_cleared")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.cell_clear_failed")
        }
    }

    func toggleButtonHidden(buttonId: String) async {
        guard let setId = selectedBoardSetId, let boardId = selectedBoardId else {
            boardStatusMessage = NSLocalizedString("boardset.error.no_board", comment: "")
            return
        }
        guard canEditSelectedBoardSet else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }
        guard await editingAccess.isAuthorized() else { return }
        do {
            _ = try await service.toggleButtonHidden(boardSetId: setId, boardId: boardId, buttonId: buttonId)
            await refreshBoardCells()
            touchSelectedBoardSet(statusKey: "boardset.status.saved")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.cell_update_failed")
        }
    }

    func setSelectedPageButtonsHidden(_ hidden: Bool) async {
        guard let setId = selectedBoardSetId, let boardId = selectedBoardId else {
            boardStatusMessage = NSLocalizedString("boardset.error.no_board", comment: "")
            return
        }
        guard canEditSelectedBoardSet else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }
        guard await editingAccess.isAuthorized() else { return }
        do {
            _ = try await service.setPageButtonsHidden(boardSetId: setId, boardId: boardId, hidden: hidden)
            await refreshBoardCells()
            touchSelectedBoardSet(statusKey: "boardset.status.saved")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
        }
    }

    func setSelectedScreenButtonsHidden(_ hidden: Bool) async {
        guard let setId = selectedBoardSetId else {
            boardStatusMessage = NSLocalizedString("boardset.error.no_board", comment: "")
            return
        }
        guard canEditSelectedBoardSet else {
            boardStatusMessage = NSLocalizedString("boardset.error.locked", comment: "")
            return
        }
        guard await editingAccess.isAuthorized() else { return }
        do {
            _ = try await service.setScreenButtonsHidden(boardSetId: setId, hidden: hidden)
            await refreshBoardCells()
            touchSelectedBoardSet(statusKey: "boardset.status.saved")
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.save_failed")
        }
    }

    // MARK: - Screen & Page Management

    func setPageBackground(imageUrl: String?, fit: String) async {
        guard canEditSelectedBoardSet, await editingAccess.isAuthorized(),
              let setId = selectedBoardSetId, let pageId = selectedBoardId else { return }
        do {
            _ = try await service.setBoardBackgroundImage(
                boardSetId: setId,
                boardId: pageId,
                imageUrl: imageUrl,
                fit: fit
            )
            await loadSelectedBoard()
            boardStatusMessage = nil
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            boardStatusMessage = NSLocalizedString("boardset.background_failed", comment: "")
        }
    }

    @discardableResult
    func setBoardSetSentenceCaching(id: String, enabled: Bool) -> Task<Void, Never> {
        guard var set = boardSets.first(where: { $0.id == id }) else {
            return Task {}
        }
        let previous = set.cacheWholeSentences
        set.cacheWholeSentences = enabled
        updateBoardSet(set)

        sentenceCachingTasks[id]?.cancel()
        let task = Task { [weak self] in
            guard let self else { return }
            do {
                let updated = try await self.service.updateBoardSetSentenceCaching(id: id, enabled: enabled)
                self.updateBoardSet(self.boardSetInfo(from: updated))
            } catch {
                if error.isNativeCancellation || Task.isCancelled { return }
                if var rollbackSet = self.boardSets.first(where: { $0.id == id }) {
                    rollbackSet.cacheWholeSentences = previous
                    self.updateBoardSet(rollbackSet)
                }
                await self.reportBoardFailure(error, messageKey: "boardset.error.save_failed")
            }
        }
        sentenceCachingTasks[id] = task
        return task
    }

    func renameSelectedBoardSet(_ name: String) async {
        guard let set = selectedBoardSet, canEditSelectedBoardSet else { return }
        let normalized = normalizedBoardsetName(name)
        do {
            let updated = try await service.renameBoardSet(boardSetId: set.id, name: normalized)
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
            let board = try await service.renameBoard(boardSetId: set.id, boardId: boardId, name: normalized)
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
            _ = try await service.resizeBoard(
                boardSetId: set.id,
                boardId: boardId,
                rows: Int32(min(max(rows, 1), 12)),
                columns: Int32(min(max(columns, 1), 12))
            )
            // refreshBoardCells publishes the resized board with its cells from one snapshot,
            // so old fields never render against the new dimensions.
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
            let board = try await service.setBoardBackgroundColor(
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
        do {
            return try await service.boardSettingsDraft(boardSetId: setId, boardId: boardId, forPage: forPage)
        } catch {
            await reportBoardFailure(error, messageKey: "board_sets.error.load_failed")
            return nil
        }
    }

    /// Saves the selected Screen's message bar buttons, in order.
    func setMessageBarActions(_ actions: [String]) async {
        guard let setId = selectedBoardSetId, canEditSelectedBoardSet else { return }
        do {
            try await service.updateMessageBarActions(boardSetId: setId, actions: actions)
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
            try await service.updateBoardSettings(
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
                cellGapDp: cellGapDp.map { Int32($0) },
                outerMarginDp: outerMarginDp.map { Int32($0) }
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
            let updated = try await service.setRootBoard(boardSetId: set.id, boardId: boardId)
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
            let updated = try await service.deleteBoard(boardSetId: set.id, boardId: boardId)
            await loadBoardSets()
            selectedBoardSetId = updated.id
            selectedBoardId = updated.rootBoardId
            await loadSelectedBoard()
        } catch {
            await reportBoardFailure(error, messageKey: "boardset.error.delete_board_failed")
        }
    }

    @discardableResult
    func setSelectedBoardSetLocked(_ locked: Bool) -> Task<Void, Never> {
        guard let set = selectedBoardSet, set.isLocked != locked else { return Task {} }
        lockToggleTask?.cancel()
        let task = Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await self.service.toggleBoardSetLocked(id: set.id)
                await self.loadBoardSets()
                self.selectedBoardSetId = set.id
                self.boardStatusMessage = locked
                    ? NSLocalizedString("boardset.status.locked", comment: "")
                    : NSLocalizedString("boardset.status.unlocked", comment: "")
            } catch {
                await self.reportBoardFailure(error, messageKey: "boardset.error.save_failed")
            }
        }
        lockToggleTask = task
        return task
    }

    /// Deletes a Screen; with `deleteRemote` its Google Drive / OneDrive file goes to that trash too.
    func deleteBoardSet(id: String, deleteRemote: Bool = false) async {
        do {
            if deleteRemote {
                let result = try await service.deleteRemoteScreen(screenId: id)
                guard result.status == "ok" else {
                    boardStatusMessage = result.message
                    return
                }
            } else {
                try await service.deleteBoardSet(id: id)
            }
            await loadBoardSets()
            if selectedBoardSetId == id {
                selectedBoardSetId = boardSets.first?.id
                if let set = selectedBoardSet {
                    selectedBoardId = set.rootBoardId
                    await loadSelectedBoard()
                } else {
                    selectedBoardId = nil
                    clearSelectedBoardState()
                }
            }
            boardStatusMessage = NSLocalizedString("board_sets.status.deleted", comment: "")
        } catch {
            await reportBoardFailure(error, messageKey: "board_sets.error.delete_failed")
        }
    }

    func duplicateBoardSet(id: String) async {
        do {
            let dup = try await service.duplicateBoardSet(id: id)
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

    func exportBoardSetAsObz(id: String) async {
        do {
            let result = try await service.shareBoardSetAsObz(id: id)
            applyExportResult(result)
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            boardStatusMessage = NSLocalizedString("boardset.export_obz_failed", comment: "")
        }
    }

    func exportBoardSetAsGridset(id: String) async {
        do {
            let result = try await service.shareBoardSetAsGridset(id: id)
            applyExportResult(result)
        } catch {
            if error.isNativeCancellation || Task.isCancelled { return }
            boardStatusMessage = NSLocalizedString("boardset.export_obz_failed", comment: "")
        }
    }
}
