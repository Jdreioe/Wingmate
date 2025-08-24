import Foundation
import AVFoundation

final class SystemTtsManager: NSObject, AVSpeechSynthesizerDelegate {
    static let shared = SystemTtsManager()
    private let synth = AVSpeechSynthesizer()

    private override init() {
        super.init()
        synth.delegate = self
    }

    func speak(_ text: String, language: String?) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let utterance = AVSpeechUtterance(string: text)
        if let lang = language, let voice = AVSpeechSynthesisVoice(language: lang) {
            utterance.voice = voice
        }
        // Reasonable defaults; users can adjust in iOS settings
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
    try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true, options: [])
        synth.speak(utterance)
    }

    func pause() {
        _ = synth.pauseSpeaking(at: .immediate)
    }

    func stop() {
        synth.stopSpeaking(at: .immediate)
    }
}
