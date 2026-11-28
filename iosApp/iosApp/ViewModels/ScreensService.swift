import Foundation
import Shared

/// Protocol seam covering `Shared.BoardsFacade`, `Shared.PredictionFacade`, and
/// `Shared.RemoteScreenActions` so `ScreensModel` can be unit-tested without Koin or disk I/O.
protocol ScreensService {
    func listBoardSets() async throws -> [Shared.ObfBoardSet]
    func getBoard(id: String) async throws -> Shared.ObfBoard?
    func listBoardCells(boardId: String) async throws -> [Shared.IosBoardCell]
    func listBoardFieldItems(boardId: String) async throws -> [Shared.IosBoardFieldItem]
    func resolveBoardSettings(boardId: String) async throws -> Shared.IosResolvedBoardSettings
    func availableFieldSpans(boardId: String, row: Int32, col: Int32) async throws -> [Shared.IosGridFieldSpan]
    func resizeBoardField(boardId: String, row: Int32, col: Int32, rowSpan: Int32, columnSpan: Int32) async throws
    func moveBoardField(boardId: String, fromRow: Int32, fromCol: Int32, toRow: Int32, toCol: Int32) async throws -> Bool
    func updateBoardSetSentenceCaching(id: String, enabled: Bool) async throws -> Shared.ObfBoardSet
    func previewSpeech(name: String, text: String, paragraphsPerPage: Int32) throws -> [Shared.SpeechImportPage]
    func importSpeech(
        name: String,
        text: String,
        paragraphsPerPage: Int32,
        previousLabel: String,
        nextLabel: String
    ) async throws -> Shared.ObfBoardSet
    func createBoardSet(name: String, rows: Int32, columns: Int32, rootBoardName: String) async throws -> Shared.ObfBoardSet
    func createKeyboardBoardSet(name: String, preset: String) async throws -> Shared.ObfBoardSet
    func createBoard(boardSetId: String, name: String, rows: Int32, columns: Int32) async throws -> Shared.ObfBoard
    func createKeyboardBoard(
        boardSetId: String,
        name: String,
        rows: Int32,
        columns: Int32,
        layout: String
    ) async throws -> Shared.ObfBoard
    func setBoardBackgroundImage(
        boardSetId: String,
        boardId: String,
        imageUrl: String?,
        fit: String
    ) async throws -> Shared.ObfBoard
    func backgroundImageUrl(board: Shared.ObfBoard) -> String?
    func backgroundImageFit(board: Shared.ObfBoard) -> String
    func boardKeyboardLayout(board: Shared.ObfBoard) -> String?
    func boardUsesSpellingMode(board: Shared.ObfBoard) -> Bool
    func upsertBoardCellButton(
        boardId: String,
        row: Int32,
        col: Int32,
        label: String?,
        vocalization: String?,
        backgroundColor: String?,
        borderColor: String?,
        linkedBoardId: String?,
        imageUrl: String?,
        clearImage: Bool,
        actions: [String],
        wordType: String?,
        applyAppearance: Bool,
        hidden: Bool,
        shape: String?,
        language: String?,
        mathMode: Bool,
        recordingPath: String?,
        clearRecording: Bool
    ) async throws -> Shared.ObfBoard
    func clearBoardCellButton(boardId: String, row: Int32, col: Int32) async throws -> Shared.ObfBoard
    func touchBoardSet(id: String) async throws -> Shared.ObfBoardSet
    func renameBoardSet(boardSetId: String, name: String) async throws -> Shared.ObfBoardSet
    func renameBoard(boardSetId: String, boardId: String, name: String) async throws -> Shared.ObfBoard
    func resizeBoard(boardSetId: String, boardId: String, rows: Int32, columns: Int32) async throws -> Shared.ObfBoard
    func setBoardBackgroundColor(
        boardSetId: String,
        boardId: String,
        backgroundColor: String?
    ) async throws -> Shared.ObfBoard
    func boardSettingsDraft(boardSetId: String, boardId: String, forPage: Bool) async throws -> Shared.IosBoardSettingsDraft
    func updateMessageBarActions(boardSetId: String, actions: [String]) async throws
    func updateBoardSettings(
        boardSetId: String,
        boardId: String,
        forPage: Bool,
        showLabels: String,
        showSymbols: String,
        labelAtTop: String,
        showMessageBar: String,
        showSpeakButton: String,
        messageBarEditable: String,
        activationBehavior: String,
        returnBehavior: String,
        cacheSpeech: String
    ) async throws
    func setRootBoard(boardSetId: String, boardId: String) async throws -> Shared.ObfBoardSet
    func deleteBoard(boardSetId: String, boardId: String) async throws -> Shared.ObfBoardSet
    func toggleBoardSetLocked(id: String) async throws -> Shared.ObfBoardSet
    func deleteBoardSet(id: String) async throws
    func deleteRemoteScreen(screenId: String) async throws -> Shared.RemoteScreenResult
    func duplicateBoardSet(id: String) async throws -> Shared.ObfBoardSet
    func shareBoardSetAsObz(id: String) async throws -> Shared.BoardSetExportResult
    func shareBoardSetAsGridset(id: String) async throws -> Shared.BoardSetExportResult
    func syncRemoteScreensAutomatic(force: Bool) async throws -> Bool
    func updateBoardSetSpeechCacheOnline(online: Bool)
    func cacheAllBoardSetFields() async throws
    func retryBoardSetSpeechCaching() async throws
    func boardReturnBehavior(
        behavior: String,
        currentBoardId: String?,
        boardStack: [String],
        rootBoardId: String
    ) -> Shared.IosBoardReturnResult
    func nGramPredictionInsertion(sentence: String, suggestion: String) -> String
    func boardButtonIsVisible(hidden: Bool, isEditMode: Bool, showHiddenButtons: Bool) -> Bool
    func boardFieldFontScale(rowSpan: Int32, columnSpan: Int32) -> Float
    func observePredictions(
        context: String,
        maxWords: Int32,
        maxLetters: Int32,
        onChange: @escaping (Shared.PredictionUpdate) -> Void
    ) -> any CancellableSubscription
}

