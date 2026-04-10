import Foundation
import Shared
import AVFoundation
import Network

@MainActor
final class IosViewModel: ObservableObject {
    private final class StoreObserver: NSObject, Shared.RxObserver {
        private let onNextState: (Shared.PhraseListStoreState) -> Void
        private let onCompleteState: () -> Void
        init(onNext: @escaping (Shared.PhraseListStoreState) -> Void) {
            self.onNextState = onNext
            self.onCompleteState = {}
        }
        init(onNext: @escaping (Shared.PhraseListStoreState) -> Void, onComplete: @escaping () -> Void) {
            self.onNextState = onNext
            self.onCompleteState = onComplete
        }
        func onComplete() { onCompleteState() }
        func onNext(value: Any?) {
            if let s = value as? Shared.PhraseListStoreState {
                onNextState(s)
            }
        }
    }
    private var store: Shared.PhraseListStore?
    private var disposable: Shared.RxDisposable?
    private let hybrid = HybridSpeechPlayer()
    private lazy var azureSequencer: AzureHybridSequencer = {
        AzureHybridSequencer(
            speak: { [weak self] text in
                guard let self = self else { return }
                _ = try? await self.bridge.speak(text: text)
            },
            pause: { [weak self] in
                guard let self = self else { return }
                _ = try? await self.bridge.pause()
            },
            stop: { [weak self] in
                guard let self = self else { return }
                _ = try? await self.bridge.stop()
            }
        )
    }()

    @Published var state: Shared.PhraseListStoreState = Shared.PhraseListStoreState(phrases: [], categories: [], selectedCategoryId: nil, isLoading: true, error: nil)

    // Bridge to shared KMP use-cases
    private let bridge = KoinBridge()

    // UI state
    @Published var input: String = ""
    @Published var primaryLanguage: String = "en-US"
    @Published var secondaryLanguage: String = "en-US"
    @Published var secondaryLanguageRanges: [NSRange] = []
    @Published var selectedVoice: Shared.Voice? = nil
    @Published var availableLanguages: [String] = []
    // Predictions
    @Published var predictions: Shared.PredictionResult = Shared.PredictionResult(words: [], letters: [])
    private var predictionJob: Task<Void, Never>? = nil
    // History items exposed as phrases for UI rendering
    @Published var historyPhrases: [Shared.Phrase] = []
    // Special selection for History view
    let historyCategoryId = "__history__"

    // Offline handling and System TTS fallback
    @Published var showOfflineInfoOnce: Bool = false
    // System TTS preference 
    @Published var useSystemTts: Bool = UserDefaults.standard.bool(forKey: "use_system_tts")
    @Published var useSystemTtsWhenOffline: Bool = UserDefaults.standard.bool(forKey: "use_system_tts_when_offline")
    // Mix recorded phrases inside sentences
    @Published var mixRecordedPhrasesInSentences: Bool = UserDefaults.standard.bool(forKey: "mix_recorded_phrases")
    private var hasShownOfflineBanner: Bool = UserDefaults.standard.bool(forKey: "offline_banner_shown")
    private var isOnline: Bool = true

    // Pronunciation Dictionary
    @Published var pronunciations: [Shared.PronunciationEntry] = []

    // Debug helpers
    @Published var debugRepoName: String = ""
    @Published var debugPersistedVoiceName: String = ""
    // Azure availability (subscription configured)
    @Published var azureConfigured: Bool = false

