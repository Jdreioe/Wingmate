import XCTest
@testable import Wingmate

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

    func testWelcomeSetupTreatsAllSpeechProvidersEqually() {
        let providers = WelcomeTtsProvider.allCases
        XCTAssertEqual(providers, [.system, .azure, .google, .elevenLabs])

        for provider in providers {
            XCTAssertFalse(provider.title.isEmpty)
            XCTAssertFalse(provider.subtitle.isEmpty)
            XCTAssertFalse(provider.icon.isEmpty)
            XCTAssertFalse(provider.pros.isEmpty)
            XCTAssertFalse(provider.cons.isEmpty)
            XCTAssertFalse(provider.voicesSubtitle.isEmpty)
            XCTAssertTrue(provider.matches(engineId: provider.engineId))
            XCTAssertEqual(WelcomeTtsProvider.from(engineId: provider.engineId), provider)
            XCTAssertEqual(provider.configureTitle != nil, provider.requiresSetup)
        }

        XCTAssertTrue(WelcomeTtsProvider.azure.matches(engineId: "AZURE_MANAGED"))
        XCTAssertEqual(WelcomeTtsProvider.from(engineId: "AZURE_MANAGED"), .azure)
    }
}
