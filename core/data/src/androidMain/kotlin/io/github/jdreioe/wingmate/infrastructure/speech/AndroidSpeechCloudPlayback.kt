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

/** Thrown by [playCloud] when the selected cloud provider cannot be tried at all. */
internal class CloudSpeechUnavailable(val reason: Reason) : IllegalStateException("Cloud speech unavailable: $reason") {
    enum class Reason { NotConfigured, Offline }
}

internal class CloudPlayback(val voice: Voice, val cachedAudioPath: String?)

internal suspend fun AndroidSpeechService.cacheSpeechImpl(text: String, voice: Voice?, pitch: Double?, rate: Double?): Boolean {
    val normalizedText = SpeechTextProcessor.normalizeShorthandSsml(text).trim()
    if (normalizedText.isEmpty()) return true
    val settingsRepo = GlobalContext.getOrNull()?.let {
        runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull()
    }
    val settings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
    val engine = settings?.ttsEngine ?: TtsEngine.SYSTEM
    if (engine == TtsEngine.SYSTEM) return false
    if (!isOnline()) {
        return false
    }

    val cached = runCatching {
        val azureConfig = if (engine == TtsEngine.GOOGLE_CLOUD || engine == TtsEngine.ELEVENLABS) null else (getConfig() ?: return@runCatching false)
        val googleConfig = if (engine == TtsEngine.GOOGLE_CLOUD) getGoogleConfig() ?: return@runCatching false else null
        val elevenLabsConfig = if (engine == TtsEngine.ELEVENLABS) getElevenLabsConfig() ?: return@runCatching false else null
        val effectiveVoice = if (engine == TtsEngine.ELEVENLABS) {
            requireNotNull(voice).also { require(it.resolvedProvider() == VoiceProvider.ELEVENLABS) }
        } else if (engine == TtsEngine.GOOGLE_CLOUD) {
            normalizeVoiceForGoogle(voice, settings?.primaryLanguage)
        } else {
            normalizeVoiceForAzure(voice)
        }
        val language = effectiveVoice.selectedLanguage.takeIf(String::isNotBlank)
            ?: settings?.primaryLanguage?.takeIf(String::isNotBlank)
            ?: effectiveVoice.primaryLanguage?.takeIf(String::isNotBlank)
            ?: AndroidSpeechService.DEFAULT_AZURE_LANGUAGE
        val voiceForSsml = effectiveVoice.copy(primaryLanguage = language, selectedLanguage = language)
        val dictionary = runCatching {
            GlobalContext.get().get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().getAll()
        }.getOrDefault(emptyList())
        val cacheKey = SpeechCacheIdentity.speech(normalizedText, engine, voiceForSsml, pitch, rate, dictionary)
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val directory = File(root, "wingmate/audio").apply { mkdirs() }
        val file = File(directory, "tts_v2_$cacheKey.mp3")
            if (!file.exists() || file.length() == 0L) {
                val segments = SpeechTextProcessor.segmentsFor(engine, SpeechTextProcessor.processText(normalizedText))
                val bytes = if (engine == TtsEngine.ELEVENLABS) {
                    ElevenLabsTtsClient.synthesizeSegments(client, segments, voiceForSsml, elevenLabsConfig!!, rate)
                } else if (engine == TtsEngine.GOOGLE_CLOUD) {
                    GoogleTtsClient.synthesizeSegments(
                        client,
                        segments,
                        voiceForSsml,
                        googleConfig!!,
                        pitch,
                        rate,
                        applicationHeaders = googleApiRequestHeaders,
                    )
                } else {
                    val ssml = if (segments.any { !it.languageTag.isNullOrBlank() || it.pauseDurationMs > 0 }) {
                        AzureTtsClient.generateSsml(segments, voiceForSsml, dictionary)
                    } else {
                        AzureTtsClient.generateSsml(normalizedText, voiceForSsml, dictionary)
                    }
                    AzureTtsClient.synthesize(client, ssml, azureConfig!!)
                }
                file.outputStream().use { it.write(bytes) }
            }
        true
    }.getOrDefault(false)

    return cached
}

/**
 * Legacy playback for callers outside the Communication session (voice previews and the
 * iOS-shaped facade): uses the selected engine, falls back to platform TTS on its own, and
 * records History.
 */

