package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import platform.AVFoundation.AVSpeechBoundary
import platform.AVFoundation.AVSpeechSynthesisVoice
import platform.AVFoundation.AVSpeechSynthesizer
import platform.AVFoundation.AVSpeechUtterance
import platform.AVFoundation.AVSpeechUtteranceDefaultSpeechRate

class IosSpeechService : SpeechService {
    private val log = LoggerFactory.getLogger("IosSpeechService")
    private val synthesizer = AVSpeechSynthesizer()

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)

        val lang = voice?.selectedLanguage?.ifBlank { null } ?: voice?.primaryLanguage?.ifBlank { null }
        val avVoice = lang?.let { AVSpeechSynthesisVoice.voiceWithLanguage(it) }
        if (avVoice != null) utterance.voice = avVoice

        if (pitch != null) utterance.pitchMultiplier = pitch.coerceIn(0.5, 2.0).toFloat()
        if (rate != null) {
            val base = AVSpeechUtteranceDefaultSpeechRate
            utterance.rate = (base * rate.coerceIn(0.1, 2.0)).toFloat().coerceIn(0.1f, 0.6f)
        }

        log.info("Speaking on iOS: '{}' (lang={})", text.take(40), lang ?: "<default>")
        synthesizer.speakUtterance(utterance)
    }

    override suspend fun pause() = withContext(Dispatchers.Main) {
        if (synthesizer.speaking) synthesizer.pauseSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }
}
