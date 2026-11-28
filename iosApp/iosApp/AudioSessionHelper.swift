import Foundation
import AVFAudio
import Shared

/// Thin seam over `AVAudioSession` so iOS audio-preview routing can be unit-tested without hardware.
protocol AudioSessionBackend {
    var currentOutputPortTypes: [AVAudioSession.Port] { get }
    var currentCategory: AVAudioSession.Category { get }
    func setCategory(
        _ category: AVAudioSession.Category,
        mode: AVAudioSession.Mode,
        options: AVAudioSession.CategoryOptions
    ) throws
    func overrideOutputAudioPort(_ portOverride: AVAudioSession.PortOverride) throws
    func setActive(_ active: Bool, options: AVAudioSession.SetActiveOptions) throws
}

final class SystemAudioSessionBackend: AudioSessionBackend {
    private let session: AVAudioSession

    init(session: AVAudioSession = .sharedInstance()) {
        self.session = session
    }

    var currentOutputPortTypes: [AVAudioSession.Port] {
        session.currentRoute.outputs.map(\.portType)
    }

    var currentCategory: AVAudioSession.Category {
        session.category
    }

    func setCategory(
        _ category: AVAudioSession.Category,
        mode: AVAudioSession.Mode,
        options: AVAudioSession.CategoryOptions
    ) throws {
        try session.setCategory(category, mode: mode, options: options)
    }

    func overrideOutputAudioPort(_ portOverride: AVAudioSession.PortOverride) throws {
        try session.overrideOutputAudioPort(portOverride)
    }

    func setActive(_ active: Bool, options: AVAudioSession.SetActiveOptions) throws {
        try session.setActive(active, options: options)
    }
}

/// iOS `SpeechAudioRouter` backed by `AVAudioSession` (#406).
///
/// Keeps standard `.playback` untouched when the preference is disabled or when only a single
/// audio output exists. When headphones/earpiece and the built-in speaker are both available and
/// the Communicator enables private preview routing, previews stay on headphones/earpiece while
/// primary spoken Messages override output to the speaker, restoring cleanly after each utterance.
final class IosAudioSessionRouter: NSObject, SpeechAudioRouter {
    static let shared = IosAudioSessionRouter()
    static let duckedPreviewVolume: Float = 0.55

    static let privateOutputPortTypes: Set<AVAudioSession.Port> = [
        .headphones,
        .bluetoothA2DP,
        .bluetoothHFP,
        .bluetoothLE,
        .usbAudio,
    ]

    private let backend: any AudioSessionBackend
    private let hasBuiltInSpeaker: Bool
    private let dispatchOffMainThread: Bool
    private let lock = NSLock()
    private var routeModified = false

    init(
        backend: any AudioSessionBackend = SystemAudioSessionBackend(),
        hasBuiltInSpeaker: Bool = true,
        dispatchOffMainThread: Bool = true
    ) {
        self.backend = backend
        self.hasBuiltInSpeaker = hasBuiltInSpeaker
        self.dispatchOffMainThread = dispatchOffMainThread
        super.init()
    }

    func currentCapabilities() -> AudioOutputCapabilities {
        let outputs = backend.currentOutputPortTypes
        let hasPrivate = outputs.contains { Self.privateOutputPortTypes.contains($0) }
        let hasSpeaker = hasBuiltInSpeaker || outputs.contains(.builtInSpeaker)
        return AudioOutputCapabilities(
            hasHeadphonesOrEarpiece: hasPrivate,
            hasSpeaker: hasSpeaker
        )
    }

    func prepareRoute(decision: AudioRouteDecision) {
        applyRoute(decision)
    }

    func restoreRoute() {
        runSessionWork {
            self.lock.lock()
            let shouldRestore = self.routeModified
            self.routeModified = false
            self.lock.unlock()
            guard shouldRestore else { return }

            do {
                try self.backend.overrideOutputAudioPort(.none)
                try self.backend.setCategory(.playback, mode: .default, options: [])
                try self.backend.setActive(true, options: [])
            } catch {
                print("AudioSessionHelper restore error: \(error.localizedDescription)")
            }
        }
    }