    func effectiveLanguage(for v: Shared.Voice) -> String {
        func nonEmpty(_ s: String?) -> String? {
            let t = (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        if let s = nonEmpty(v.selectedLanguage) { return s }
        if let p = nonEmpty(v.primaryLanguage) { return p }
        return self.primaryLanguage
    }

    var canChangeVoiceLanguage: Bool {
        let languages = availableLanguages
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return Set(languages).count > 1
    }

    func start() async {
        await MainActor.run { IosDiBridge().startKoinWithOverridesBridge() }
        let repoNameBefore = KoinBridge().debugVoiceRepositoryName()
        print("DEBUG: After startKoinWithOverrides: Bound VoiceRepository = \(repoNameBefore)")
        if let phraseStore = KoinBridge().phraseListStoreOrNull() {
            self.store = phraseStore
            let observer = StoreObserver(onNext: { [weak self] newState in self?.state = newState }, onComplete: { [weak self] in
                self?.disposable = nil
                self?.store = nil
            })
            self.disposable = store?.states(observer: observer)
        } else {
            print("DEBUG: phraseListStoreOrNull() returned nil — Koin not ready or store not bound")
            try? await Task.sleep(nanoseconds: 150_000_000)
            if let retryStore = KoinBridge().phraseListStoreOrNull() {
                self.store = retryStore
                let observer = StoreObserver(onNext: { [weak self] newState in self?.state = newState }, onComplete: { [weak self] in
                    self?.disposable = nil
                    self?.store = nil
                })
                self.disposable = store?.states(observer: observer)
                print("DEBUG: Store resolved on retry")
            } else {
                print("DEBUG: Store still nil after retry")
            }
        }
    // Load selected voice and languages for welcome gating and UI
    refreshVoiceAndLanguages()

        await refreshLanguagePreferences()

        // Determine if Azure is configured (endpoint + key)
        await refreshAzureConfiguration()

        // Start connectivity monitoring
        ConnectivityMonitor.shared.onChange { [weak self] online in
            guard let self = self else { return }
            self.isOnline = online
            if !online && !self.hasShownOfflineBanner {
                self.showOfflineInfoOnce = true
                self.hasShownOfflineBanner = true
                UserDefaults.standard.set(true, forKey: "offline_banner_shown")
            }
        }
    // Preload history once Koin is up
    await loadHistory()
    // Train prediction model on history
    _ = try? await bridge.trainPredictionModel()
    // Load pronunciations
    await loadPronunciations()
    }

    func refreshAzureConfiguration() async {
        do {
            if let cfg = try await bridge.getSpeechConfig() {
                let ep = cfg.endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
                let key = cfg.subscriptionKey.trimmingCharacters(in: .whitespacesAndNewlines)
                azureConfigured = !ep.isEmpty && !key.isEmpty
            } else {
                azureConfigured = false
            }
        } catch {
            azureConfigured = false
        }
    }

    func refreshLanguagePreferences() async {
        do {
            let settings = try await bridge.getSettings()
            await MainActor.run {
                self.primaryLanguage = settings.primaryLanguage
                self.secondaryLanguage = settings.secondaryLanguage
            }
        } catch {
            await MainActor.run {
                self.primaryLanguage = self.primaryLanguage
                self.secondaryLanguage = self.secondaryLanguage
            }
        }
    }

    func deletePhrase(id: String) {
        if let path = recordingPath(for: id) {
            try? FileManager.default.removeItem(atPath: path)
        }
        store?.accept(intent: Shared.PhraseListStoreIntent.DeletePhrase(phraseId: id))
    }

    func selectCategory(id: String?) {
        // Toggle history mode if the special ID is selected
        if id == historyCategoryId {
            // Keep the store's selectedCategoryId nil to avoid filtering real phrases
            store?.accept(intent: Shared.PhraseListStoreIntent.SelectCategory(categoryId: nil))
        } else {
            store?.accept(intent: Shared.PhraseListStoreIntent.SelectCategory(categoryId: id))
        }
    }

    var filteredPhrases: [Shared.Phrase] {
        guard let sel = state.selectedCategoryId, !sel.isEmpty else { return state.phrases }
        return state.phrases.filter { $0.parentId == sel }
    }

    var isHistorySelected: Bool {
        // We consider history selected when selectedCategoryId is nil but a shadow selection equals history
        // The MainContentView will drive this by selecting our sentinel explicitly.
        return false // The view controls selection via the chip; we keep store selection separate.
    }

    func insertPhraseText(_ phrase: Shared.Phrase) {
        let t = phrase.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        input += t + " "
        onInputChanged(input)
        // Incremental learning
        Task { _ = try? await bridge.learnPhrase(text: t) }
    }
    func deleteText() {
        input = ""
        onInputChanged(input)
    }
    func speak(_ text: String) {
        let plain = text
        guard !plain.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        AudioSessionHelper.activatePlayback()

        // Only convert hidden ranges for the live input text and Azure path.
        let t = (text == input) ? textWithSecondaryLanguageMarkup(from: plain) : plain

        // Hybrid mixing: splice recorded phrase audio into sentence
        if mixRecordedPhrasesInSentences {
            let segments = buildHybridSegments(for: plain)
            if !segments.isEmpty {
                // Decide engine for TTS segments: Azure vs local
                let shouldUseAzure = azureConfigured && isOnline || (azureConfigured && !useSystemTtsWhenOffline)
                if !useSystemTts && shouldUseAzure {
                    let azSegments: [AzureHybridSequencer.Segment] = segments.map { seg in
                        switch seg {
                        case .audio(let url): return .audio(url)
                        case .tts(let s): return .tts(s)
                        }
                    }
                    azureSequencer.play(segments: azSegments)
                } else {
                    hybrid.play(segments: segments, language: primaryLanguage)
                }
                return
            }
        }

        // If user prefers system TTS, use it directly
        if useSystemTts {
            SystemTtsManager.shared.speak(plain, language: primaryLanguage)
            return
        }
        
        // If Azure is not configured, always use on-device TTS to keep the app working
        if !azureConfigured {
            SystemTtsManager.shared.speak(plain, language: primaryLanguage)
            return
        }
        // Otherwise, allow offline fallback when enabled
        if !isOnline && useSystemTtsWhenOffline {
            SystemTtsManager.shared.speak(plain, language: primaryLanguage)
            return
        }
        Task { _ = try? await bridge.speak(text: t) }
    }

    private func textWithSecondaryLanguageMarkup(from plainText: String) -> String {
        let locale = secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !locale.isEmpty,
              locale != primaryLanguage,
              !secondaryLanguageRanges.isEmpty else {
            return plainText
        }

        let ns = plainText as NSString
        let validRanges = secondaryLanguageRanges
            .filter { $0.location != NSNotFound && $0.length > 0 && $0.location + $0.length <= ns.length }
            .sorted { $0.location < $1.location }

        guard !validRanges.isEmpty else { return plainText }

        var cursor = 0
        var out = ""
        for range in validRanges {
            if range.location > cursor {
                out += ns.substring(with: NSRange(location: cursor, length: range.location - cursor))
            }
            let selected = ns.substring(with: range)
            out += "<lang xml:lang=\"\(locale)\">\(selected)</lang>"
            cursor = range.location + range.length
        }
        if cursor < ns.length {
            out += ns.substring(from: cursor)
        }
        return out
    }

    // MARK: - History
    func loadHistory() async {
        do {
            let items = try await bridge.listHistoryAsPhrases()
            await MainActor.run { self.historyPhrases = items.reversed() }
        } catch {
            await MainActor.run { self.historyPhrases = [] }
        }
    }
    // Build mixed segments: recorded audio when a phrase name/text matches; TTS for the rest
    private func buildHybridSegments(for text: String) -> [HybridSpeechPlayer.Segment] {
        // Prepare lookup: map name/text -> recording path if exists
        let phrases = filteredPhrases // use currently visible scope; could also use state.phrases
        var dictionary: [(pattern: NSRegularExpression, id: String, path: String)] = []
        for p in phrases {
            let key = (p.name?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 } ?? p.text
            guard !key.isEmpty, let path = recordingPath(for: p.id), !path.isEmpty else { continue }
            // Word-boundary, case-insensitive
            let pattern = "\\b" + NSRegularExpression.escapedPattern(for: key) + "\\b"
            if let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) {
                dictionary.append((re, p.id, path))
            }
        }
        guard !dictionary.isEmpty else { return [] }

        // Find non-overlapping matches preferring longer keys first
        let ns = text as NSString
        var matches: [(range: NSRange, path: String)] = []
        for (re, _, path) in dictionary.sorted(by: { $0.pattern.pattern.count > $1.pattern.pattern.count }) {
            let found = re.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length))
            for m in found {
                // Skip overlaps
                if matches.contains(where: { NSIntersectionRange($0.range, m.range).length > 0 }) { continue }
                matches.append((m.range, path))
            }
        }
        guard !matches.isEmpty else { return [] }

