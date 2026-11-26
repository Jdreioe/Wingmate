import Foundation
import Shared
import UIKit

extension IosViewModel {
    // MARK: - Message (shared Communication session)

    /// Mirrors the session. Runs for every change, including ones made by Kotlin
    /// (speech progress, restore) and by the other workspace.
    func applySessionState(_ state: Shared.NativeCommunicationState) {
        let text = state.activeMessage.displayText
        let textChanged = input != text
        if textChanged { input = text }
        inputSelectionRange = clampedSelectionRange(inputSelectionRange, maxLength: (text as NSString).length)
        let secondary = secondaryLanguage
        let ranges = state.activeMessage.languageSpans
            .filter { $0.languageTag == secondary }
            .map { NSRange(location: Int($0.range.start), length: Int($0.range.length)) }
        if ranges != secondaryLanguageRanges { secondaryLanguageRanges = ranges }
        if messageParts != state.activeMessage.parts {
            messageParts = state.activeMessage.parts
            resolveScreenButtonLooks(for: state.activeMessage.parts + (state.heldMessage?.parts ?? []))
        }
        if heldMessageText != state.heldMessage?.displayText { heldMessageText = state.heldMessage?.displayText }
        if playback != state.playback {
            // A finished Message may have just entered History.
            let finished = state.playback.name == "Idle"
            playback = state.playback
            if finished && historyVisible { Task { await loadHistory() } }
        }
        if sessionNotice != state.notice { sessionNotice = state.notice }
        if sessionIsSaving != state.isSaving { sessionIsSaving = state.isSaving }
        if messageLoaded != state.isLoaded { messageLoaded = state.isLoaded }
        if textChanged { refreshPredictions(for: text) }
    }

    /// Sends one edit to the session and mirrors the result right away, so the text
    /// field never shows a stale Message between an edit and its observation.
    func editMessage(_ edit: () -> Void) {
        guard messageLoaded else {
            // Undo the field's own change; the saved Message is still loading.
            applySessionState(session.state())
            return
        }
        edit()
        applySessionState(session.state())
    }

    /// Applies the Message field's new text. Untouched Phrase and Button parts keep
    /// their recordings and sources.
    func onInputChanged(_ newValue: String) {
        guard newValue != input else { return }
        editMessage { session.editText(newText: newValue) }
    }

    func loadTypingTray() async {
        do {
            typingTray = try await typingScreens.tray()
            typingTrayFailed = false
        } catch {
            typingTrayFailed = true
        }
    }

    /// Activates a tray Phrase per the Typing Screen's activation behavior and the
    /// speech policy (#119): insert at the cursor, speak, or both.
    func activateTypingPhrase(_ phrase: Shared.Phrase) {
        // #118: ignore rapid repeated activations of the same target.
        guard acceptActivation(targetId: phrase.id) else { return }
        guard !phrase.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        // #120: tap, hold and switch selection all land here, so the tile highlight follows each of them.
        Task { await activateBoardSelectionHighlight(buttonId: "phrase:\(phrase.id)") }
        let selection = clampedSelectionRange(inputSelectionRange, maxLength: (input as NSString).length)
        // Before the Typing Screen loads, the session falls back to speak-only.
        let behavior = typingTray?.activationBehavior
        var activation: Shared.NativePhraseActivation?
        editMessage {
            activation = session.activatePhrase(
                phrase: phrase,
                start: Int32(selection.location),
                endExclusive: Int32(selection.location + selection.length),
                activationBehavior: behavior
            )
        }
        guard let activation else { return }
        // A speak-only activation leaves the Message and its selection alone.
        if let behavior, behavior.name != "SpeakOnly" { inputSelectionRange = NSRange(location: Int(activation.cursor), length: 0) }
        if activation.shouldSpeak { speakPhrase(phrase) }
    }

    /// Runs one Action-strip effect against the Message and its selection. The
    /// keyboard effect belongs to the view, which owns the Message field's focus.
    func performTypingEffect(_ effect: Shared.TypingEffect) {
        let kind = effect.kind
        if kind.name == "InsertText" || kind.name == "WrapSelection" || kind.name == "Backspace" {
            editSelection(effect)
        } else if kind.name == "Clear" {
            clearMessage()
        } else if kind.name == "Speak" {
            speakMessage()
        } else if kind.name == "Pause" {
            pauseSpeech()
        } else if kind.name == "Resume" {
            resumeSpeech()
        } else if kind.name == "Stop" {
            stopSpeech()
        } else if kind.name == "SecondaryLanguage" {
            markSelectionAsSecondaryLanguage(range: inputSelectionRange)
        } else if kind.name == "HoldMessage" {
            toggleHoldThatThought()
        }
    }

