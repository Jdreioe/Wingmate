import Foundation
import AVFoundation

final class AudioRecorder: NSObject, AVAudioRecorderDelegate, AVAudioPlayerDelegate {
    private(set) var isRecording = false
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?

    func startRecording() async throws -> URL {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dir = docs.appendingPathComponent("Recordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("phrase_\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue
        ]
        recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder?.delegate = self
        recorder?.record()
        isRecording = true
        return url
    }

    func stopRecording() async throws -> URL {
        guard let r = recorder else { throw NSError(domain: "rec", code: 1) }
        r.stop()
        isRecording = false
        try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return r.url
    }

    func play(url: URL) {
        do {
            player?.stop()
            player = try AVAudioPlayer(contentsOf: url)
            player?.prepareToPlay()
            player?.play()
        } catch {
            print("Failed to play: \(error)")
        }
    }

    func stopPlayback() {
        player?.stop()
        player = nil
    }
}
