import XCTest
@testable import Wingmate

final class ScreenButtonTextTests: XCTestCase {
    func testExpandedTextShowsWholeParagraphForShortenedSpeechCue() {
        let paragraph = "Wingmate is a free and open source AAC app for iPhone and iPad that is developed by me, who uses AAC on a daily basis."
        let ellipsisCue = "Wingmate is a free and open source AAC app for iPhone and iPad that is…"
        let asciiCue = "Wingmate is a free and open source AAC app for iPhone and iPad that is..."

        XCTAssertEqual(ScreenButtonText.expandedText(label: ellipsisCue, vocalization: paragraph), paragraph)
        XCTAssertEqual(ScreenButtonText.expandedText(label: asciiCue, vocalization: paragraph), paragraph)
    }

    func testExpandedTextKeepsUnrelatedOrUnshortenedLabelsAsWritten() {
        XCTAssertNil(ScreenButtonText.expandedText(label: "Hej", vocalization: "Hej"))
        XCTAssertNil(ScreenButtonText.expandedText(label: "More…", vocalization: "Something else entirely"))
        XCTAssertNil(ScreenButtonText.expandedText(label: "Wait…", vocalization: nil))
    }

    func testIsShortLabelDistinguishesWordsFromSentences() {
        XCTAssertTrue(ScreenButtonText.isShortLabel("Yes"))
        XCTAssertTrue(ScreenButtonText.isShortLabel("all done"))
        XCTAssertTrue(ScreenButtonText.isShortLabel("Hvordan går det?"))
        XCTAssertFalse(ScreenButtonText.isShortLabel("Wingmate has two separate workspaces for communication, that syncs. Screens and Typing."))
    }
}
