import XCTest
import Shared
@testable import Wingmate

/// Kotlin's Communication session waits on these callbacks, so every device
/// speech request must report its end exactly once.
final class SystemTtsManagerTests: XCTestCase {
    func testStopReportsTheRequestAsNotSpoken() {
        let finished = expectation(description: "The request reported its end")
        var results: [Bool] = []

        SystemTtsManager.shared.speak(
            segments: [SpeechSegment(text: "Hello there", pauseDurationMs: 0, languageTag: nil)],
            languageTag: "en-US",
            rate: 1,
            pitch: 1
        ) { spoken in
            results.append(spoken.boolValue)
            finished.fulfill()
        }
        SystemTtsManager.shared.stop()

        wait(for: [finished], timeout: 2)
        XCTAssertEqual(results, [false])
    }

    func testRequestWithNothingToSayFinishesAsSpoken() {
        let finished = expectation(description: "The request reported its end")

        SystemTtsManager.shared.speak(
            segments: [SpeechSegment(text: "   ", pauseDurationMs: 0, languageTag: nil)],
            languageTag: "en-US",
            rate: 1,
            pitch: 1
        ) { spoken in
            XCTAssertTrue(spoken.boolValue)
            finished.fulfill()
        }

        wait(for: [finished], timeout: 1)
    }
}
