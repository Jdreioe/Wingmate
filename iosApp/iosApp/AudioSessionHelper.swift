import Foundation
import AVFAudio

enum AudioSessionHelper {
    // Activate the playback session off the main thread. On macOS (iOS-on-Mac / Catalyst),
    // AVAudioSession.setActive on the main thread logs:
    //   SessionCore_macOS_Legacy.mm:822 This method can lead to UI unresponsiveness...
    // and can stall the UI. We run it on a background queue and wait so callers still
    // get a synchronous guarantee that activation finished before audio starts.
    static func activatePlayback() {
        let session = AVAudioSession.sharedInstance()
        let semaphore = DispatchSemaphore(value: 0)
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                if session.category != .playback {
                    try session.setCategory(.playback)
                }
                try session.setActive(true, options: [])
            } catch {
                print("AudioSessionHelper error: \(error.localizedDescription)")
            }
            semaphore.signal()
        }
        semaphore.wait()
    }
}