    func editSelection(_ effect: Shared.TypingEffect) {
        let text = input as NSString
        let selection = clampedSelectionRange(inputSelectionRange, maxLength: text.length)
        var range = selection
        var replacement = ""
        var cursor = selection.location
        if effect.kind.name == "InsertText" {
            replacement = effect.prefix
            cursor = selection.location + (replacement as NSString).length
        } else if effect.kind.name == "WrapSelection" {
            let selected = text.substring(with: selection)
            replacement = effect.prefix + selected + effect.suffix
            cursor = selected.isEmpty
                ? selection.location + (effect.prefix as NSString).length
                : selection.location + (replacement as NSString).length
        } else if selection.length == 0 {
            // Backspace removes the whole character before the cursor, emoji included.
            guard selection.location > 0 else { return }
            range = text.rangeOfComposedCharacterSequence(at: selection.location - 1)
            cursor = range.location
        }
        editMessage {
            session.replaceRange(
                start: Int32(range.location),
                endExclusive: Int32(range.location + range.length),
                replacement: replacement
            )
        }
        inputSelectionRange = NSRange(location: cursor, length: 0)
    }

    /// Whether the secondary-language action can tag a selection.
    var hasUsableSecondaryLanguage: Bool {
        let secondary = secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        return !secondary.isEmpty && secondary != primaryLanguage
    }

    /// Appends a Button of the given Page (defaulting to the current Page) to the Message.
    func appendScreenPart(text: String, buttonId: String, pageId: String? = nil, languageTag: String? = nil, mathMode: Bool = false) {
        rememberScreenButtonLooks()
        editMessage {
            session.appendScreenPart(
                text: text,
                screenId: selectedBoardSetId ?? "",
                pageId: pageId ?? selectedBoardId ?? "",
                buttonId: buttonId,
                spellingMode: selectedBoardUsesSpellingMode,
                languageTag: languageTag,
                mathMode: mathMode
            )
        }
    }

    func removeLastMessagePart() {
        editMessage { session.removeLastPart(spellingMode: selectedBoardUsesSpellingMode) }
    }

    /// Removes one part, e.g. a Button tapped by mistake in the Screens message bar.
    func removeMessagePart(at index: Int) {
        guard messageParts.indices.contains(index) else { return }
        let start = messageParts[..<index].reduce(0) { $0 + ($1.displayText as NSString).length }
        let length = (messageParts[index].displayText as NSString).length
        editMessage {
            session.replaceRange(start: Int32(start), endExclusive: Int32(start + length), replacement: "")
        }
    }

    func clearMessage() {
        editMessage { session.clear() }
        inputSelectionRange = NSRange(location: 0, length: 0)
    }

    /// Holds the Message, or swaps it with the held one.
    func toggleHoldThatThought() {
        editMessage { session.swapHeldMessage() }
        inputSelectionRange = NSRange(location: (input as NSString).length, length: 0)
    }

    /// Speaks the whole Message. The session records it in History once spoken.
    func speakMessage(cacheAudio: Bool = true) {
        AudioSessionHelper.activatePlayback()
        session.speak(voice: selectedVoice, cacheAudio: cacheAudio)
    }

    /// Speaks the Message from a Screen, honoring the Screen's sentence caching.
    func speakBoardMessage(boardSetId: String) {
        speakMessage(cacheAudio: boardSets.first(where: { $0.id == boardSetId })?.cacheWholeSentences ?? true)
    }

    /// Speaks text on its own (single Buttons) without changing the Message or History.
    func speak(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        AudioSessionHelper.activatePlayback()
        session.speakText(text: text, voice: selectedVoice)
    }

    /// Speaks an auditory-fishing preview, replacing any earlier preview without
    /// interrupting committed speech or recording History.
    func previewSpeech(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        AudioSessionHelper.activatePlayback()
        session.previewText(text: text, voice: selectedVoice, rateOverride: KotlinDouble(value: 0.8))
    }

    /// Stops any active or queued auditory-fishing preview without interrupting committed speech.
    func cancelPreviewSpeech() {
        session.cancelPreview()
    }

