import Foundation

struct BoardSetInfo: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var rootBoardId: String
    var boardIds: [String]
    var isLocked: Bool
    var cacheWholeSentences: Bool
    var updatedAt: TimeInterval
}

struct ScreenPreviewCell: Identifiable, Equatable {
    var row: Int
    var column: Int
    var rowSpan: Int
    var columnSpan: Int
    var colorHex: String?
    var label: String?
    var imageUrl: String?

    var id: String { "\(row):\(column)" }
}

/// The home page of a screen, reduced to what a library thumbnail needs.
struct ScreenPreview: Equatable {
    var rows: Int
    var columns: Int
    var backgroundHex: String?
    var cells: [ScreenPreviewCell]
}

struct BoardCellInfo: Identifiable, Equatable {
    var row: Int
    var col: Int
    var buttonId: String
    var label: String?
    var vocalization: String?
    var backgroundColor: String?
    var resolvedBackgroundColor: String?
    var wordType: String?
    var borderColor: String?
    var linkedBoardId: String?
    var imageId: String?
    var imageUrl: String?
    var hidden: Bool
    var actions: [String]
    var soundId: String? = nil
    var soundDataUrl: String? = nil
    var soundPath: String? = nil
    var shape: String
    var language: String? = nil
    var mathMode: Bool = false

    var id: String { "\(row):\(col)" }
}

struct BoardFieldItem: Identifiable, Equatable {
    var row: Int
    var column: Int
    var rowSpan: Int
    var columnSpan: Int
    var buttonId: String?

    var id: String { "\(row):\(column)" }
}

struct SentencePhraseToken: Identifiable, Equatable {    var id: String = UUID().uuidString
    var phraseId: String
    var text: String
    var title: String
    var imageUrl: String?
}

extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

