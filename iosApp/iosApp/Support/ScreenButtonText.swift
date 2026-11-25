import Foundation

/// Display rules for text on Screen Buttons, shared in spirit with Android's ObfButtonContent.
/// Speech Screen buttons store a shortened opening cue in `label` and the full paragraph in
/// `vocalization`; text-only buttons expand that cue to the full paragraph so the Communicator
/// can read along without duplicate or cut-off text.
enum ScreenButtonText {
    /// Returns the full spoken paragraph when `label` is a shortened `"…"` or `"..."` cue for
    /// `vocalization`; otherwise `nil` so the saved label displays as written.
    static func expandedText(label: String?, vocalization: String?) -> String? {
        guard let rawLabel = label?.trimmingCharacters(in: .whitespacesAndNewlines),
              let rawVocalization = vocalization else {
            return nil
        }
        let cue: String
        if rawLabel.hasSuffix("…") {
            cue = String(rawLabel.dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
        } else if rawLabel.hasSuffix("...") {
            cue = String(rawLabel.dropLast(3)).trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            return nil
        }
        let spoken = rawVocalization
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        guard !cue.isEmpty, spoken.count > cue.count, spoken.hasPrefix(cue) else {
            return nil
        }
        return spoken
    }

    /// A word or short phrase, shown centered and large on a text-only Button:
    /// up to 3 words and 24 characters.
    static func isShortLabel(_ label: String) -> Bool {
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        let words = trimmed.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }
        return trimmed.count <= 24 && words.count <= 3
    }
}