    /// Says one part of the Message again, e.g. a word the listener missed.
    func speakMessagePart(at index: Int) {
        AudioSessionHelper.activatePlayback()
        session.speakMessagePart(index: Int32(index), voice: selectedVoice)
    }

    /// Speaks a Phrase on its own, playing its recording when it has one.
    func speakPhrase(_ phrase: Shared.Phrase) {
        AudioSessionHelper.activatePlayback()
        session.speakPhrase(phrase: phrase, voice: selectedVoice)
    }

    func pauseSpeech() { session.pause() }
    func resumeSpeech() { session.resume() }
    func stopSpeech() { session.stop() }
    func dismissSessionNotice() { session.dismissNotice() }
    func retrySessionStorage() { session.retryStorage() }

    func markSelectionAsSecondaryLanguage(range: NSRange) {
        let locale = secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !locale.isEmpty, locale != primaryLanguage,
              range.location != NSNotFound, range.length > 0 else { return }
        editMessage {
            session.toggleSecondaryLanguage(start: Int32(range.location), endExclusive: Int32(range.location + range.length))
        }
    }

    /// Plays a Button's own sound through the session, so Pause and Stop reach it.
    func playBoardButtonSound(_ dataUrl: String) {
        guard !dataUrl.isEmpty, let url = playableURL(from: dataUrl), url.isFileURL else { return }
        AudioSessionHelper.activatePlayback()
        session.speakRecording(path: url.path, voice: selectedVoice)
    }

    /// Plays a Button recording stored as a file path.
    func playBoardButtonFile(_ path: String) {
        guard !path.isEmpty else { return }
        AudioSessionHelper.activatePlayback()
        session.speakRecording(path: path, voice: selectedVoice)
    }

    /// Records how the current Page's Buttons look, for the message bar on other Pages.
    func rememberScreenButtonLooks() {
        guard let pageId = selectedBoardId else { return }
        rememberScreenButtonLooks(pageId: pageId, cells: boardCells.map { ($0.buttonId, $0.label, $0.vocalization, $0.imageUrl) })
    }

