package io.github.jdreioe.wingmate.repository

import io.github.jdreioe.wingmate.db.TtsDatabase
import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.infrastructure.AzureTtsDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class TtsRepository(
    private val database: TtsDatabase,
    private val azure: AzureTtsDataSource,
    private val audioPlayer: AudioPlayer,
    private val fileStorage: FileStorage,
    private val configRepository: ConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val saidTextRepository: SaidTextRepository
) : SpeechService {

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val segment = SpeechSegment(text, 0, null)
        speakSegments(listOf(segment), voice, pitch, rate)
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        val settings = settingsRepository.get()
        if (settings.useSystemTts) {
            val text = segments.joinToString(" ") { it.text }
            audioPlayer.playSystemTts(text, voice)
            return
        }

        val text = segments.joinToString("") { it.text }
        val effectiveVoice = resolveVoice(voice, settings.primaryLanguage, pitch, rate)

        // 1. Level 1: Cache
        val hash = computeHash(text, effectiveVoice, pitch, rate)
        val cached = withContext(Dispatchers.IO) {
            database.ttsCacheQueries.selectByHash(hash).executeAsOneOrNull()
        }

        if (cached != null) {
            if (fileStorage.exists(cached.path)) {
                logger.info { "Cache HIT for hash=$hash" }
                addToHistory(text, effectiveVoice, cached.path)
                audioPlayer.playFile(cached.path)
                return
            }
        }

        // 2. Level 2: Azure
        try {
            val config = configRepository.getSpeechConfig()
            if (config != null && config.subscriptionKey.isNotBlank()) {
                 val ssml = if (segments.size > 1 || segments.any { !it.languageTag.isNullOrBlank() }) {
                    azure.generateSsml(segments, effectiveVoice)
                } else {
                    azure.generateSsml(text, effectiveVoice)
                }

                val bytes = azure.synthesize(ssml, config)
                val fileName = "${hash}.mp3"
                val path = fileStorage.saveFile(fileName, bytes)

                withContext(Dispatchers.IO) {
                    database.ttsCacheQueries.insert(
                        hash = hash,
                        path = path,
                        voice_params = "${effectiveVoice.name}|${effectiveVoice.primaryLanguage}",
                        timestamp = System.currentTimeMillis()
                    )
                }
                addToHistory(text, effectiveVoice, path)
                audioPlayer.playFile(path)
                return
            }
        } catch (e: Exception) {
            logger.error(e) { "Azure TTS failed, falling back to System" }
        }

        // 3. Level 3: Fallback
        val fullText = segments.joinToString(" ") { it.text }
        audioPlayer.playSystemTts(fullText, effectiveVoice)
    }

    override suspend fun pause() = audioPlayer.pause()
    override suspend fun stop() = audioPlayer.stop()
    override suspend fun resume() = audioPlayer.resume()
    override fun isPlaying() = audioPlayer.isPlaying()
    override fun isPaused() = false // TODO: enhance player interface

    private fun resolveVoice(voice: Voice?, uiLanguage: String, pitch: Double?, rate: Double?): Voice {
        val v = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        val effectiveLang = when {
            !v.selectedLanguage.isNullOrBlank() -> v.selectedLanguage
            !uiLanguage.isNullOrBlank() -> uiLanguage
            !v.primaryLanguage.isNullOrBlank() -> v.primaryLanguage
            else -> "en-US"
        }
        return v.copy(
            primaryLanguage = effectiveLang,
            pitch = pitch ?: v.pitch,
            rate = rate ?: v.rate
        )
    }

    private fun computeHash(text: String, voice: Voice, pitch: Double?, rate: Double?): String {
        // Simple hash for demo; in production use sha256
        return "${text.hashCode()}_${voice.name}_${voice.primaryLanguage}_${pitch}_${rate}"
    }

    private suspend fun addToHistory(text: String, voice: Voice, path: String) {
        val now = System.currentTimeMillis()
        saidTextRepository.add(SaidText(
            date = now,
            saidText = text,
            voiceName = voice.name,
            pitch = voice.pitch,
            speed = voice.rate,
            audioFilePath = path,
            createdAt = now,
            primaryLanguage = voice.primaryLanguage
        ))
    }
}