internal suspend fun AndroidSpeechService.playCloud(
    requestId: Long,
    engine: TtsEngine,
    segments: List<SpeechSegment>,
    voice: Voice?,
    pitch: Double?,
    rate: Double?,
    cacheAudio: Boolean,
    uiSettings: io.github.jdreioe.wingmate.domain.Settings?,
    audioRoute: AudioRouteDecision = AudioRouteDecision.SystemDefault,
): CloudPlayback {
    val combinedText = segments.joinToString("") { it.text }

    // Reuse a cached audio file from history to save API calls (works even offline).
    if (cacheAudio) {
        maybePlayFromHistoryCache(requestId, combinedText, voice, pitch, rate, uiSettings?.primaryLanguage, audioRoute)
            ?.let { return it }
    }

    val azureConfig = if (engine == TtsEngine.GOOGLE_CLOUD || engine == TtsEngine.ELEVENLABS) null else getConfig()
    val googleConfig = if (engine == TtsEngine.GOOGLE_CLOUD) getGoogleConfig() else null
    val elevenLabsConfig = if (engine == TtsEngine.ELEVENLABS) getElevenLabsConfig() else null
    if (azureConfig == null && googleConfig == null && elevenLabsConfig == null) {
        throw CloudSpeechUnavailable(CloudSpeechUnavailable.Reason.NotConfigured)
    }
    if (!isOnline()) throw CloudSpeechUnavailable(CloudSpeechUnavailable.Reason.Offline)

    return withContext(Dispatchers.IO) {
        try {
            val useDefaultAzureVoice = engine != TtsEngine.GOOGLE_CLOUD && engine != TtsEngine.ELEVENLABS && shouldUseDefaultAzureVoice(voice)
            val v = if (engine == TtsEngine.ELEVENLABS) {
                requireNotNull(voice).also { require(it.resolvedProvider() == VoiceProvider.ELEVENLABS) }
            } else if (engine == TtsEngine.GOOGLE_CLOUD) {
                normalizeVoiceForGoogle(voice, uiSettings?.primaryLanguage)
            } else {
                normalizeVoiceForAzure(voice)
            }
            
            // Show warning if no Azure voice was available and we had to fallback.
            if (useDefaultAzureVoice) {
                showDefaultVoiceWarning()
            }

            // Prefer the voice's selected language, then the app's, then the voice's primary one.
            val effectiveLang =
                v.selectedLanguage.takeIf(String::isNotBlank)
                    ?: uiSettings?.primaryLanguage?.takeIf(String::isNotBlank)
                    ?: v.primaryLanguage?.takeIf(String::isNotBlank)
                    ?: "en-US"

            val vForSsml = v.copy(primaryLanguage = effectiveLang, selectedLanguage = effectiveLang)
            
            // Fetch dictionary entries to apply
            val dict = runCatching { 
                GlobalContext.get().get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().getAll() 
            }.getOrDefault(emptyList())

            val musicRoot = if (cacheAudio) {
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            } else {
                context.cacheDir
            }
            val outDir = File(musicRoot, "wingmate/audio").apply { if (!exists()) mkdirs() }
            
            // Calculate hash early to check if file already exists
            val cacheKey = SpeechCacheIdentity.speech(combinedText, engine, vForSsml, pitch, rate, dict)
            val fileName = if (cacheAudio) "tts_v2_$cacheKey.mp3" else "tts_session_${System.nanoTime()}.mp3"
            val outFile = File(outDir, fileName)

            if (cacheAudio && outFile.exists() && outFile.length() > 0) {
                // Cache hit! reuse the file without calling Azure
                startPlayback(requestId, outFile, vForSsml, audioRoute = audioRoute)
                return@withContext CloudPlayback(vForSsml, outFile.absolutePath)
            }

            // Cache Miss - Proceed to Synthesis
            
            // The cache key above keeps the Message's own text; only the request drops audio tags.
            val spokenSegments = SpeechTextProcessor.segmentsFor(engine, segments)
            val bytes = if (engine == TtsEngine.ELEVENLABS) {
                ElevenLabsTtsClient.synthesizeSegments(client, spokenSegments, vForSsml, elevenLabsConfig!!, rate)
            } else if (engine == TtsEngine.GOOGLE_CLOUD) {
                GoogleTtsClient.synthesizeSegments(
                    client,
                    spokenSegments,
                    vForSsml,
                    googleConfig!!,
                    pitch,
                    rate,
                    applicationHeaders = googleApiRequestHeaders,
                )
            } else {
                // Use segments-based SSML generation if ANY segment has language override OR pause
                val ssml = if (spokenSegments.any { !it.languageTag.isNullOrBlank() || it.pauseDurationMs > 0 }) {
                    AzureTtsClient.generateSsml(spokenSegments, vForSsml, dict)
                } else {
                    AzureTtsClient.generateSsml(spokenSegments.joinToString("") { it.text }, vForSsml, dict)
                }
                AzureTtsClient.synthesize(client, ssml, azureConfig!!)
            }

            // Persist to an app-private Music directory so history can reference it later.
            // Use a stable hash for the filename to allow aggressive caching and reuse.
            // If file already exists and is valid, skip writing (unless 0 bytes)
            if (!outFile.exists() || outFile.length() == 0L) {
                FileOutputStream(outFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    // Force bytes to stable storage so MediaPlayer's decoder never
                    // reads a partially visible file on first playback (#232).
                    fos.fd.sync()
                }
            }

            startPlayback(requestId, outFile, vForSsml, deleteAfterPlayback = !cacheAudio, audioRoute = audioRoute)
            CloudPlayback(vForSsml, outFile.absolutePath.takeIf { cacheAudio })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // Literal tags keep the call checkable by SpeechLogPrivacyTest.
            if (engine == TtsEngine.ELEVENLABS) {
                OperationalLogger.warn("speech.elevenlabs_synthesis", "failed", exceptionClass = t.loggingClassName())
            } else if (engine == TtsEngine.GOOGLE_CLOUD) {
                OperationalLogger.warn("speech.google_synthesis", "failed", exceptionClass = t.loggingClassName())
            } else {
                OperationalLogger.warn("speech.azure_synthesis", "failed", exceptionClass = t.loggingClassName())
            }
            throw t
        }
    }
}

