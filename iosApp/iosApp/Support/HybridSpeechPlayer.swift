import Foundation
import AVFoundation

final class HybridSpeechPlayer: NSObject, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate {
    enum Segment {
        case audio(URL)
        case tts(String)
    }

    private let synth = AVSpeechSynthesizer()
    private var player: AVAudioPlayer?
    private var queue: [Segment] = []
    private var language: String = Locale.current.identifier
    private(set) var isPlaying: Bool = false

    override init() {
        super.init()
        synth.delegate = self
    }

    func play(segments: [Segment], language: String) {
        stop()
        self.queue = segments
        self.language = language
        isPlaying = true
        playNext()
    }

    func pause() {
        if player?.isPlaying == true { player?.pause() }
        if synth.isSpeaking { synth.pauseSpeaking(at: .immediate) }
        isPlaying = false
    }

    func stop() {
        player?.stop(); player = nil
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        queue.removeAll()
        isPlaying = false
    }

    private func playNext() {
        guard !queue.isEmpty else { isPlaying = false; return }
        let item = queue.removeFirst()
        switch item {
        case .audio(let url):
            do {
                player = try AVAudioPlayer(contentsOf: url)
                player?.delegate = self
                player?.prepareToPlay()
                player?.play()
            } catch {
                // Failed to play audio; skip to next
                playNext()
            }
        case .tts(let text):
            let utt = AVSpeechUtterance(string: text)
            utt.voice = AVSpeechSynthesisVoice(language: language)
            utt.rate = AVSpeechUtteranceDefaultSpeechRate
            synth.speak(utt)
        }
    }

    // MARK: - Delegates
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        playNext()
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        playNext()
    }
}
