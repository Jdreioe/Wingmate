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
            setRecordingPath("", for: id)
            recordings.removeValue(forKey: id)
            if let data = try? JSONSerialization.data(withJSONObject: recordings, options: []) {
                UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: recordingKey)
            }
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

    func pauseTts() {
        if useSystemTts || !azureConfigured {
            SystemTtsManager.shared.pause()
        } else if !isOnline && useSystemTtsWhenOffline {
            SystemTtsManager.shared.pause()
        } else {
            Task { _ = try? await bridge.pause() }
        }
    }

    func stopTts() {
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

    // MARK: - Recording store (Swift-side for now)
    private let recordingKey = "phrase_recordings_v1"
    private var recordings: [String: String] = {
        let d = UserDefaults.standard
        if let json = d.string(forKey: "phrase_recordings_v1"), let data = json.data(using: .utf8) {
            return (try? JSONSerialization.jsonObject(with: data) as? [String: String]) ?? [:]
        }
        return [:]
    }()

    func recordingPath(for phraseId: String) -> String? { recordings[phraseId] }
    func setRecordingPath(_ path: String, for phraseId: String) {
        recordings[phraseId] = path
        if let data = try? JSONSerialization.data(withJSONObject: recordings, options: []) {
            UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: recordingKey)
        }
        objectWillChange.send()
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
