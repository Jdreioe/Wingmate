import XCTest
import AVFAudio
import Shared
@testable import Wingmate

final class IosAudioSessionRouterTests: XCTestCase {
    func testHeadphonesAndSpeakerExposeDualOutputsAndRoutePreviewVsMessage() {
        let backend = FakeAudioSessionBackend(outputs: [.bluetoothA2DP])
        let router = IosAudioSessionRouter(
            backend: backend,
            hasBuiltInSpeaker: true,
            dispatchOffMainThread: false
        )

        let capabilities = router.currentCapabilities()
        XCTAssertTrue(capabilities.hasHeadphonesOrEarpiece)
        XCTAssertTrue(capabilities.hasSpeaker)

        let previewDecision = AudioPreviewRoutingPolicy.shared.resolve(
            role: .auditoryPreview,
            routePreviewToHeadphones: true,
            capabilities: capabilities
        )
        router.prepareRoute(decision: previewDecision)
        XCTAssertEqual(backend.lastCategory, .playback)
        XCTAssertEqual(backend.lastOverride, .none)
        XCTAssertTrue(router.hasActiveOverride)

        let speakerDecision = AudioPreviewRoutingPolicy.shared.resolve(
            role: .primaryMessage,
            routePreviewToHeadphones: true,
            capabilities: capabilities
        )
        router.prepareRoute(decision: speakerDecision)
        XCTAssertEqual(backend.lastCategory, .playAndRecord)
        XCTAssertEqual(backend.lastOverride, .speaker)

        router.restoreRoute()
        XCTAssertFalse(router.hasActiveOverride)
        XCTAssertEqual(backend.lastCategory, .playback)
        XCTAssertEqual(backend.lastOverride, .none)
    }

    func testSpeakerOnlySingleOutputFallbackDucksPreviewAndRestoresCleanly() {
        let backend = FakeAudioSessionBackend(outputs: [.builtInSpeaker])
        let router = IosAudioSessionRouter(
            backend: backend,
            hasBuiltInSpeaker: true,
            dispatchOffMainThread: false
        )

        let capabilities = router.currentCapabilities()
        XCTAssertFalse(capabilities.hasHeadphonesOrEarpiece)
        XCTAssertTrue(capabilities.hasSpeaker)

        let previewDecision = AudioPreviewRoutingPolicy.shared.resolve(
            role: .auditoryPreview,
            routePreviewToHeadphones: true,
            capabilities: capabilities
        )
        XCTAssertEqual(previewDecision.route, .systemDefault)
        XCTAssertTrue(previewDecision.duckAudio)

        router.prepareRoute(decision: previewDecision)
        XCTAssertEqual(backend.lastCategory, .playback)
        XCTAssertTrue(backend.lastOptions.contains(.duckOthers))
        XCTAssertEqual(backend.lastOverride, .none)

        router.restoreRoute()
        XCTAssertFalse(router.hasActiveOverride)
        XCTAssertEqual(backend.lastCategory, .playback)
        XCTAssertFalse(backend.lastOptions.contains(.duckOthers))
    }

    func testBluetoothHfpEarpieceUsesPlayAndRecordAllowBluetoothForPreview() {
        let backend = FakeAudioSessionBackend(outputs: [.bluetoothHFP])
        let router = IosAudioSessionRouter(
            backend: backend,
            hasBuiltInSpeaker: true,
            dispatchOffMainThread: false
        )

        router.prepareRoute(decision: AudioRouteDecision(
            route: .headphonesOrEarpiece,
            duckAudio: false,
            requiresRouteOverride: true
        ))

        XCTAssertEqual(backend.lastCategory, .playAndRecord)
        XCTAssertTrue(backend.lastOptions.contains(.allowBluetooth))
        XCTAssertEqual(backend.lastOverride, .none)
    }

    func testAudioSessionFailureFallsBackGracefullyWithoutCrashing() {
        let backend = FakeAudioSessionBackend(outputs: [.headphones])
        backend.failSpeakerOverride = true
        let router = IosAudioSessionRouter(
            backend: backend,
            hasBuiltInSpeaker: true,
            dispatchOffMainThread: false
        )

        router.prepareRoute(decision: AudioRouteDecision(
            route: .speaker,
            duckAudio: false,
            requiresRouteOverride: true
        ))

        XCTAssertEqual(backend.lastCategory, .playback)
        XCTAssertEqual(backend.lastOverride, .none)
    }
}

private enum FakeAudioSessionError: Error {
    case overrideUnsupported
}

private final class FakeAudioSessionBackend: AudioSessionBackend {
    var currentOutputPortTypes: [AVAudioSession.Port]
    var currentCategory: AVAudioSession.Category = .playback
    var lastCategory: AVAudioSession.Category = .playback
    var lastOptions: AVAudioSession.CategoryOptions = []
    var lastOverride: AVAudioSession.PortOverride = .none
    var failSpeakerOverride = false

    init(outputs: [AVAudioSession.Port]) {
        self.currentOutputPortTypes = outputs
    }

    func setCategory(
        _ category: AVAudioSession.Category,
        mode: AVAudioSession.Mode,
        options: AVAudioSession.CategoryOptions
    ) throws {
        currentCategory = category
        lastCategory = category
        lastOptions = options
    }

    func overrideOutputAudioPort(_ portOverride: AVAudioSession.PortOverride) throws {
        if failSpeakerOverride && portOverride == .speaker {
            throw FakeAudioSessionError.overrideUnsupported
        }
        lastOverride = portOverride
    }

    func setActive(_ active: Bool, options: AVAudioSession.SetActiveOptions) throws {}
}
