package io.github.jdreioe.wingmate.infrastructure

import android.Manifest
import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.jdreioe.wingmate.domain.AudioRouteDecision
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechPlaybackState
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceProvider
import io.github.jdreioe.wingmate.domain.resolvedProvider
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.ElevenLabsSpeechConfig
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfig
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.os.ParcelFileDescriptor
import android.os.Environment
import androidx.annotation.RequiresPermission
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

internal fun AndroidSpeechService.onTtsUtteranceFinished(utteranceId: String?, failed: Boolean = false) {
    val requestId = utteranceId?.substringAfter("wingmate-", "")
        ?.substringBefore('-')?.toLongOrNull() ?: return
    if (requestGeneration.get() != requestId) return
    // Only after the request check: a late error from a replaced request must not fail this one.
    if (failed) ttsUtteranceFailed.set(true)
    val remaining = pendingTtsUtterances.updateAndGet { count -> if (count > 0) count - 1 else 0 }
    if (remaining == 0) {
        // An engine error (e.g. voice data not installed) ends the request as FAILED, not done.
        finishPlayback(requestId, failed = ttsUtteranceFailed.get())
    }
}

internal suspend fun AndroidSpeechService.ensureTts(enginePackageName: String? = null): TextToSpeech = withContext(Dispatchers.Main) {
    val normalizedEngine = enginePackageName?.takeIf { it.isNotBlank() }
    val cur = tts
    if (cur != null && activeTtsEnginePackage == normalizedEngine) {
        val ready = ttsInitialization?.let { awaitSpeechInitialization(it, 5_000) } == true
        check(ready) { "Device text-to-speech did not become ready" }
        return@withContext cur
    }

    if (cur != null) {
        try {
            cur.stop()
        } catch (_: Throwable) {
            
        }
        try {
            cur.shutdown()
        } catch (_: Throwable) {
            
        }
        tts = null
    }

    val initialization = CompletableDeferred<Boolean>()
    ttsInitialization = initialization
    val created = if (normalizedEngine.isNullOrBlank()) {
        TextToSpeech(context) { status ->
            initialization.complete(status == TextToSpeech.SUCCESS)
        }
    } else {
        TextToSpeech(context, { status ->
            initialization.complete(status == TextToSpeech.SUCCESS)
        }, normalizedEngine)
    }

    created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val requestId = utteranceId?.substringAfter("wingmate-", "")
                ?.substringBefore('-')?.toLongOrNull()
            if (requestId == requestGeneration.get()) {
                updatePlaybackState(PlaybackState.STATE_PLAYING)
            }
        }

        override fun onDone(utteranceId: String?) {
            onTtsUtteranceFinished(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onTtsUtteranceFinished(utteranceId, failed = true)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onTtsUtteranceFinished(utteranceId, failed = true)
        }
    })

    tts = created
    activeTtsEnginePackage = normalizedEngine
    val ready = awaitSpeechInitialization(initialization, 5_000)
    if (!ready) {
        if (tts === created) {
            tts = null
            activeTtsEnginePackage = null
            ttsInitialization = null
        }
        runCatching { created.shutdown() }
        error("Device text-to-speech failed to initialize")
    }
    created
}

