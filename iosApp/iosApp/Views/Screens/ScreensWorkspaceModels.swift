import SwiftUI

struct CellOpenSymbolsSymbolResult {
    let id: String
    let name: String
    let image_url: String?
    let source: String
}

enum CellSymbolPackageFilter: String, CaseIterable, Identifiable {
    case all
    case opensymbols
    case mulberry
    case arasaac

    var id: String { rawValue }

    /// Resolved catalog text. The key is built as a String first: interpolating inside
    /// LocalizedStringKey looks up a format key and shows the raw key.
    var localizedTitle: String { Self.title(for: rawValue) }

    static func title(for source: String) -> String {
        switch source {
        case Self.all.rawValue: return NSLocalizedString("phrase.symbol.package.all", comment: "")
        case Self.opensymbols.rawValue: return NSLocalizedString("phrase.symbol.package.opensymbols", comment: "")
        case Self.mulberry.rawValue: return NSLocalizedString("phrase.symbol.package.mulberry", comment: "")
        case Self.arasaac.rawValue: return NSLocalizedString("phrase.symbol.package.arasaac", comment: "")
        default: return source
        }
    }
}

enum CellSymbolSource: String, CaseIterable, Identifiable {
    case openSymbols
    case userPhotos

    var id: String { rawValue }
}

enum GridFieldSpanSelection: Hashable {
    case single
    case custom(GridFieldSpanInfo)

    static func == (lhs: GridFieldSpanSelection, rhs: GridFieldSpanSelection) -> Bool {
        switch (lhs, rhs) {
        case (.single, .single): return true
        case (.custom(let a), .custom(let b)): return a.rows == b.rows && a.columns == b.columns
        default: return false
        }
    }
}

struct GridFieldSpanInfo: Hashable {
    let rows: Int
    let columns: Int
}

struct BoardCellAnchor: Equatable {
    var row: Int
    var column: Int
}

enum FieldResizeEdge {
    case corner
    case end
    case bottom
}

struct BoardGridMetrics {
    var layout: BoardFoldLayout = BoardFoldLayout.compute(
        rows: 1, columns: 1, contentWidth: 1, contentHeight: 1, gap: 8, gridInset: 8, division: nil
    )
    var rows: Int = 1
    var columns: Int = 1
}

struct FieldDragState: Equatable {
    var buttonId: String
    var fromRow: Int
    var fromColumn: Int
    var translation: CGSize
    var targetRow: Int
    var targetColumn: Int
}

enum BoardSetCreationKind: String, CaseIterable, Identifiable {
    case blank
    case qwerty
    case alphabetical

    var id: String { rawValue }
}

enum BoardKeyAction: String, CaseIterable, Identifiable {
    case none = ""
    case spell = ":spell"
    case space = ":space"
    case backspace = ":backspace"
    case clear = ":clear"
    case home = ":home"
    case speak = ":speak"
    case prediction = ":prediction"

    var id: String { rawValue }

    var localizationKey: LocalizedStringKey {
        switch self {
        case .none: "boardset.cell.action.none"
        case .spell: "boardset.cell.action.spell"
        case .space: "boardset.cell.action.space"
        case .backspace: "boardset.cell.action.backspace"
        case .clear: "boardset.cell.action.clear"
        case .home: "boardset.cell.action.home"
        case .speak: "boardset.cell.action.speak"
        case .prediction: "boardset.cell.action.prediction"
        }
    }
}

enum BoardWorkspaceMode: Equatable {
    case run
    case edit
}

enum BoardSettingsTarget: String, Identifiable {
    case screen
    case page

    var id: String { rawValue }
    var forPage: Bool { self == .page }
}

enum BoardManagementSheet: String, Identifiable {
    case renameSet
    case renameBoard
    case resizeBoard
    case backgroundColor

    var id: String { rawValue }
}

enum BoardSetRoute: Equatable {
    case library
    case workspace(boardSetId: String, mode: BoardWorkspaceMode)
}

struct FieldEditActions: ViewModifier {
    var enabled: Bool
    var moveUp: () -> Void
    var moveDown: () -> Void
    var moveLeft: () -> Void
    var moveRight: () -> Void
    var wider: () -> Void
    var narrower: () -> Void
    var taller: () -> Void
    var shorter: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .accessibilityAction(named: Text("boardset.cell.move.up"), moveUp)
                .accessibilityAction(named: Text("boardset.cell.move.down"), moveDown)
                .accessibilityAction(named: Text("boardset.cell.move.left"), moveLeft)
                .accessibilityAction(named: Text("boardset.cell.move.right"), moveRight)
                .accessibilityAction(named: Text("boardset.cell.size.wider"), wider)
                .accessibilityAction(named: Text("boardset.cell.size.narrower"), narrower)
                .accessibilityAction(named: Text("boardset.cell.size.taller"), taller)
                .accessibilityAction(named: Text("boardset.cell.size.shorter"), shorter)
        } else {
            content
        }
    }
}

