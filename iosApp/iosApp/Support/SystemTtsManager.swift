import Foundation
import AVFoundation

final class SystemTtsManager: NSObject, AVSpeechSynthesizerDelegate {
    static let shared = SystemTtsManager()
    private let synth = AVSpeechSynthesizer()

    private override init() {
        super.init()
        synth.delegate = self
    }

    func speak(
        _ text: String,
        language: String?,
        secondaryLanguage: String? = nil,
        secondaryLanguageRanges: [NSRange] = []
    ) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let utterances = buildUtterances(
            for: text,
            primaryLanguage: language,
            secondaryLanguage: secondaryLanguage,
            secondaryLanguageRanges: secondaryLanguageRanges
        )
        guard !utterances.isEmpty else { return }

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true, options: [])

        for utterance in utterances {
            synth.speak(utterance)
        }
    }

    private func buildUtterances(
        for text: String,
        primaryLanguage: String?,
        secondaryLanguage: String?,
        secondaryLanguageRanges: [NSRange]
    ) -> [AVSpeechUtterance] {
        let nsText = text as NSString
        let totalLength = nsText.length
        guard totalLength > 0 else { return [] }

        let primaryNormalized = primaryLanguage?.trimmingCharacters(in: .whitespacesAndNewlines)
        let secondaryNormalized = secondaryLanguage?.trimmingCharacters(in: .whitespacesAndNewlines)

        let supportsSecondary = {
            guard let sec = secondaryNormalized, !sec.isEmpty else { return false }
            guard let pri = primaryNormalized, !pri.isEmpty else { return true }
            return sec.caseInsensitiveCompare(pri) != .orderedSame
        }()

        if !supportsSecondary || secondaryLanguageRanges.isEmpty {
            return [makeUtterance(text: text, language: primaryLanguage)]
        }

        let validRanges = mergedValidRanges(secondaryLanguageRanges, maxLength: totalLength)
        if validRanges.isEmpty {
            return [makeUtterance(text: text, language: primaryLanguage)]
        }

        var utterances: [AVSpeechUtterance] = []
        var cursor = 0
        for range in validRanges {
            if range.location > cursor {
                let primaryChunk = nsText.substring(with: NSRange(location: cursor, length: range.location - cursor))
                if !primaryChunk.isEmpty {
                    utterances.append(makeUtterance(text: primaryChunk, language: primaryLanguage))
                }
            }

            let secondaryChunk = nsText.substring(with: range)
            if !secondaryChunk.isEmpty {
                utterances.append(makeUtterance(text: secondaryChunk, language: secondaryLanguage))
            }
            cursor = range.location + range.length
        }

        if cursor < totalLength {
            let trailing = nsText.substring(from: cursor)
            if !trailing.isEmpty {
                utterances.append(makeUtterance(text: trailing, language: primaryLanguage))
            }
        }

        return utterances
    }

    private func makeUtterance(text: String, language: String?) -> AVSpeechUtterance {
        let utterance = AVSpeechUtterance(string: text)
        if let voice = resolveVoice(for: language) {
            utterance.voice = voice
        }
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        return utterance
    }

    private func mergedValidRanges(_ ranges: [NSRange], maxLength: Int) -> [NSRange] {
        let sorted = ranges
            .filter { $0.location != NSNotFound && $0.length > 0 && $0.location + $0.length <= maxLength }
            .sorted { $0.location < $1.location }

        guard !sorted.isEmpty else { return [] }

        var merged: [NSRange] = []
        for range in sorted {
            if let last = merged.last {
                let lastEnd = last.location + last.length
                let rangeEnd = range.location + range.length
                if range.location <= lastEnd {
                    merged[merged.count - 1] = NSRange(location: last.location, length: max(lastEnd, rangeEnd) - last.location)
                } else {
                    merged.append(range)
                }
            } else {
                merged.append(range)
            }
        }
        return merged
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

    func pause() {
        _ = synth.pauseSpeaking(at: .immediate)
    }

    func stop() {
        synth.stopSpeaking(at: .immediate)
    }
}