internal suspend fun AndroidSpeechService.speakRecordedAudioImpl(
    audioFilePath: String,
    textForHistory: String?,
    voice: Voice?,
    audioRoute: AudioRouteDecision = AudioRouteDecision.SystemDefault,
): Boolean {
    val file = File(audioFilePath)
    if (!file.exists() || file.length() <= 0L) return false
    val requestId = beginRequest()

    val played = runCatching {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val player = MediaPlayer()
                var finished = false

                fun finalizePlayback(success: Boolean) {
                    if (finished) return
                    finished = true
                    try {
                        synchronized(playerLock) {
                            if (mediaPlayer === player) {
                                try { player.reset() } catch (_: Throwable) {}
                                try { player.release() } catch (_: Throwable) {}
                                mediaPlayer = null
                            }
                        }
                    } finally {
                        finishPlayback(requestId)
                        if (cont.isActive) cont.resume(success)
                    }
                }

                val attributes = if (audioRoute.requiresRouteOverride) {
                    audioRouter.audioAttributesFor(audioRoute)
                } else {
                    ttsAudioAttributes
                }
                requestAudioFocus(attributes)
                player.setOnCompletionListener {
                    finalizePlayback(true)
                }
                player.setOnErrorListener { _, _, _ ->
                    finalizePlayback(false)
                    true
                }

                cont.invokeOnCancellation {
                    if (finished) return@invokeOnCancellation
                    finished = true
                    try {
                        synchronized(playerLock) {
                            if (mediaPlayer === player) {
                                try { player.stop() } catch (_: Throwable) {}
                                try { player.reset() } catch (_: Throwable) {}
                                try { player.release() } catch (_: Throwable) {}
                                mediaPlayer = null
                            }
                        }
                    } finally {
                        isPlaying = false
                        isPaused = false
                        abandonAudioFocus()
                    }
                }

                try {
                    updateNowPlaying(textForHistory ?: file.nameWithoutExtension, voice)
                    applyMediaPlayerRoute(player, attributes, audioRoute)
                    player.setDataSourceFromFile(file)
                    player.prepare()
                    synchronized(playerLock) {
                        check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                        mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
                        mediaPlayer = player
                        markPlaybackStarted(textForHistory ?: file.nameWithoutExtension, voice, requestId)
                        player.start()
                    }
                } catch (_: Throwable) {
                    finalizePlayback(false)
                }
            }
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        finishPlayback(requestId)
        false
    }

    if (played) {
        textForHistory?.takeIf { it.isNotBlank() }?.let {
            recordHistory(it, voice, file.absolutePath)
        }
    }

    return played
}

