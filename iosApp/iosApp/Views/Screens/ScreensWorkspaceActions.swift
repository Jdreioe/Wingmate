import SwiftUI
import Shared
import PhotosUI
import UIKit
import AudioToolbox

extension SymbolBoardWorkspaceView {
    /// The shared Message, as shown in the Screens message bar: each Button with its
    /// label and symbol, wherever it came from. Token ids are part indices, so deleting
    /// a token removes the matching Message part.
    var boardSentenceTokens: [SentencePhraseToken] {
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

    var boardSentenceText: String { model.input }

    var boardPredictionTaskId: String {
        let predictors = model.boardCells
            .filter { $0.actions.contains(where: { $0.lowercased().hasPrefix(":prediction") }) }
            .map(\.buttonId)
            .joined(separator: ",")
        return "\(model.selectedBoardId ?? "")|\(predictors)|\(boardSentenceText)"
    }

    func activateBoardCell(_ cell: BoardCellInfo?, pageId: String, row: Int, col: Int, sourceCellId: String) {
        // Gate every activation route (Touch Exit, tap, hold, dwell, switch, accessibility)
        // on the rendered Page still owning the active loaded cells.
        guard let cell, model.boardCellsReady(for: pageId) else { return }
        if cell.actions.isEmpty, trimmed(cell.linkedBoardId) != nil {
            if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
            AccessHaptics.perform(.confirm)
            model.pushBoardNavigationStack(pageId)
            Task { await model.activateBoardCell(cell, onPageId: pageId, checkDebounce: false) }
            return
        }
        // #118 / #405: enforce per-Page, per-Button debounce and confirm/reject haptics for every non-link Button.
        guard model.acceptActivation(targetId: model.screenButtonDebounceKey(pageId: pageId, buttonId: cell.buttonId)) else { return }
        if model.selectionSoundEnabled { AudioServicesPlaySystemSound(1104) }
        if cell.actions.isEmpty {
            let behavior = model.boardActivationBehavior
            let shouldAdd = behavior != "SpeakOnly"
            let shouldSpeak = model.shouldSpeakSelectionImmediately
            // A Button's own sound replaces its spoken text, so it is said once.
            if shouldSpeak { playCellSound(cell) }
            if shouldAdd {
                appendCellToSentenceIfNeeded(cell, pageId: pageId, sourceCellId: sourceCellId)
            }
            Task {
                if shouldSpeak && trimmed(cell.soundPath) == nil && trimmed(cell.soundDataUrl) == nil {
                    await model.activateBoardCell(cell, onPageId: pageId, checkDebounce: false)
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
                appendCellToSentenceIfNeeded(cell, pageId: pageId, textOverride: " ", sourceCellId: sourceCellId)
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
                    appendCellToSentenceIfNeeded(cell, pageId: pageId, textOverride: insertion, sourceCellId: sourceCellId)
                }
            case ":spell":
                isContentActivation = true
                if let text = trimmed(cell.vocalization) ?? trimmed(cell.label) {
                    appendCellToSentenceIfNeeded(cell, pageId: pageId, textOverride: text, sourceCellId: sourceCellId)
                }
            default:
                if action.hasPrefix("+") {
                    isContentActivation = true
                    appendCellToSentenceIfNeeded(cell, pageId: pageId, textOverride: String(action.dropFirst()), sourceCellId: sourceCellId)
                }
            }
        }
        if isContentActivation {
            playCellSound(cell)
            Task { await model.activateBoardSelectionHighlight(buttonId: cell.buttonId) }
            Task { await model.applyBoardReturnBehavior() }
        }
    }

    func appendCellToSentenceIfNeeded(_ cell: BoardCellInfo?, pageId: String, textOverride: String? = nil, sourceCellId: String? = nil) {
        guard let cell else { return }
        let text = textOverride ?? trimmed(cell.vocalization) ?? trimmed(cell.label)
        guard let text, !text.isEmpty else { return }

        // The new token first renders on top of the tapped cell, then flies to its
        // place in the message bar on the very next frame.
        var placement = Transaction()
        placement.disablesAnimations = true
        var tokenId = ""
        withTransaction(placement) {
            model.appendScreenPart(text: text, buttonId: cell.buttonId, pageId: pageId, languageTag: cell.language, mathMode: cell.mathMode)
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

    func playCellSound(_ cell: BoardCellInfo) {
        if let path = trimmed(cell.soundPath) {
            model.playBoardButtonFile(path)
        } else if let sound = trimmed(cell.soundDataUrl) {
            model.playBoardButtonSound(sound)
        }
    }

    func backspaceBoardSentence() {
        model.removeLastMessagePart()
    }

    func predictionInsertion(sentence: String, suggestion: String) -> String {
        model.nGramPredictionInsertion(sentence: sentence, suggestion: suggestion)
    }

    func boardCellDisplayLabel(_ cell: BoardCellInfo?) -> String {
        guard let cell else { return "" }
        if cell.actions.contains(where: { $0.lowercased() == ":prediction" || $0.lowercased() == ":predictions" }),
           let prediction = model.boardPrediction(for: cell.buttonId) {
            return prediction
        }
        if let expanded = ScreenButtonText.expandedText(label: cell.label, vocalization: cell.vocalization) {
            return expanded
        }
        return trimmed(cell.label) ?? trimmed(cell.vocalization) ?? ""
    }

    /// Resolves the spoken auditory-fishing preview for a Button.
    /// Live prediction suggestions always win over stored labels/vocalizations; empty prediction slots stay silent.
    func boardCellPreviewText(_ cell: BoardCellInfo?) -> String? {
        guard let cell else { return nil }
        let isPrediction = cell.actions.contains {
            let lower = $0.lowercased()
            return lower == ":prediction" || lower == ":predictions"
        }
        if isPrediction {
            return trimmed(model.boardPrediction(for: cell.buttonId))
        }
        return trimmed(cell.vocalization) ?? trimmed(boardCellDisplayLabel(cell))
    }

    func boardCellAccessibilityHint(_ cell: BoardCellInfo?) -> String {
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

    func moveEditingAction(at index: Int, by offset: Int) {
        let target = index + offset
        guard editingActions.indices.contains(index), editingActions.indices.contains(target) else { return }
        editingActions.swapAt(index, target)
    }

    func boardKeyActionLabel(_ rawAction: String) -> String {
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

    var currentEditingSpan: GridFieldSpanInfo {
        if case .custom(let span) = editingSpan { return span }
        return GridFieldSpanInfo(rows: 1, columns: 1)
    }

    func spanIsAllowed(rows: Int, columns: Int) -> Bool {
        rows >= 1 && columns >= 1 && editingSpanOptions.contains(GridFieldSpanInfo(rows: rows, columns: columns))
    }

    // MARK: - Editor Setup, Media, Lock & Export

    func openCellEditor(row: Int, col: Int, existing: BoardCellInfo?) {
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

    func trimmed(_ value: String?) -> String? {
        let text = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    func colorFromHex(_ hex: String?, fallback: Color) -> Color {
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

    func contrastingColor(for hex: String?, fallback: Color) -> Color {
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

    func hexFromColor(_ color: Color) -> String {
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

    func searchCellOpenSymbols() async {
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

    func importCellPhotoItem(_ item: PhotosPickerItem) async {
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

    func importCellImageFile(_ sourceUrl: URL) async {
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

    func persistCellImageData(_ data: Data, preferredExtension: String) throws -> String {
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
    func beginScreenLockToggle(_ set: BoardSetInfo) async {
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
    func finishEditingAccessSuccess() async {
        if pendingRemoteScreen {
            // Present remote storage once the PIN sheet has finished dismissing.
            pendingRemoteScreen = false
            openRemoteAfterUnlock = true
            return
        }
        if let lockId = pendingBoardLockId {
            let unlocks = pendingBoardLockUnlocks
            pendingBoardLockId = nil
            await model.selectBoardSet(id: lockId)
            model.setSelectedBoardSetLocked(!unlocks)
            return
        }
        if let deleteId = pendingDeleteBoardSetId {
            pendingDeleteBoardSetId = nil
            await model.deleteBoardSet(id: deleteId, deleteRemote: pendingDeleteRemote)
            pendingDeleteRemote = false
        } else if let id = pendingEditingBoardSetId {
            pendingEditingBoardSetId = nil
            route = .workspace(boardSetId: id, mode: .edit)
        }
    }

    /// Deleting is protected by Editing access; [deleteRemote] also trashes the Screen's cloud file.
    func confirmDeleteScreen(_ target: BoardSetInfo, deleteRemote: Bool) {
        Task {
            if await model.editingIsAuthorized() {
                await model.deleteBoardSet(id: target.id, deleteRemote: deleteRemote)
            } else {
                pendingDeleteBoardSetId = target.id
                pendingDeleteRemote = deleteRemote
                pendingEditingBoardSetId = nil
                pendingBoardLockId = nil
                pinMode = .unlock
                showEditingAccessSheet = true
            }
            deleteTargetSet = nil
        }
    }

    /// Remote storage (linking, opening, conflicts) is protected by Editing access.
    func manageRemoteScreen(id: String?) {
        Task {
            remoteScreenId = id
            if await model.editingIsAuthorized() {
                showRemoteScreen = true
            } else {
                pendingEditingBoardSetId = nil
                pendingDeleteBoardSetId = nil
                pendingBoardLockId = nil
                pendingRemoteScreen = true
                pinMode = .unlock
                showEditingAccessSheet = true
            }
        }
    }

    /// An iCloud share link was tapped: show the Screen if it is already here, otherwise offer to open it.
    func openAcceptedShare(_ share: AcceptedCloudKitScreenShare) {
        Task {
            await model.loadBoardSets()
            if let existing = model.boardSets.first(where: { $0.remoteLocation == share.location }) {
                cloudShares.pendingShare = nil
                await model.selectBoardSet(id: existing.id)
                route = .workspace(boardSetId: existing.id, mode: .run)
                await model.syncRemoteScreensAutomatic(force: true)
            } else if !showRemoteScreen {
                manageRemoteScreen(id: nil)
            }
        }
    }

    @MainActor
    func requestEnterEditing(boardSetId: String) async {
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

    func printScreen(id: String) {
        guard !exportingPdf else { return }
        exportingPdf = true
        model.boardStatusMessage = nil
        Task {
            defer { exportingPdf = false }
            do { pdfUrl = try await exportScreenPdf(id: id) }
            catch { model.boardStatusMessage = NSLocalizedString("boardset.print_failed", comment: "") }
        }
    }

    func exportBoardSet(id: String) async {
        let result = try? await IosDiBridge().boardsFacade().shareBoardSetAsObz(id: id)
        await finishExport(result)
    }

    func exportBoardSetAsGridset(id: String) async {
        let result = try? await IosDiBridge().boardsFacade().shareBoardSetAsGridset(id: id)
        await finishExport(result)
    }

    func finishExport(_ result: BoardSetExportResult?) async {
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
