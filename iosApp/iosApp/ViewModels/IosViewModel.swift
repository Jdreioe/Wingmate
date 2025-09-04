import Foundation
import Shared
import AVFoundation
import Network

@MainActor
final class IosViewModel: ObservableObject {
    private final class StoreObserver: NSObject, Shared.RxObserver {
        private let onNextState: (Shared.PhraseListStoreState) -> Void
        init(onNext: @escaping (Shared.PhraseListStoreState) -> Void) {
            self.onNextState = onNext
        }
        func onComplete() { /* no-op */ }
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
    @Published var selectedVoice: Shared.Voice? = nil
    @Published var availableLanguages: [String] = []

    // Offline handling and System TTS fallback
    @Published var showOfflineInfoOnce: Bool = false
    // System TTS preference 
    @Published var useSystemTts: Bool = UserDefaults.standard.bool(forKey: "use_system_tts")
    @Published var useSystemTtsWhenOffline: Bool = UserDefaults.standard.bool(forKey: "use_system_tts_when_offline")
    // Mix recorded phrases inside sentences
    @Published var mixRecordedPhrasesInSentences: Bool = UserDefaults.standard.bool(forKey: "mix_recorded_phrases")
    private var hasShownOfflineBanner: Bool = UserDefaults.standard.bool(forKey: "offline_banner_shown")
    private var isOnline: Bool = true

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

    func start() async {
        await MainActor.run { IosDiBridge().startKoinWithOverridesBridge() }
        let repoNameBefore = KoinBridge().debugVoiceRepositoryName()
        print("DEBUG: After startKoinWithOverrides: Bound VoiceRepository = \(repoNameBefore)")
        if let phraseStore = KoinBridge().phraseListStoreOrNull() {
            self.store = phraseStore
            let observer = StoreObserver { [weak self] newState in self?.state = newState }
            self.disposable = store?.states(observer: observer)
        } else {
            print("DEBUG: phraseListStoreOrNull() returned nil — Koin not ready or store not bound")
            try? await Task.sleep(nanoseconds: 150_000_000)
            if let retryStore = KoinBridge().phraseListStoreOrNull() {
                self.store = retryStore
                let observer = StoreObserver { [weak self] newState in self?.state = newState }
                self.disposable = store?.states(observer: observer)
                print("DEBUG: Store resolved on retry")
            } else {
                print("DEBUG: Store still nil after retry")
            }
        }
    // Load selected voice and languages for welcome gating and UI
    refreshVoiceAndLanguages()

        // Determine if Azure is configured (endpoint + key)
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
    }

    func deletePhrase(id: String) {
        if let path = recordingPath(for: id) {
            try? FileManager.default.removeItem(atPath: path)
        }
        store?.accept(intent: Shared.PhraseListStoreIntent.DeletePhrase(phraseId: id))
    }

    func selectCategory(id: String?) {
        store?.accept(intent: Shared.PhraseListStoreIntent.SelectCategory(categoryId: id))
    }

    var filteredPhrases: [Shared.Phrase] {
        guard let sel = state.selectedCategoryId, !sel.isEmpty else { return state.phrases }
        return state.phrases.filter { $0.parentId == sel }
    }

    func insertPhraseText(_ phrase: Shared.Phrase) {
        let t = phrase.text
        guard !t.isEmpty else { return }
        input.append(t)
    }

    func speak(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        AudioSessionHelper.activatePlayback()
        // Hybrid mixing: splice recorded phrase audio into sentence
        if mixRecordedPhrasesInSentences {
            let segments = buildHybridSegments(for: t)
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
            SystemTtsManager.shared.speak(t, language: primaryLanguage)
            return
        }
        
        // If Azure is not configured, always use on-device TTS to keep the app working
        if !azureConfigured {
            SystemTtsManager.shared.speak(t, language: primaryLanguage)
            return
        }
        // Otherwise, allow offline fallback when enabled
        if !isOnline && useSystemTtsWhenOffline {
            SystemTtsManager.shared.speak(t, language: primaryLanguage)
            return
        }
        Task { _ = try? await bridge.speak(text: t) }
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
            refreshVoiceAndLanguages()
        }
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

    func updatePhrase(id: String, text: String?, name: String?) {
        store?.accept(intent: Shared.PhraseListStoreIntent.UpdatePhrase(id: id, text: text, name: name))
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

    func addPhrase(text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store?.accept(intent: Shared.PhraseListStoreIntent.AddPhrase(text: trimmed))
    }
}