internal fun AndroidSpeechService.applyMediaPlayerRoute(
    player: MediaPlayer,
    attributes: AudioAttributes,
    audioRoute: AudioRouteDecision,
) {
    try { player.setAudioAttributes(attributes) } catch (_: Throwable) {}
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && audioRoute.requiresRouteOverride) {
        audioRouter.preferredOutputDeviceFor(audioRoute)?.let { device ->
            runCatching { player.preferredDevice = device }
        }
    }
    if (audioRoute.duckAudio) {
        val volume = audioRouter.volumeFor(audioRoute)
        runCatching { player.setVolume(volume, volume) }
    }
}

/**
 * Opens [file] through a FileDescriptor instead of a raw path. Avoids
 * stale/partial reads of freshly written files on some devices (#232).
 * MediaPlayer dups the fd internally, so the PFD is closed right away.
 */
internal fun MediaPlayer.setDataSourceFromFile(file: File) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        setDataSource(pfd.fileDescriptor)
    }
}

internal fun AndroidSpeechService.startPlayback(
    requestId: Long,
    file: File,
    voice: Voice,
    deleteAfterPlayback: Boolean = false,
    audioRoute: AudioRouteDecision = AudioRouteDecision.SystemDefault,
) {
    check(requestGeneration.get() == requestId) { "Speech request was replaced" }
    val player = MediaPlayer()
    val attributes = if (audioRoute.requiresRouteOverride) {
        audioRouter.audioAttributesFor(audioRoute)
    } else {
        ttsAudioAttributes
    }
    requestAudioFocus(attributes)

    player.setOnCompletionListener { mp ->
        try {
            synchronized(playerLock) {
                if (mediaPlayer === mp) {
                    mp.reset()
                    mp.release()
                    mediaPlayer = null
                }
            }
            finishPlayback(requestId)
            if (deleteAfterPlayback) runCatching { file.delete() }
        } catch (_: Throwable) {}
    }
    player.setOnErrorListener { mp, _, _ ->
        try { synchronized(playerLock) { if (mediaPlayer === mp) { mp.reset(); mp.release(); mediaPlayer = null } } } catch (_: Throwable) {}
        if (deleteAfterPlayback) runCatching { file.delete() }
        finishPlayback(requestId)
        true
    }
    applyMediaPlayerRoute(player, attributes, audioRoute)
    player.setDataSourceFromFile(file)
    player.prepare()
    synchronized(playerLock) {
        check(requestGeneration.get() == requestId) { "Speech request was replaced" }
        mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
        mediaPlayer = player
        markPlaybackStarted(file.nameWithoutExtension, voice, requestId)
        player.start()
    }
}

internal fun AndroidSpeechService.effectiveVoice(
    base: Voice?,
    uiPrimaryLanguage: String?
): Voice {
    val v = base ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
    val effectiveLang = when {
        !v.selectedLanguage.isNullOrBlank() -> v.selectedLanguage
        !uiPrimaryLanguage.isNullOrBlank() -> uiPrimaryLanguage
        !v.primaryLanguage.isNullOrBlank() -> v.primaryLanguage
        else -> "en-US"
    }
    return v.copy(primaryLanguage = effectiveLang)
}