    func activate(for decision: AudioRouteDecision) {
        if decision.requiresRouteOverride || decision.duckAudio {
            applyRoute(decision)
        } else {
            activateDefaultPlayback(respectActiveOverride: false)
        }
    }

    func activateDefaultPlayback(respectActiveOverride: Bool = true) {
        runSessionWork {
            if respectActiveOverride {
                self.lock.lock()
                let isOverridden = self.routeModified
                self.lock.unlock()
                if isOverridden { return }
            }
            do {
                if self.backend.currentCategory != .playback {
                    try self.backend.setCategory(.playback, mode: .default, options: [])
                }
                try self.backend.setActive(true, options: [])
            } catch {
                print("AudioSessionHelper error: \(error.localizedDescription)")
            }
        }
    }

    var hasActiveOverride: Bool {
        lock.lock()
        defer { lock.unlock() }
        return routeModified
    }

    private func applyRoute(_ decision: AudioRouteDecision) {
        runSessionWork {
            do {
                switch decision.route {
                case .speaker:
                    try self.backend.setCategory(
                        .playAndRecord,
                        mode: .default,
                        options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP]
                    )
                    try self.backend.overrideOutputAudioPort(.speaker)
                    try self.backend.setActive(true, options: [])
                    self.setRouteModified(true)

                case .headphonesOrEarpiece:
                    let outputs = self.backend.currentOutputPortTypes
                    let isHfpOnly = outputs.contains(.bluetoothHFP) && !outputs.contains {
                        $0 == .headphones || $0 == .bluetoothA2DP || $0 == .bluetoothLE || $0 == .usbAudio
                    }
                    if isHfpOnly {
                        try self.backend.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth])
                    } else {
                        try self.backend.setCategory(.playback, mode: .default, options: [])
                    }
                    try self.backend.overrideOutputAudioPort(.none)
                    try self.backend.setActive(true, options: [])
                    self.setRouteModified(true)

                case .systemDefault:
                    if decision.duckAudio {
                        try self.backend.setCategory(.playback, mode: .default, options: [.duckOthers])
                        try self.backend.overrideOutputAudioPort(.none)
                        try self.backend.setActive(true, options: [])
                        self.setRouteModified(true)
                    } else {
                        if self.backend.currentCategory != .playback {
                            try self.backend.setCategory(.playback, mode: .default, options: [])
                        }
                        try self.backend.setActive(true, options: [])
                    }

                default:
                    if self.backend.currentCategory != .playback {
                        try self.backend.setCategory(.playback, mode: .default, options: [])
                    }
                    try self.backend.setActive(true, options: [])
                }
            } catch {
                // Fall back gracefully to standard playback so speech never fails or drops.
                print("AudioSessionHelper route fallback: \(error.localizedDescription)")
                try? self.backend.overrideOutputAudioPort(.none)
                try? self.backend.setCategory(.playback, mode: .default, options: [])
                try? self.backend.setActive(true, options: [])
            }
        }
    }

    private func setRouteModified(_ value: Bool) {
        lock.lock()
        routeModified = value
        lock.unlock()
    }

    // Activate the playback session off the main thread. On macOS (iOS-on-Mac / Catalyst),
    // AVAudioSession.setActive on the main thread logs:
    //   SessionCore_macOS_Legacy.mm:822 This method can lead to UI unresponsiveness...
    // and can stall the UI. We run it on a background queue and wait so callers still
    // get a synchronous guarantee that activation finished before audio starts.
    private func runSessionWork(_ work: @escaping () -> Void) {
        guard dispatchOffMainThread && Thread.isMainThread else {
            work()
            return
        }
        let semaphore = DispatchSemaphore(value: 0)
        DispatchQueue.global(qos: .userInitiated).async {
            work()
            semaphore.signal()
        }
        semaphore.wait()
    }
}

enum AudioSessionHelper {
    static var duckedPreviewVolume: Float { IosAudioSessionRouter.duckedPreviewVolume }

    static func activatePlayback() {
        IosAudioSessionRouter.shared.activateDefaultPlayback(respectActiveOverride: true)
    }

    static func activate(for decision: AudioRouteDecision) {
        IosAudioSessionRouter.shared.activate(for: decision)
    }
}
