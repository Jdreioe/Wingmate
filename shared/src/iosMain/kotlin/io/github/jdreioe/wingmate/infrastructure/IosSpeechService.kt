package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.dataWithBytes

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalForeignApi::class)
class IosSpeechService(
    private val httpClient: HttpClient,
    private val configRepository: ConfigRepository
) : SpeechService {

    private var audioPlayer: AVAudioPlayer? = null

    init {
        configureAudioSession()
    }

    private fun configureAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            // Newer AVFAudio bindings prefer NSErrorPointer parameters
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                // Pass category by name to avoid constant import differences across SDKs
                session.setCategory("AVAudioSessionCategoryPlayback", error = err.ptr)
                // Some AVFAudio bindings don't expose setActive; skip if unavailable
                // iOS often auto-activates on playback; if needed, this can be handled in Swift side
                if (err.value != null) {
                    logger.error { "AVAudioSession error: ${err.value?.localizedDescription}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to configure AVAudioSession" }
        }
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        if (text.isBlank()) return

        val audioBytes = withContext(Dispatchers.Default) {
            try {
                val config = configRepository.getSpeechConfig()
                if (config == null) {
                    logger.warn { "No speech config found, cannot speak" }
                    return@withContext null
                }

                val voiceToUse = voice ?: Voice(
                    id = null,
                    name = "en-US-JennyNeural",
                    displayName = "Default Voice",
                    primaryLanguage = "en-US",
                    selectedLanguage = "en-US"
                )
                val ssml = AzureTtsClient.generateSsml(text, voiceToUse)
                val audioData = AzureTtsClient.synthesize(httpClient, ssml, config)
                logger.info { "Generated ${audioData.size} bytes of audio for text: '${text.take(40)}'" }
                audioData
            } catch (e: Exception) {
                logger.error(e) { "Failed to synthesize speech for text: '${text.take(40)}'" }
                null
            }
        }

        audioBytes ?: return

        playAudio(audioBytes)
    }

    private suspend fun playAudio(audioBytes: ByteArray) = withContext(Dispatchers.Main) {
        stop()

        val nsData = audioBytes.usePinned {
            NSData.dataWithBytes(it.addressOf(0), it.get().size.toULong())
        }

        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            audioPlayer = AVAudioPlayer(data = nsData, error = error.ptr)

            if (error.value != null) {
                logger.error { "Failed to create audio player: ${error.value?.localizedDescription}" }
                return@memScoped
            }

            audioPlayer?.play()
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main) {
        logger.info { "Pause speech on iOS" }
        if (audioPlayer?.isPlaying() == true) {
            audioPlayer?.pause()
        }
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        logger.info { "Stop speech on iOS" }
        if (audioPlayer?.isPlaying() == true) {
            audioPlayer?.stop()
        }
        audioPlayer = null
    }
}