    func rememberScreenButtonLooks(
        pageId: String,
        cells: [(buttonId: String, label: String?, vocalization: String?, imageUrl: String?)]
    ) {
        var looks = screenButtonLooks
        for cell in cells {
            let label = (cell.label ?? cell.vocalization ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let imageUrl = cell.imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
            looks["\(pageId)|\(cell.buttonId)"] = (label, imageUrl?.isEmpty == false ? imageUrl : nil)
        }
        // Runs on every Button tap; only publish when a look actually changed.
        let changed = looks.count != screenButtonLooks.count || looks.contains { key, look in
            guard let old = screenButtonLooks[key] else { return true }
            return old.label != look.label || old.imageUrl != look.imageUrl
        }
        if changed { screenButtonLooks = looks }
        lookedUpPageIds.insert(pageId)
    }

    /// Pages whose Button looks are known or being loaded.

    /// Loads the looks of Pages a saved or held Message uses but this launch has not shown.
    func resolveScreenButtonLooks(for parts: [Shared.MessagePart]) {
        let pageIds = Set(parts.compactMap { ($0.source as? Shared.MessagePartSourceScreenButton)?.pageId })
            .subtracting(lookedUpPageIds)
        guard !pageIds.isEmpty else { return }
        lookedUpPageIds.formUnion(pageIds)
        Task {
            for pageId in pageIds {
                guard let cells = try? await boardsFacade.listBoardCells(boardId: pageId) else {
                    lookedUpPageIds.remove(pageId)
                    continue
                }
                rememberScreenButtonLooks(pageId: pageId, cells: cells.map { ($0.buttonId, $0.label, $0.vocalization, $0.imageUrl) })
            }
        }
    }

    func playableURL(from dataUrl: String) -> URL? {
        if let base64Range = dataUrl.range(of: "base64,") {
            let encoded = String(dataUrl[base64Range.upperBound...])
            guard let data = Data(base64Encoded: encoded) else { return nil }
            let ext = dataUrl.hasPrefix("data:audio/mpeg") ? "mp3" : "caf"
            let temp = FileManager.default.temporaryDirectory
                .appendingPathComponent("wingmate-button-sound-\(UUID().uuidString).\(ext)")
            do {
                try data.write(to: temp)
                return temp
            } catch {
                return nil
            }
        }
        return URL(string: dataUrl)
    }

    // MARK: - History
    func loadHistory() async {
        historyLoadGeneration += 1
        let generation = historyLoadGeneration
        guard historyVisible else {
            historyPhrases = []
            likelyNextPhrases = []
            return
        }
        // On a read failure keep the History already shown; cancellation leaves it as is too.
        guard let result = try? await communicationFacade.history(), result.isSuccess else { return }
        guard historyVisible, generation == historyLoadGeneration else { return }
        await MainActor.run { self.historyPhrases = result.phrases.reversed() }
        if likelyNextPageEnabled {
            if let next = try? await communicationFacade.likelyNext(), next.isSuccess, historyVisible, likelyNextPageEnabled, generation == historyLoadGeneration {
                likelyNextPhrases = next.phrases
            }
        } else { likelyNextPhrases = [] }
    }
    var likelyNextAvailable: Bool { historyVisible && likelyNextPageEnabled && !likelyNextPhrases.isEmpty }

    // MARK: - Recording path (persisted in shared Phrase)
    func recordingPath(for phraseId: String) -> String? {
        state.phrases.first(where: { $0.id == phraseId })?.recordingPath
    }
    func setRecordingPath(_ path: String?, for phraseId: String) {
        communicationFacade.updatePhraseRecording(phraseId: phraseId, recordingPath: path)
    }

    func deleteCategory(id: String) {
        store?.accept(intent: Shared.PhraseListStoreIntent.DeleteCategory(categoryId: id))
    }

    /// Mirrors confirmed deletion undo state from `PhraseListStore` so the banner only
    /// appears after persistence succeeds, stays visible while a restore is in flight so
    /// failed restores can be retried, and never unlinks recordings still referenced by
    /// active/held Messages or History.
    func syncPhraseUndo(from storeState: Shared.PhraseListStoreState) {
        guard let undo = storeState.lastDeletedUndo else {
            pendingPhraseUndoTask?.cancel()
            pendingPhraseUndoTask = nil
            if let current = pendingPhraseUndo {
                if current.isRestoring {
                    AccessHaptics.perform(.confirm)
                }
                pendingPhraseUndo = nil
            }
            return
        }
        if let current = pendingPhraseUndo, current.id == undo.id {
            // Drive banner state from the undo entry's own restoreStatus so unrelated store errors
            // cannot prematurely re-enable Undo or play a reject haptic while restore is queued.
            switch undo.restoreStatus {
            case .restoring:
                if !current.isRestoring {
                    pendingPhraseUndo = PendingPhraseUndo(
                        id: undo.id,
                        includesSubItems: undo.includesSubItems,
                        isRestoring: true
                    )
                }
            case .failed:
                if current.isRestoring {
                    AccessHaptics.perform(.reject)
                    pendingPhraseUndo = PendingPhraseUndo(
                        id: undo.id,
                        includesSubItems: undo.includesSubItems,
                        isRestoring: false
                    )
                }
            default:
                break
            }
            return
        }
        guard !dismissedPhraseUndoIds.contains(undo.id) else { return }
        pendingPhraseUndoTask?.cancel()
        pendingPhraseUndoTask = nil

        let entry = PendingPhraseUndo(
            id: undo.id,
            includesSubItems: undo.includesSubItems,
            isRestoring: undo.restoreStatus == .restoring
        )
        pendingPhraseUndo = entry

        let messageKey = entry.includesSubItems ? "phrase.deleted_with_subitems" : "phrase.deleted"
        UIAccessibility.post(notification: .announcement, argument: NSLocalizedString(messageKey, comment: ""))

        // Keep the undo banner visible for assistive-technology users until they choose Undo or Close.
        if !UIAccessibility.isVoiceOverRunning && !UIAccessibility.isSwitchControlRunning {
            pendingPhraseUndoTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                guard !Task.isCancelled, let self,
                      let current = self.pendingPhraseUndo,
                      current.id == entry.id,
                      !current.isRestoring else { return }
                self.dismissPendingPhraseUndo()
            }
        }
    }

    func undoPendingPhraseDeletion() {
        pendingPhraseUndoTask?.cancel()
        pendingPhraseUndoTask = nil
        guard let pending = pendingPhraseUndo, !pending.isRestoring else { return }
        // Keep the banner visible with Undo disabled until the store confirms restore or reports failure.
        pendingPhraseUndo = PendingPhraseUndo(
            id: pending.id,
            includesSubItems: pending.includesSubItems,
            isRestoring: true
        )
        store?.accept(intent: Shared.PhraseListStoreIntent.UndoLastDeletion(undoId: pending.id))
    }