struct SharedScreensService: ScreensService {
    private let resolveBoards: () -> Shared.BoardsFacade
    private let resolvePredictions: () -> Shared.PredictionFacade
    private let resolveRemoteActions: () -> Shared.RemoteScreenActions

    init(
        resolveBoards: @escaping () -> Shared.BoardsFacade = { IosDiBridge().boardsFacade() },
        resolvePredictions: @escaping () -> Shared.PredictionFacade = { IosDiBridge().predictionFacade() },
        resolveRemoteActions: @escaping () -> Shared.RemoteScreenActions = { IosDiBridge().remoteScreenActions() }
    ) {
        self.resolveBoards = resolveBoards
        self.resolvePredictions = resolvePredictions
        self.resolveRemoteActions = resolveRemoteActions
    }

    func listBoardSets() async throws -> [Shared.ObfBoardSet] {
        try await resolveBoards().listBoardSets()
    }

    func getBoard(id: String) async throws -> Shared.ObfBoard? {
        try await resolveBoards().getBoard(id: id)
    }

    func listBoardCells(boardId: String) async throws -> [Shared.IosBoardCell] {
        try await resolveBoards().listBoardCells(boardId: boardId)
    }

    func listBoardFieldItems(boardId: String) async throws -> [Shared.IosBoardFieldItem] {
        try await resolveBoards().listBoardFieldItems(boardId: boardId)
    }

    func resolveBoardSettings(boardId: String) async throws -> Shared.IosResolvedBoardSettings {
        try await resolveBoards().resolveBoardSettings(boardId: boardId)
    }

    func availableFieldSpans(boardId: String, row: Int32, col: Int32) async throws -> [Shared.IosGridFieldSpan] {
        try await resolveBoards().availableFieldSpans(boardId: boardId, row: row, col: col)
    }

    func resizeBoardField(boardId: String, row: Int32, col: Int32, rowSpan: Int32, columnSpan: Int32) async throws {
        try await resolveBoards().resizeBoardField(
            boardId: boardId,
            row: row,
            col: col,
            rowSpan: rowSpan,
            columnSpan: columnSpan
        )
    }

    func moveBoardField(boardId: String, fromRow: Int32, fromCol: Int32, toRow: Int32, toCol: Int32) async throws -> Bool {
        try await resolveBoards().moveBoardField(
            boardId: boardId,
            fromRow: fromRow,
            fromCol: fromCol,
            toRow: toRow,
            toCol: toCol
        ).boolValue
    }