internal suspend fun AndroidSpeechService.maybePlayFromHistoryCache(
    requestId: Long,
    text: String,
    voice: Voice?,
    pitch: Double?,
    rate: Double?,
    uiPrimaryLanguage: String?,
    audioRoute: AudioRouteDecision = AudioRouteDecision.SystemDefault,
): CloudPlayback? {
    return runCatching {
        val koin = GlobalContext.getOrNull()
        val saidRepo = koin?.getOrNull<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
        if (saidRepo == null) return null

        val v = effectiveVoice(voice, uiPrimaryLanguage)
        val list = runCatching { withContext(Dispatchers.IO) { saidRepo.list() } }.getOrNull().orEmpty()
        
        val candidate = list.asSequence()
            .filter { it.saidText == text }
            .filter { !it.audioFilePath.isNullOrBlank() }
            .filter { it.voiceName == v.name }
            // Stricter language matching
            .filter { 
                val itemLang = it.primaryLanguage ?: ""
                val voiceLang = v.selectedLanguage.ifBlank { v.primaryLanguage.orEmpty() }
                itemLang == voiceLang
            }
            // Stricter pitch/rate matching to avoid playing "normal" speed for "slow" request
            .filter { (it.pitch ?: 1.0) == (pitch ?: v.pitch ?: 1.0) }
            .filter { (it.speed ?: 1.0) == (rate ?: v.rate ?: 1.0) }
            .filter { item ->
                val path = item.audioFilePath ?: return@filter false
                val baseTime = item.createdAt ?: item.date ?: File(path).lastModified()
                val age = System.currentTimeMillis() - baseTime
                val fresh = age <= CACHE_TTL_MS
                if (!fresh) runCatching { File(path).delete() }
                fresh
            }
            .sortedByDescending { it.date ?: it.createdAt ?: 0L }
            .firstOrNull()

        val path = candidate?.audioFilePath
        if (path.isNullOrBlank()) return null

        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        if (!file.name.matches(Regex("tts_v2_[0-9a-f]{64}\\.mp3"))) return null

        // Play the cached file with MediaPlayer (mirror Azure playback path)
        val player = MediaPlayer()
        val attributes = if (audioRoute.requiresRouteOverride) {
            audioRouter.audioAttributesFor(audioRoute)
        } else {
            ttsAudioAttributes
        }
        if (!requestAudioFocus(attributes)) {
            // Even if focus fails, try to play; but Android Auto may ignore without focus
        }
        player.setOnCompletionListener { mp ->
            try {
                synchronized(playerLock) {
                    if (mediaPlayer === mp) {
                        mp.reset()
                        mp.release()
                        mediaPlayer = null
                    }
                }
                finishPlayback(requestId)
            } catch (_: Throwable) {}
        }
        player.setOnErrorListener { mp, _, _ ->
            try { synchronized(playerLock) { if (mediaPlayer === mp) { mp.reset(); mp.release(); mediaPlayer = null } } } catch (_: Throwable) {}
            finishPlayback(requestId)
            true
        }
        // Ensure proper routing for car/AA through media usage speech attributes.
        applyMediaPlayerRoute(player, attributes, audioRoute)
        try {
            player.setDataSourceFromFile(file)
            player.prepare()
            synchronized(playerLock) {
                check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
                mediaPlayer = player
                markPlaybackStarted(text, v, requestId)
                player.start()
            }
            CloudPlayback(v, file.absolutePath)
        } catch (_: Throwable) {
            try { player.release() } catch (_: Throwable) {}
            finishPlayback(requestId)
            null
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        null
    }
}

internal fun AndroidSpeechService.recordHistory(text: String, voice: Voice?, audioPath: String? = null) {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val koin = GlobalContext.getOrNull()
            val saidRepo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>() ?: return@runCatching
            val visibleInHistory = koin.getOrNull<io.github.jdreioe.wingmate.domain.SettingsRepository>()
                ?.get()?.historyVisible ?: true
            val now = System.currentTimeMillis()
            
            // Determine effective voice params for history
            val v = voice ?: Voice(name = "System", primaryLanguage = Locale.getDefault().toLanguageTag())
            
            saidRepo.add(
                io.github.jdreioe.wingmate.domain.SaidText(
                    date = now,
                    saidText = text,
                    voiceName = v.name,
                    pitch = v.pitch,
                    speed = v.rate,
                    audioFilePath = audioPath,
                    createdAt = now,
                    position = 0,
                    primaryLanguage = v.selectedLanguage.takeIf(String::isNotBlank) ?: v.primaryLanguage,
                    visibleInHistory = visibleInHistory
                )
            )
        }.onFailure { t ->
            OperationalLogger.warn(
                operation = "speech_history.record",
                outcome = "failed",
                exceptionClass = t.loggingClassName(),
            )
        }
    }
}

internal suspend fun AndroidSpeechService.playTextWithAzure(requestId: Long, text: String, voice: Voice?, pitch: Double?, rate: Double?) {
    // Use the existing Azure TTS logic from the original speakSegments method
    // This is a simplified version that just calls the platform TTS as fallback
    speakWithPlatformTts(requestId, text, voice, pitch, rate)
}
