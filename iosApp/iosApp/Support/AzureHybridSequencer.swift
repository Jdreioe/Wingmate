import Foundation
import AVFoundation

final class AzureHybridSequencer: NSObject, AVAudioPlayerDelegate {
    enum Segment {
        case audio(URL)
        case tts(String)
    }

    private var player: AVAudioPlayer?
    private var task: Task<Void, Never>?
    private(set) var isRunning: Bool = false

    // Azure controls
    private let speakAzure: (String) async -> Void
    private let pauseAzure: () async -> Void
    private let stopAzure: () async -> Void

    init(speak: @escaping (String) async -> Void,
         pause: @escaping () async -> Void,
         stop: @escaping () async -> Void) {
        self.speakAzure = speak
        self.pauseAzure = pause
        self.stopAzure = stop
        super.init()
    }

    func play(segments: [Segment]) {
        stop()
        isRunning = true
        task = Task { [weak self] in
            guard let self = self else { return }
            for seg in segments {
                if Task.isCancelled { break }
                switch seg {
                case .audio(let url):
                    do { try await self.playAudio(url: url) } catch { /* skip */ }
                case .tts(let text):
                    await self.speakAzure(text)
                }
            }
            await MainActor.run { self.isRunning = false }
        }
    }

    func pause() {
        if player?.isPlaying == true { player?.pause() }
        Task { await pauseAzure() }
        isRunning = false
    }

    func stop() {
        task?.cancel(); task = nil
        player?.stop(); player = nil
        Task { await stopAzure() }
        isRunning = false
    }

    // MARK: - Private
    private func playAudio(url: URL) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            do {
                let p = try AVAudioPlayer(contentsOf: url)
                self.player = p
                p.delegate = self
                p.prepareToPlay()
                p.play()
                self.completion = { continuation.resume() }
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    private var completion: (() -> Void)?

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        completion?(); completion = nil
    }
}