internal suspend fun AndroidSpeechService.speakWithPlatformTts(requestId: Long, text: String, voice: Voice?, pitch: Double?, rate: Double?) {
    // System TTS reads markup aloud, so strip SSML and audio tags.
    val cleanText = SpeechTextProcessor.stripAudioTags(text.replace(Regex("<[^>]+>"), ""))
    withContext(Dispatchers.Main) {
        val parsedVoiceId = AndroidTtsVoiceId.parse(voice?.name)
        val t = ensureTts(parsedVoiceId.enginePackageName)
        val lang = voice?.primaryLanguage ?: Locale.getDefault().toLanguageTag()
        applyPlatformVoiceSettings(
            ttsEngine = t,
            languageTag = lang,
            requestedVoiceName = parsedVoiceId.voiceName,
        )
        t.setPitch((pitch ?: 1.0).toFloat())
        t.setSpeechRate((rate ?: 1.0).toFloat())
        // Route TTS through media channel to keep Android Auto routing consistent.
        try { t.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
        requestAudioFocus()
        val utteranceId = "wingmate-$requestId-${System.nanoTime()}"
        ttsUtteranceFailed.set(false)
        pendingTtsUtterances.set(1)
        val result = synchronized(playerLock) {
            check(requestGeneration.get() == requestId) { "Speech request was replaced" }
            t.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
        if (result != TextToSpeech.SUCCESS) {
            pendingTtsUtterances.set(0)
            finishPlayback(requestId)
            error("Device text-to-speech rejected playback")
        }
        // The engine can finish (or fail) before speak() returns; never mark that as playing.
        if (pendingTtsUtterances.get() > 0) markPlaybackStarted(cleanText, voice, requestId)
    }
}

/**
 * Internal helper to record speech history for all playback paths
 */

internal suspend fun AndroidSpeechService.playSegmentsWithPlatformTts(
    requestId: Long,
    messageSegments: List<SpeechSegment>,
    voice: Voice?,
    pitch: Double?,
    rate: Double?,
    audioRoute: AudioRouteDecision = AudioRouteDecision.SystemDefault,
) {
    val segments = SpeechTextProcessor.segmentsFor(TtsEngine.SYSTEM, messageSegments)
    withContext(Dispatchers.Main) {
        val parsedVoiceId = AndroidTtsVoiceId.parse(voice?.name)
        val t = ensureTts(parsedVoiceId.enginePackageName)
        val attributes = if (audioRoute.requiresRouteOverride) {
            audioRouter.audioAttributesFor(audioRoute)
        } else {
            ttsAudioAttributes
        }
        try { t.setAudioAttributes(attributes) } catch (_: Throwable) {}
        requestAudioFocus(attributes)

        val combinedText = segments.joinToString(" ") { it.text }.trim()
        ttsUtteranceFailed.set(false)
        pendingTtsUtterances.set(0)
        check(requestGeneration.get() == requestId) { "Speech request was replaced" }
        
        // Iterate segments to handle breaks correctly for platform TTS
        for ((index, segment) in segments.withIndex()) {
            if (!isPlaying || pausedAtSegment) break
            
            currentSegmentIndex = index
            
            // 1. Speak the text (if any)
            if (segment.text.isNotEmpty()) {
                val segmentVoice = voice.applyLanguageOverride(segment.languageTag)
                
                // Apply voice params for this segment
                val lang = segmentVoice?.primaryLanguage ?: Locale.getDefault().toLanguageTag()
                applyPlatformVoiceSettings(
                    ttsEngine = t,
                    languageTag = lang,
                    requestedVoiceName = parsedVoiceId.voiceName,
                )
                t.setPitch((pitch ?: 1.0).toFloat())
                t.setSpeechRate((rate ?: 1.0).toFloat())
                
                // Queue the speech
                val params = android.os.Bundle()
                val utteranceId = "wingmate-$requestId-$index-${System.nanoTime()}"
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                if (audioRoute.duckAudio) {
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, audioRouter.volumeFor(audioRoute))
                }
                
                // Use QUEUE_ADD to chain segments seamlessly
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                pendingTtsUtterances.incrementAndGet()
                val speakResult = synchronized(playerLock) {
                    check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                    t.speak(segment.text, queueMode, params, utteranceId)
                }
                if (speakResult != TextToSpeech.SUCCESS) {
                    onTtsUtteranceFinished(utteranceId)
                    error("Device text-to-speech rejected a speech segment")
                }
            }
            
            // 2. Play silence for break (if any)
            if (segment.pauseDurationMs > 0) {
                val silenceId = "wingmate-$requestId-silence-$index-${System.nanoTime()}"
                pendingTtsUtterances.incrementAndGet()
                val silenceResult = synchronized(playerLock) {
                    check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                    t.playSilentUtterance(
                        segment.pauseDurationMs,
                        TextToSpeech.QUEUE_ADD,
                        silenceId
                    )
                }
                if (silenceResult != TextToSpeech.SUCCESS) {
                    onTtsUtteranceFinished(silenceId)
                    error("Device text-to-speech rejected a pause segment")
                }
            }
            
            // Note: We cannot easily delay() here because t.speak is asynchronous and non-blocking.
            // The queuing handles the timing. But for UI sync (highlighting), we're limited.
            // Since this method was previously using delay() which blocked the coroutine but not the TTS, 
            // we'll remove manual delay to let TTS handle timing properly.
        }

        if (pendingTtsUtterances.get() == 0) {
            finishPlayback(requestId, failed = ttsUtteranceFailed.get())
        } else {
            markPlaybackStarted(combinedText, voice, requestId)
        }
    }
}

internal fun AndroidSpeechService.applyPlatformVoiceSettings(
    ttsEngine: TextToSpeech,
    languageTag: String,
    requestedVoiceName: String?,
) {
    val locale = Locale.forLanguageTag(languageTag)
    runCatching { ttsEngine.setLanguage(locale) }

    if (requestedVoiceName.isNullOrBlank()) {
        return
    }

    val availableVoices = runCatching { ttsEngine.voices }.getOrNull().orEmpty()
    val selectedVoice = availableVoices.firstOrNull { it.name == requestedVoiceName } ?: return
    runCatching { ttsEngine.setVoice(selectedVoice) }
}

internal fun AndroidSpeechService.shouldUseDefaultAzureVoice(voice: Voice?): Boolean {
    val voiceName = voice?.name
    if (voiceName.isNullOrBlank()) return true
    if (voiceName == "system-default") return true
    if (voice.resolvedProvider() == VoiceProvider.GOOGLE) return true

    val parsedVoiceId = AndroidTtsVoiceId.parse(voiceName)
    return parsedVoiceId.enginePackageName != null
}

internal fun AndroidSpeechService.normalizeVoiceForAzure(voice: Voice?): Voice {
    if (!shouldUseDefaultAzureVoice(voice) && voice != null) {
        return voice
    }

    val base = voice ?: Voice()
    return base.copy(
        name = AndroidSpeechService.DEFAULT_AZURE_VOICE_NAME,
        primaryLanguage = base.primaryLanguage ?: AndroidSpeechService.DEFAULT_AZURE_LANGUAGE,
    )
}


internal suspend fun AndroidSpeechService.getConfig(): SpeechServiceConfig? {
    val koin = GlobalContext.getOrNull()
    val repo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.ConfigRepository>() }.getOrNull() }

    val storedConfig = if (repo != null) {
        try {
            withContext(Dispatchers.IO) { repo.getSpeechConfig() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    if (storedConfig?.endpoint?.isNotBlank() == true && storedConfig.subscriptionKey.isNotBlank()) {
        return storedConfig
    }

    // Return any partially saved config so settings screens can still show persisted values.
    if (storedConfig != null) {
        return storedConfig
    }

    return null
}

internal suspend fun AndroidSpeechService.getGoogleConfig(): GoogleSpeechConfig? {
    val repository = GlobalContext.getOrNull()?.let {
        runCatching { it.get<io.github.jdreioe.wingmate.domain.ConfigRepository>() }.getOrNull()
    } ?: return null
    return try {
        withContext(Dispatchers.IO) { repository.getGoogleSpeechConfig() }
            ?.takeIf { it.apiKey.isNotBlank() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}

internal suspend fun AndroidSpeechService.getElevenLabsConfig(): ElevenLabsSpeechConfig? {
    val repository = GlobalContext.getOrNull()?.let {
        runCatching { it.get<io.github.jdreioe.wingmate.domain.ConfigRepository>() }.getOrNull()
    } ?: return null
    return try {
        withContext(Dispatchers.IO) { repository.getElevenLabsSpeechConfig() }
            ?.takeIf { it.apiKey.isNotBlank() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}

internal fun AndroidSpeechService.normalizeVoiceForGoogle(voice: Voice?, primaryLanguage: String?): Voice {
    val base = voice?.takeIf { it.resolvedProvider() == VoiceProvider.GOOGLE } ?: Voice()
    val language = base.selectedLanguage.takeIf(String::isNotBlank)
        ?: primaryLanguage?.takeIf(String::isNotBlank)
        ?: base.primaryLanguage?.takeIf(String::isNotBlank)
        ?: "en-US"
    return base.copy(primaryLanguage = language, selectedLanguage = language, mathMode = false)
}

internal fun Voice?.applyLanguageOverride(languageTag: String?): Voice? {
    if (languageTag.isNullOrBlank()) return this
    val base = this ?: Voice(name = "en-US-JennyNeural", primaryLanguage = languageTag, selectedLanguage = languageTag)
    return base.copy(
        selectedLanguage = languageTag,
        primaryLanguage = languageTag
    )
}