    func updateBoardSetSentenceCaching(id: String, enabled: Bool) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().updateBoardSetSentenceCaching(id: id, enabled: enabled)
    }

    func previewSpeech(name: String, text: String, paragraphsPerPage: Int32) throws -> [Shared.SpeechImportPage] {
        try resolveBoards().previewSpeech(name: name, text: text, paragraphsPerPage: paragraphsPerPage)
    }

    func importSpeech(
        name: String,
        text: String,
        paragraphsPerPage: Int32,
        previousLabel: String,
        nextLabel: String
    ) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().importSpeech(
            name: name,
            text: text,
            paragraphsPerPage: paragraphsPerPage,
            previousLabel: previousLabel,
            nextLabel: nextLabel
        )
    }

    func createBoardSet(name: String, rows: Int32, columns: Int32, rootBoardName: String) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().createBoardSet(name: name, rows: rows, columns: columns, rootBoardName: rootBoardName)
    }

    func createKeyboardBoardSet(name: String, preset: String) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().createKeyboardBoardSet(name: name, preset: preset)
    }

    func createBoard(boardSetId: String, name: String, rows: Int32, columns: Int32) async throws -> Shared.ObfBoard {
        try await resolveBoards().createBoard(boardSetId: boardSetId, name: name, rows: rows, columns: columns)
    }

    func createKeyboardBoard(
        boardSetId: String,
        name: String,
        rows: Int32,
        columns: Int32,
        layout: String
    ) async throws -> Shared.ObfBoard {
        try await resolveBoards().createKeyboardBoard(
            boardSetId: boardSetId,
            name: name,
            rows: rows,
            columns: columns,
            layout: layout
        )
    }

    func setBoardBackgroundImage(
        boardSetId: String,
        boardId: String,
        imageUrl: String?,
        fit: String
    ) async throws -> Shared.ObfBoard {
        try await resolveBoards().setBoardBackgroundImage(
            boardSetId: boardSetId,
            boardId: boardId,
            imageUrl: imageUrl,
            fit: fit
        )
    }

    func backgroundImageUrl(board: Shared.ObfBoard) -> String? {
        resolveBoards().backgroundImageUrl(board: board)
    }

    func backgroundImageFit(board: Shared.ObfBoard) -> String {
        resolveBoards().backgroundImageFit(board: board)
    }

    func boardKeyboardLayout(board: Shared.ObfBoard) -> String? {
        resolveBoards().boardKeyboardLayout(board: board)
    }

    func boardUsesSpellingMode(board: Shared.ObfBoard) -> Bool {
        resolveBoards().boardUsesSpellingMode(board: board)
    }

    func upsertBoardCellButton(
        boardId: String,
        row: Int32,
        col: Int32,
        label: String?,
        vocalization: String?,
        backgroundColor: String?,
        borderColor: String?,
        linkedBoardId: String?,
        imageUrl: String?,
        clearImage: Bool,
        actions: [String],
        wordType: String?,
        applyAppearance: Bool,
        hidden: Bool,
        shape: String?,
        language: String?,
        mathMode: Bool,
        recordingPath: String?,
        clearRecording: Bool
    ) async throws -> Shared.ObfBoard {
        try await resolveBoards().upsertBoardCellButton(
            boardId: boardId,
            row: row,
            col: col,
            label: label,
            vocalization: vocalization,
            backgroundColor: backgroundColor,
            borderColor: borderColor,
            linkedBoardId: linkedBoardId,
            imageUrl: imageUrl,
            clearImage: clearImage,
            actions: actions,
            wordType: wordType,
            applyAppearance: applyAppearance,
            hidden: hidden,
            shape: shape,
            language: language,
            mathMode: mathMode,
            recordingPath: recordingPath,
            clearRecording: clearRecording
        )
    }

    func clearBoardCellButton(boardId: String, row: Int32, col: Int32) async throws -> Shared.ObfBoard {
        try await resolveBoards().clearBoardCellButton(boardId: boardId, row: row, col: col)
    }

    func touchBoardSet(id: String) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().touchBoardSet(id: id)
    }

    func renameBoardSet(boardSetId: String, name: String) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().renameBoardSet(boardSetId: boardSetId, name: name)
    }

    func renameBoard(boardSetId: String, boardId: String, name: String) async throws -> Shared.ObfBoard {
        try await resolveBoards().renameBoard(boardSetId: boardSetId, boardId: boardId, name: name)
    }

    func resizeBoard(boardSetId: String, boardId: String, rows: Int32, columns: Int32) async throws -> Shared.ObfBoard {
        try await resolveBoards().resizeBoard(boardSetId: boardSetId, boardId: boardId, rows: rows, columns: columns)
    }

    func setBoardBackgroundColor(
        boardSetId: String,
        boardId: String,
        backgroundColor: String?
    ) async throws -> Shared.ObfBoard {
        try await resolveBoards().setBoardBackgroundColor(
            boardSetId: boardSetId,
            boardId: boardId,
            backgroundColor: backgroundColor
        )
    }

    func boardSettingsDraft(boardSetId: String, boardId: String, forPage: Bool) async throws -> Shared.IosBoardSettingsDraft {
        try await resolveBoards().boardSettingsDraft(boardSetId: boardSetId, boardId: boardId, forPage: forPage)
    }

    func updateMessageBarActions(boardSetId: String, actions: [String]) async throws {
        try await resolveBoards().updateMessageBarActions(boardSetId: boardSetId, actions: actions)
    }

    func updateBoardSettings(
        boardSetId: String,
        boardId: String,
        forPage: Bool,
        showLabels: String,
        showSymbols: String,
        labelAtTop: String,
        showMessageBar: String,
        showSpeakButton: String,
        messageBarEditable: String,
        activationBehavior: String,
        returnBehavior: String,
        cacheSpeech: String
    ) async throws {
        try await resolveBoards().updateBoardSettings(
            boardSetId: boardSetId,
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
            cacheSpeech: cacheSpeech
        )
    }

    func setRootBoard(boardSetId: String, boardId: String) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().setRootBoard(boardSetId: boardSetId, boardId: boardId)
    }

    func deleteBoard(boardSetId: String, boardId: String) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().deleteBoard(boardSetId: boardSetId, boardId: boardId)
    }

    func toggleBoardSetLocked(id: String) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().toggleBoardSetLocked(id: id)
    }

    func deleteBoardSet(id: String) async throws {
        try await resolveBoards().deleteBoardSet(id: id)
    }

    func deleteRemoteScreen(screenId: String) async throws -> Shared.RemoteScreenResult {
        try await resolveRemoteActions().deleteScreen(screenId: screenId, deleteRemote: true)
    }

    func duplicateBoardSet(id: String) async throws -> Shared.ObfBoardSet {
        try await resolveBoards().duplicateBoardSet(id: id)
    }

    func shareBoardSetAsObz(id: String) async throws -> Shared.BoardSetExportResult {
        try await resolveBoards().shareBoardSetAsObz(id: id)
    }

    func shareBoardSetAsGridset(id: String) async throws -> Shared.BoardSetExportResult {
        try await resolveBoards().shareBoardSetAsGridset(id: id)
    }

    func syncRemoteScreensAutomatic(force: Bool) async throws -> Bool {
        let actions = resolveRemoteActions()
        actions.startAutoSync()
        return try await actions.syncAllAutomatic(force: force).boolValue
    }

    func updateBoardSetSpeechCacheOnline(online: Bool) {
        resolveBoards().updateBoardSetSpeechCacheOnline(online: online)
    }

    func cacheAllBoardSetFields() async throws {
        try await resolveBoards().cacheAllBoardSetFields()
    }

    func retryBoardSetSpeechCaching() async throws {
        try await resolveBoards().retryBoardSetSpeechCaching()
    }

    func boardReturnBehavior(
        behavior: String,
        currentBoardId: String?,
        boardStack: [String],
        rootBoardId: String
    ) -> Shared.IosBoardReturnResult {
        resolveBoards().boardReturnBehavior(
            behavior: behavior,
            currentBoardId: currentBoardId,
            boardStack: boardStack,
            rootBoardId: rootBoardId
        )
    }

    func nGramPredictionInsertion(sentence: String, suggestion: String) -> String {
        resolveBoards().nGramPredictionInsertion(sentence: sentence, suggestion: suggestion)
    }

    func boardButtonIsVisible(hidden: Bool, isEditMode: Bool, showHiddenButtons: Bool) -> Bool {
        resolveBoards().boardButtonIsVisible(
            hidden: hidden,
            isEditMode: isEditMode,
            showHiddenButtons: showHiddenButtons
        )
    }

    func boardFieldFontScale(rowSpan: Int32, columnSpan: Int32) -> Float {
        resolveBoards().boardFieldFontScale(rowSpan: rowSpan, columnSpan: columnSpan)
    }

    func observePredictions(
        context: String,
        maxWords: Int32,
        maxLetters: Int32,
        onChange: @escaping (Shared.PredictionUpdate) -> Void
    ) -> any CancellableSubscription {
        resolvePredictions().observe(context: context, maxWords: maxWords, maxLetters: maxLetters, onChange: onChange)
    }
}
