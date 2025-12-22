package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.jdreioe.wingmate.domain.AudioPlayer
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AndroidAudioPlayer(private val context: Context) : AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)
    @Volatile private var isPlaying = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady.set(true)
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isPlaying = true
            }
            override fun onDone(utteranceId: String?) {
                isPlaying = false
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isPlaying = false
            }
        })
    }

    override suspend fun playFile(path: String) {
        stop()
        withContext(Dispatchers.IO) {
            val player = MediaPlayer()
            try {
                player.setDataSource(path)
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setOnCompletionListener {
                    isPlaying = false
                    it.release()
                }
                player.prepare()
                player.start()
                mediaPlayer = player
                isPlaying = true
            } catch (e: Exception) {
                e.printStackTrace()
                player.release()
                isPlaying = false
            }
        }
    }

    override suspend fun playSystemTts(text: String, voice: Voice?) {
        if (!ttsReady.get()) return
        val t = tts ?: return

        val lang = voice?.primaryLanguage ?: Locale.getDefault().toLanguageTag()
        t.language = Locale.forLanguageTag(lang)
        voice?.pitch?.let { t.setPitch(it.toFloat()) }
        voice?.rate?.let { t.setSpeechRate(it.toFloat()) }

        val params = android.os.Bundle()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "wingmate-${System.currentTimeMillis()}")
    }

    override suspend fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            tts?.stop()
            isPlaying = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun pause() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
    }

    override suspend fun resume() {
        mediaPlayer?.start()
    }

    override fun isPlaying(): Boolean = isPlaying
}
