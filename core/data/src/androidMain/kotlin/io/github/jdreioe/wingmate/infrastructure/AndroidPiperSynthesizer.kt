package io.github.jdreioe.wingmate.infrastructure

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.jdreioe.wingmate.domain.SpeechSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Speaks installed Piper voices on the device through sherpa-onnx (ONNX Runtime with the
 * espeak-ng phonemizer). Keeps the most recently used model loaded, because loading takes
 * about a second. Files are checksum-verified before the native engine sees them.
 */
class AndroidPiperSynthesizer(private val installer: PiperVoiceInstaller) {
    private val lock = Mutex()
    private var loadedKey: String? = null
    private var loaded: OfflineTts? = null

    /** Loads [key] so the first phrase starts quickly; throws when the voice cannot be used. */
    suspend fun prepare(key: String) {
        withContext(Dispatchers.Default) { lock.withLock { load(key) } }
    }

    /** Writes [segments] spoken by [key] to [output] as WAV. Pauses become silence. */
    suspend fun synthesize(key: String, segments: List<SpeechSegment>, rate: Double?, output: File) {
        withContext(Dispatchers.Default) {
            lock.withLock {
                val tts = load(key)
                val sampleRate = tts.sampleRate()
                // Piper sounds unnatural outside this range; the device voice clamps similarly.
                val speed = (rate ?: 1.0).toFloat().coerceIn(0.5f, 2.0f)
                val parts = segments.flatMap { segment ->
                    listOfNotNull(
                        segment.text.takeIf(String::isNotBlank)?.let { tts.generate(it, sid = 0, speed = speed).samples },
                        segment.pauseDurationMs.takeIf { it > 0 }?.let { FloatArray((sampleRate * it / 1000).toInt()) },
                    )
                }
                val samples = FloatArray(parts.sumOf { it.size })
                var offset = 0
                parts.forEach { part ->
                    part.copyInto(samples, offset)
                    offset += part.size
                }
                check(samples.isNotEmpty()) { "Piper produced no audio" }
                check(GeneratedAudio(samples, sampleRate).save(output.absolutePath)) { "Piper audio could not be written" }
            }
        }
    }

    /** Frees [key]'s model, for example before its files are deleted. */
    suspend fun unload(key: String) {
        lock.withLock {
            if (loadedKey == key) releaseLoaded()
        }
    }

    private suspend fun load(key: String): OfflineTts {
        if (loadedKey == key) loaded?.let { return it }
        releaseLoaded()
        val files = installer.verifiedFiles(key)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = files.model.toString(),
                    tokens = files.tokens.toString(),
                    dataDir = files.phonemizerData.toString(),
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        return OfflineTts(assetManager = null, config = config).also {
            loaded = it
            loadedKey = key
        }
    }

    private fun releaseLoaded() {
        loaded?.release()
        loaded = null
        loadedKey = null
    }
}
