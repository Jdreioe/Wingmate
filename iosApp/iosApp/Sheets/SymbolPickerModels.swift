import Foundation
import Shared

struct OpenSymbolsSymbolResult {
    let id: String
    let name: String
    let image_url: String?
    let source: String
}

enum SymbolPackageFilter: String, CaseIterable, Identifiable {
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

enum PhraseSymbolSource: String, CaseIterable, Identifiable {
    case openSymbols
    case userPhotos

    var id: String { rawValue }
}

/// Searches with `IosViewModel.searchSymbols`. Cancellation returns no results and no error.
func searchSymbols(
    _ query: String,
    packageFilter: SymbolPackageFilter,
    using symbolSearch: SymbolSearch
) async -> (results: [OpenSymbolsSymbolResult], errorCode: String?) {
    guard let result = await symbolSearch(query, packageFilter.rawValue) else {
        return ([], nil)
    }
    if result.isSuccess {
        let mapped = result.symbols.map {
            OpenSymbolsSymbolResult(id: $0.id, name: $0.name, image_url: $0.imageUrl, source: $0.source)
        }
        return (mapped, nil)
    }
    return ([], symbolSearchErrorCode(result.status))
}

/// A symbol search by query and package wire value; nil when cancelled.
typealias SymbolSearch = (_ query: String, _ symbolPackage: String) async -> Shared.SymbolSearchResult?

/// The Localizable.strings suffix for a failed symbol search.
func symbolSearchErrorCode(_ status: Shared.SymbolSearchStatus) -> String {
    // Kotlin/Native lowercases a PascalCase enum entry whole: NotConfigured -> notconfigured.
    status == .notconfigured ? "missing_proxy" : "search_failed"
}

