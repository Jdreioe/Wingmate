import XCTest
@testable import Wingmate

final class AzureHybridSequencerTests: XCTestCase {
    func testTtsSegmentsAreSpokenInOrder() async {
        let spokenTexts = SpokenTextLog()
        let spokeTwice = expectation(description: "Both TTS segments were spoken")
        spokeTwice.expectedFulfillmentCount = 2

        let sequencer = AzureHybridSequencer(
            speak: { text in
                await spokenTexts.append(text)
                spokeTwice.fulfill()
            },
            pause: {},
            stop: {}
        )

        sequencer.play(segments: [.tts("hello"), .tts("world")])

        await fulfillment(of: [spokeTwice], timeout: 1)
        let result = await spokenTexts.values
        XCTAssertEqual(result, ["hello", "world"])
    }
}

private actor SpokenTextLog {
    private var storage: [String] = []

    func append(_ text: String) {
        storage.append(text)
    }

    var values: [String] {
        storage
    }
}