        // Sort by location and build segments
        matches.sort { $0.range.location < $1.range.location }
        var segs: [HybridSpeechPlayer.Segment] = []
        var cursor = 0
        for m in matches {
            if m.range.location > cursor {
                let start = cursor
                let end = m.range.location
                let chunk = ns.substring(with: NSRange(location: start, length: end - start))
                if !chunk.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    segs.append(.tts(chunk))
                }
            }
            segs.append(.audio(URL(fileURLWithPath: m.path)))
            cursor = m.range.location + m.range.length
        }
        if cursor < ns.length {
            let chunk = ns.substring(from: cursor)
            if !chunk.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                segs.append(.tts(chunk))
            }
        }
        return segs
    }

    func pauseTts() {
        if azureSequencer.isRunning {
            azureSequencer.pause()
            return
        }
        if hybrid.isPlaying {
            hybrid.pause()
            return
        }
        if useSystemTts || !azureConfigured {
            SystemTtsManager.shared.pause()
        } else if !isOnline && useSystemTtsWhenOffline {
            SystemTtsManager.shared.pause()
        } else {
            Task { _ = try? await bridge.pause() }
        }
    }

    func stopTts() {
        if azureSequencer.isRunning {
            azureSequencer.stop()
            return
        }
        if hybrid.isPlaying {
            hybrid.stop()
            return
        }
        if useSystemTts || !azureConfigured {
            SystemTtsManager.shared.stop()
        } else if !isOnline && useSystemTtsWhenOffline {
            SystemTtsManager.shared.stop()
        } else {
            Task { _ = try? await bridge.stop() }
        }
    }

    func setUseSystemTts(_ enabled: Bool) {
        self.useSystemTts = enabled
        UserDefaults.standard.set(enabled, forKey: "use_system_tts")
    }

    func setUseSystemTtsWhenOffline(_ enabled: Bool) {
        self.useSystemTtsWhenOffline = enabled
        UserDefaults.standard.set(enabled, forKey: "use_system_tts_when_offline")
    }

    func setMixRecordedPhrases(_ enabled: Bool) {
        self.mixRecordedPhrasesInSentences = enabled
        UserDefaults.standard.set(enabled, forKey: "mix_recorded_phrases")
    }

    // MARK: - Recording path (persisted in shared Phrase)
    func recordingPath(for phraseId: String) -> String? {
        state.phrases.first(where: { $0.id == phraseId })?.recordingPath
    }
    func setRecordingPath(_ path: String?, for phraseId: String) {
        bridge.updatePhraseRecording(phraseId: phraseId, recordingPath: path)
    }

    func chooseVoice(_ v: Shared.Voice) async {
        do {
            try await bridge.selectVoiceAndMaybeUpdatePrimary(voice: v)
            await MainActor.run {
                self.selectedVoice = v
                if let langs = v.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
                self.primaryLanguage = effectiveLanguage(for: v)
            }
            let persisted = try? await bridge.selectedVoice()
            if let pv = persisted {
                self.selectedVoice = pv
                if let langs = pv.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
                self.primaryLanguage = effectiveLanguage(for: pv)
                #if DEBUG
                let name = (pv.displayName ?? pv.name) ?? "—"
                let lang = effectiveLanguage(for: pv)
                print("DEBUG: bridge.selectedVoice() => \(name) [\(lang)]")
                #endif
            } else {
                #if DEBUG
                print("DEBUG: bridge.selectedVoice() => (none)")
                #endif
            }
        } catch {
            // swallow for now
        }
    }

    func updateLanguage(_ lang: String) {
        Task {
            _ = try? await bridge.updateSelectedVoiceLanguage(lang: lang)
            self.primaryLanguage = lang
            if lang == self.secondaryLanguage {
                self.secondaryLanguageRanges = []
            }
            refreshVoiceAndLanguages()
        }
    }

    func updateSecondaryLanguage(_ lang: String) {
        Task {
            _ = try? await bridge.updateSecondaryLanguage(lang: lang)
            self.secondaryLanguage = lang
            if lang == self.primaryLanguage {
                self.secondaryLanguageRanges = []
            }
        }
    }

    func markSelectionAsSecondaryLanguage(range: NSRange) {
        let locale = secondaryLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !locale.isEmpty, locale != primaryLanguage else { return }

        let currentText = input as NSString
        guard range.location != NSNotFound,
              range.length > 0,
              range.location + range.length <= currentText.length else { return }

        secondaryLanguageRanges = mergeRanges(secondaryLanguageRanges + [range], maxLength: currentText.length)
    }

    func adjustSecondaryLanguageRangesAfterEdit(range: NSRange, replacementText: String) {
        guard !secondaryLanguageRanges.isEmpty else { return }
        let currentLength = (input as NSString).length
        let replacementLength = (replacementText as NSString).length
        let delta = replacementLength - range.length

        let editStart = range.location
        let editEnd = range.location + range.length

        var updated: [NSRange] = []
        for r in secondaryLanguageRanges {
            let rStart = r.location
            let rEnd = r.location + r.length

            // Edit completely before range: shift
            if editEnd <= rStart {
                updated.append(NSRange(location: max(0, rStart + delta), length: r.length))
                continue
            }

            // Edit completely after range: unchanged
            if editStart >= rEnd {
                updated.append(r)
                continue
            }

            // Overlap cases
            if editStart <= rStart && editEnd >= rEnd {
                // Range removed entirely
                continue
            }

            if editStart <= rStart {
                // Overlap starts before (or at) marked range start
                let newStart = max(0, editStart + replacementLength)
                let newLen = max(0, rEnd - editEnd)
                if newLen > 0 {
                    updated.append(NSRange(location: newStart, length: newLen))
                }
                continue
            }

            if editEnd >= rEnd {
                // Overlap ends after (or at) marked range end
                let newLen = max(0, editStart - rStart)
                if newLen > 0 {
                    updated.append(NSRange(location: rStart, length: newLen))
                }
                continue
            }

            // Edit fully inside marked range: keep one merged range with adjusted length
            let newLen = r.length + delta
            if newLen > 0 {
                updated.append(NSRange(location: rStart, length: newLen))
            }
        }

        let newMaxLength = max(0, currentLength + delta)
        secondaryLanguageRanges = mergeRanges(updated, maxLength: newMaxLength)
    }

    private func mergeRanges(_ ranges: [NSRange], maxLength: Int) -> [NSRange] {
        let sorted = ranges
            .filter { $0.location != NSNotFound && $0.length > 0 && $0.location + $0.length <= maxLength }
            .sorted { $0.location < $1.location }

        guard !sorted.isEmpty else { return [] }
        var merged: [NSRange] = []
        for r in sorted {
            if let last = merged.last {
                let lastEnd = last.location + last.length
                let rEnd = r.location + r.length
                if r.location <= lastEnd {
                    merged[merged.count - 1] = NSRange(location: last.location, length: max(lastEnd, rEnd) - last.location)
                } else {
                    merged.append(r)
                }
            } else {
                merged.append(r)
            }
        }
        return merged
    }

    func refreshVoiceAndLanguages() {
        Task {
            let v = try? await bridge.selectedVoice()
            self.selectedVoice = v
            if let langs = v?.supportedLanguages { self.availableLanguages = langs } else { self.availableLanguages = [] }
            if let v = v { self.primaryLanguage = effectiveLanguage(for: v) }
        }
    }

    func deleteCategory(id: String) {
        store?.accept(intent: Shared.PhraseListStoreIntent.DeleteCategory(categoryId: id))
    }

    func updatePhrase(id: String, text: String?, name: String?, imageUrl: String? = nil) {
        let normalizedImageUrl = imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        store?.accept(intent: Shared.PhraseListStoreIntent.UpdatePhrase(id: id, text: text, name: name, imageUrl: normalizedImageUrl))
    }

    func movePhrase(from: Int, to: Int) {
        store?.accept(intent: Shared.PhraseListStoreIntent.MovePhrase(fromIndex: Int32(from), toIndex: Int32(to)))
    }

    // MARK: - Add category / phrase
    func addCategory(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store?.accept(intent: Shared.PhraseListStoreIntent.AddCategory(name: trimmed))
    }

    func addPhrase(text: String, alternativeText: String? = nil, imageUrl: String? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let normalizedAlternative = alternativeText?.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalAlternative = (normalizedAlternative?.isEmpty == false) ? normalizedAlternative : nil
        let normalizedImageUrl = imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalImageUrl = (normalizedImageUrl?.isEmpty == false) ? normalizedImageUrl : nil
        store?.accept(intent: Shared.PhraseListStoreIntent.AddPhrase(text: trimmed, name: finalAlternative, imageUrl: finalImageUrl))
        // Incremental learning
        Task { _ = try? await bridge.learnPhrase(text: trimmed) }
    }
    
    // MARK: - Prediction
    func onInputChanged(_ newValue: String, preserveSecondaryRanges: Bool = false) {
        input = newValue
        predictionJob?.cancel()
        predictionJob = Task {
            try? await Task.sleep(nanoseconds: 100_000_000) // 100ms debounce
            if Task.isCancelled { return }
            let res = (try? await bridge.predict(context: newValue, maxWords: 5, maxLetters: 5)) ?? Shared.PredictionResult(words: [], letters: [])
            await MainActor.run { self.predictions = res }
        }
    }
    
    func applyWordPrediction(_ word: String) {
        let text = input
        // Simple heuristic: replace last word part or append
        // Finding the last word boundary
        let ns = text as NSString
        let range = NSRange(location: 0, length: ns.length)
        // Regex to find the last token
        // Strategy: find last space.
        if let lastSpaceInfo = text.lastIndex(of: " ") {
            let prefix = text[..<lastSpaceInfo]
            // If the typed word matches start of prediction, replace it. 
            // Actually, simplest is: if text ends with space, append. If not, replace last token.
            if text.hasSuffix(" ") {
                input = text + word + " "
            } else {
                input = String(prefix) + " " + word + " "
            }
        } else {
            // First word
             input = word + " "
        }
        // Re-predict
        onInputChanged(input)
    }
    
    func applyLetterPrediction(_ char: String) {
        input.append(char)
        onInputChanged(input)
    }

    private func hasAudioPath(_ path: String?) -> Bool {
        guard let path = path else { return false }
        return !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func resolveLastAudioPath() -> String? {
        let normalizedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)

        // 1) Prefer an exact match to current input if available.
        if !normalizedInput.isEmpty,
           let byText = historyPhrases.first(where: {
               let t = ($0.text).trimmingCharacters(in: .whitespacesAndNewlines)
               let n = ($0.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
               return (t == normalizedInput || n == normalizedInput) && hasAudioPath($0.recordingPath)
           }),
           let path = byText.recordingPath {
            return path
        }

        // 2) Fall back to the most recent history entry with audio.
        if let fromHistory = historyPhrases.first(where: { hasAudioPath($0.recordingPath) }),
           let path = fromHistory.recordingPath {
            return path
        }

        // 3) Last fallback: any stored phrase recording.
        if let fromPhrases = state.phrases.first(where: { hasAudioPath($0.recordingPath) }),
           let path = fromPhrases.recordingPath {
            return path
        }

        return nil
    }

    var hasShareableAudio: Bool {
        resolveLastAudioPath() != nil
    }
    
    // MARK: - Sharing
    func shareLastAudio() {
        guard let path = resolveLastAudioPath() else { return }
        bridge.shareAudio(path: path)
    }

    func copyLastAudio() {
        guard let path = resolveLastAudioPath() else { return }
        // Fallback to share to avoid framework symbol mismatch when copyAudio is not exported in current build.
        bridge.shareAudio(path: path)
    }
    
    // MARK: - Pronunciations
    func loadPronunciations() async {
        do {
            let items = try await bridge.listPronunciations()
            await MainActor.run { self.pronunciations = items }
        } catch {
            await MainActor.run { self.pronunciations = [] }
        }
    }
    
    func addPronunciation(word: String, phoneme: String, alphabet: String) {
        Task {
            try? await bridge.addPronunciation(word: word, phoneme: phoneme, alphabet: alphabet)
            await loadPronunciations()
        }
    }
    
    func deletePronunciation(word: String) {
        Task {
            try? await bridge.deletePronunciation(word: word)
            await loadPronunciations()
        }
    }
}
