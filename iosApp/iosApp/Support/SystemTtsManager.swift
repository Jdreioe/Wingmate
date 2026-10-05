import Foundation
import AVFoundation
import Shared

/// The device voice. Kotlin's Communication session drives it through `IosDeviceSpeech`
/// whenever the System engine speaks, including when a cloud voice falls back to it.
final class SystemTtsManager: NSObject, AVSpeechSynthesizerDelegate, IosDeviceSpeech {
    static let shared = SystemTtsManager()
    private let synth = AVSpeechSynthesizer()

    /// The utterances of the request in progress and how to report its end.
    private var request: (utterances: [AVSpeechUtterance], onFinished: (KotlinBoolean) -> Void)?

    private override init() {
        super.init()
        synth.delegate = self
    }

    func speak(
        segments: [SpeechSegment],
        languageTag: String,
        rate: Double,
        pitch: Double,
        onFinished: @escaping (KotlinBoolean) -> Void
    ) {
        finishRequest(spoken: false)
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }

        let utterances: [AVSpeechUtterance] = segments.compactMap { segment in
            guard !segment.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            let utterance = AVSpeechUtterance(string: segment.text)
            utterance.voice = resolveVoice(for: segment.languageTag ?? languageTag)
            utterance.rate = Float(min(
                max(Double(AVSpeechUtteranceDefaultSpeechRate) * rate, Double(AVSpeechUtteranceMinimumSpeechRate)),
                Double(AVSpeechUtteranceMaximumSpeechRate)
            ))
            utterance.pitchMultiplier = Float(min(max(pitch, 0.5), 2.0))
            if segment.pauseDurationMs > 0 {
                utterance.postUtteranceDelay = TimeInterval(segment.pauseDurationMs) / 1_000.0
            }
            return utterance
        }
        guard !utterances.isEmpty else {
            onFinished(KotlinBoolean(bool: true))
            return
        }

        AudioSessionHelper.activatePlayback()
        request = (utterances, onFinished)
        utterances.forEach { synth.speak($0) }
    }

    func pause() {
        _ = synth.pauseSpeaking(at: .immediate)
    }

    func resume() {
        _ = synth.continueSpeaking()
    }

    func stop() {
        finishRequest(spoken: false)
        synth.stopSpeaking(at: .immediate)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        if let request, request.utterances.last === utterance {
            finishRequest(spoken: true)
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        if let request, request.utterances.contains(where: { $0 === utterance }) {
            finishRequest(spoken: false)
        }
    }

    private func finishRequest(spoken: Bool) {
        guard let finished = request else { return }
        request = nil
        finished.onFinished(KotlinBoolean(bool: spoken))
    }

    private func resolveVoice(for language: String?) -> AVSpeechSynthesisVoice? {
        guard let raw = language?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }

        let candidates = normalizedLanguageCandidates(from: raw)
        let allVoices = AVSpeechSynthesisVoice.speechVoices()

        // 1) Prefer exact locale matches among installed voices.
        if let exact = allVoices.first(where: { voice in
            candidates.contains(where: { $0.caseInsensitiveCompare(voice.language) == .orderedSame })
        }) {
            return exact
        }

        // 2) Fall back to matching language code prefix (e.g. "da" -> "da-DK").
        let languageCode = languageCodeOnly(from: raw)
        if !languageCode.isEmpty,
           let prefix = allVoices.first(where: { $0.language.lowercased().hasPrefix(languageCode + "-") || $0.language.lowercased() == languageCode }) {
            return prefix
        }

        // 3) Try Apple resolver with normalized candidates.
        for candidate in candidates {
            if let voice = AVSpeechSynthesisVoice(language: candidate) {
                return voice
            }
        }

        // 4) Final fallback: language-only (e.g. "da").
        if !languageCode.isEmpty {
            return AVSpeechSynthesisVoice(language: languageCode)
        }

        return nil
    }

    private func normalizedLanguageCandidates(from raw: String) -> [String] {
        var out: [String] = []

        func add(_ value: String) {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return }
            if !out.contains(where: { $0.caseInsensitiveCompare(trimmed) == .orderedSame }) {
                out.append(trimmed)
            }
        }

        let dash = raw.replacingOccurrences(of: "_", with: "-")
        add(raw)
        add(dash)

        // Locale canonicalization can produce underscore form, so add both styles.
        let localeCanonical = Locale(identifier: dash).identifier
        add(localeCanonical)
        add(localeCanonical.replacingOccurrences(of: "_", with: "-"))

        let code = languageCodeOnly(from: dash)
        if !code.isEmpty {
            add(code)
        }

        return out
    }

    private func languageCodeOnly(from value: String) -> String {
        let normalized = value.replacingOccurrences(of: "_", with: "-").lowercased()
        return normalized.split(separator: "-").first.map(String.init) ?? ""
    }
}
