import Foundation
import AVFAudio

enum AudioSessionHelper {
    static func activatePlayback() {
        let session = AVAudioSession.sharedInstance()
        do {
            if session.category != .playback {
                try session.setCategory(.playback)
            }
            try session.setActive(true, options: [])
        } catch {
            print("AudioSessionHelper error: \(error.localizedDescription)")
        }
    }
}
