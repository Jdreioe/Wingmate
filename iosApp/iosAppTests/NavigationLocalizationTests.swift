import XCTest

final class NavigationLocalizationTests: XCTestCase {
    func testPageNavigationLabelIncludesThePageNameInEveryLanguage() throws {
        for locale in ["Base", "da", "de"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: "Localizable", ofType: "strings", inDirectory: "\(locale).lproj"))
            let strings = try XCTUnwrap(NSDictionary(contentsOfFile: path) as? [String: String])
            let format = try XCTUnwrap(strings["board_cell_opens_board"])
            let expectedPrefix = ["Base": "Opens page: ", "da": "Åbner side: ", "de": "Öffnet Seite: "][locale]!
            XCTAssertEqual(String(format: format, "Test page"), expectedPrefix + "Test page", locale)
        }
    }
}