    func dismissPendingPhraseUndo() {
        pendingPhraseUndoTask?.cancel()
        pendingPhraseUndoTask = nil
        guard let pending = pendingPhraseUndo, !pending.isRestoring else { return }
        dismissedPhraseUndoIds.insert(pending.id)
        pendingPhraseUndo = nil
        store?.accept(intent: Shared.PhraseListStoreIntent.DismissDeletionUndo(undoId: pending.id))
    }

    func updatePhrase(id: String, text: String?, name: String?, imageUrl: String? = nil) {
        let normalizedImageUrl = imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        store?.accept(intent: Shared.PhraseListStoreIntent.UpdatePhrase(id: id, text: text, name: name, imageUrl: normalizedImageUrl))
    }

    /// Moves a Phrase or Category to the target's place in the repository order.
    func movePhrase(_ phraseId: String, onto targetId: String) {
        guard phraseId != targetId else { return }
        store?.accept(intent: Shared.PhraseListStoreIntent.MovePhrase(phraseId: phraseId, targetId: targetId))
    }

    // MARK: - Add category / phrase
    func addCategory(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store?.accept(intent: Shared.PhraseListStoreIntent.AddCategory(name: trimmed))
    }

    func addPhrase(text: String, alternativeText: String? = nil, imageUrl: String? = nil, recordingPath: String? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let normalizedAlternative = alternativeText?.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalAlternative = (normalizedAlternative?.isEmpty == false) ? normalizedAlternative : nil
        let normalizedImageUrl = imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalImageUrl = (normalizedImageUrl?.isEmpty == false) ? normalizedImageUrl : nil
        let normalizedRecordingPath = recordingPath?.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalRecordingPath = (normalizedRecordingPath?.isEmpty == false) ? normalizedRecordingPath : nil
        store?.accept(intent: Shared.PhraseListStoreIntent.AddPhrase(text: trimmed, name: finalAlternative, imageUrl: finalImageUrl, recordingPath: finalRecordingPath))
    }
    
    // MARK: - Prediction
    func refreshPredictions(for text: String) {
        #if DEBUG
        predictionJob?.cancel()
        predictionSubscription?.cancel()
        predictionSubscription = nil
        // Screens have their own prediction Buttons; Typing's suggestions would only re-render them.
        if boardModeEnabled || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            if !(predictions.words.isEmpty && predictions.letters.isEmpty) {
                predictions = Shared.PredictionResult(words: [], letters: [])
            }
            return
        }
        predictionJob = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard !Task.isCancelled, let self else { return }
            self.predictionSubscription = self.predictionFacade.observe(
                context: text, maxWords: 5, maxLetters: 5
            ) { [weak self] update in
                // An unavailable update has no suggestions, so stale ones clear; the next edit retries.
                guard let self, self.input == text else { return }
                self.predictions = update.predictions
            }
        }
        #endif
    }

    #if DEBUG
    func applyWordPrediction(_ word: String) {
        let result = TextEditingPolicy.shared.completeWord(
            text: input,
            cursor: Int32(inputSelectionRange.location),
            suggestion: word
        )
        onInputChanged(result.text)
        inputSelectionRange = NSRange(location: Int(result.cursor), length: 0)
    }
    
    func applyLetterPrediction(_ char: String) {
        let result = TextEditingPolicy.shared.insert(
            text: input,
            cursor: Int32(inputSelectionRange.location),
            value: char
        )
        onInputChanged(result.text)
        inputSelectionRange = NSRange(location: Int(result.cursor), length: 0)
    }
    #endif

    // MARK: - Symbol search
    /// Searches the shared symbol catalogue; ranking and package rules stay in shared code.
    /// `symbolPackage` is a package wire value (`all`, `opensymbols`, `mulberry`, `arasaac`).
    /// Returns nil when the search is cancelled.
    func searchSymbols(query: String, symbolPackage: String) async -> Shared.SymbolSearchResult? {
        try? await symbolSearchFacade.search(
            query: query,
            locale: Locale.current.identifier,
            symbolPackage: symbolPackage,
            prioritizeArasaac: false
        )
    }
}
