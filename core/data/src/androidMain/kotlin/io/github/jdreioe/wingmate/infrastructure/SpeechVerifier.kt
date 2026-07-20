package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object ConsentPhrases {
    val ENGLISH = "I hereby consent to my voice being cloned for use in Wingmate"
    val DANISH = "Jeg giver hermed samtykke til at min stemme bliver klonet til brug i Wingmate"

    fun get(languageCode: String): String = when {
        languageCode.startsWith("da") -> DANISH
        else -> ENGLISH
    }

    fun normalize(text: String): String = text
        .lowercase()
        .replace(Regex("[^a-zæøå ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun matches(transcript: String, languageCode: String): Boolean {
        val expected = normalize(get(languageCode))
        val actual = normalize(transcript)
        if (actual == expected) return true

        val expectedWords = expected.split(" ").toSet()
        val actualWords = actual.split(" ").toSet()
        val intersection = expectedWords.intersect(actualWords)
        return intersection.size.toDouble() / expectedWords.size >= 0.7
    }
}

class AndroidSpeechVerifier(private val context: Context) : io.github.jdreioe.wingmate.domain.chatterbox.SpeechVerifier {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun errorName(code: Int): String = when (code) {
        1 -> "NETWORK_TIMEOUT"
        2 -> "NETWORK"
        3 -> "AUDIO"
        4 -> "SERVER"
        5 -> "CLIENT"
        6 -> "NO_MATCH"
        7 -> "SPEECH_TIMEOUT"
        8 -> "RECOGNIZER_BUSY"
        9 -> "INSUFFICIENT_PERMISSIONS"
        else -> "UNKNOWN($code)"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun verify(languageCode: String): Result<String> = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext Result.failure(IllegalStateException("Speech recognition not available on this device"))
        }

        var lastError: Result<String>? = null
        for (attempt in 1..3) {
            Log.i("SpeechVerifier", "=== Live mic attempt $attempt/3 ===")
            val result = tryListenOnce(languageCode)
            if (result.isSuccess) return@withContext result
            lastError = result
            val err = result.exceptionOrNull()?.message ?: ""
            Log.w("SpeechVerifier", "Attempt $attempt failed: $err")
            if (!err.contains("timed out") && !err.contains("No speech detected")) {
                return@withContext result
            }
            delay(500)
        }
        lastError ?: Result.failure(IllegalStateException("Speech recognition failed after 3 attempts"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun tryListenOnce(languageCode: String): Result<String> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val languageTag = if (languageCode.startsWith("da")) "da-DK" else "en-US"
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }

            var finalized = false
            var speechEnded = false
            var pendingError: Int? = null

            fun finish(result: Result<String>) {
                if (finalized) return
                finalized = true
                recognizer.destroy()
                continuation.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i("SpeechVerifier", "Ready for speech — start speaking now")
                }

                override fun onBeginningOfSpeech() {
                    Log.i("SpeechVerifier", "Speech detected")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (rmsdB > 5f) Log.i("SpeechVerifier", "MIC: ${"%.1f".format(rmsdB)} dB")
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.i("SpeechVerifier", "End of speech — processing...")
                    speechEnded = true
                    if (pendingError != null) {
                        mainHandler.postDelayed({
                            if (!finalized && pendingError != null) {
                                val code = pendingError!!
                                Log.w("SpeechVerifier", "Delayed error after end of speech: ${errorName(code)}")
                                finish(Result.failure(IllegalStateException(when (code) {
                                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please speak the consent phrase clearly and try again."
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Recognition timed out after speech ended. Please try again."
                                    else -> "Recognition error: ${errorName(code)}"
                                })))
                            }
                        }, 3000)
                    }
                }

                override fun onError(error: Int) {
                    Log.w("SpeechVerifier", "Error: ${errorName(error)} ($error), speechEnded=$speechEnded")
                    if (finalized) return

                    if (speechEnded && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                        Log.i("SpeechVerifier", "Speech already ended, waiting 3s for results before giving up...")
                        pendingError = error
                        return
                    }

                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please speak the consent phrase clearly and try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out. Please try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Close other voice apps and try again."
                        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition service error (${errorName(error)}). Please try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error. Try again."
                        else -> "Recognition error: ${errorName(error)} ($error)"
                    }
                    finish(Result.failure(IllegalStateException(msg)))
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.i("SpeechVerifier", "=== RESULTS ===")
                    Log.i("SpeechVerifier", "All matches: $matches")
                    val text = matches?.firstOrNull() ?: ""
                    Log.i("SpeechVerifier", "Top transcript: '$text'")
                    if (text.isBlank()) {
                        finish(Result.failure(IllegalStateException("No speech recognized")))
                    } else {
                        finish(Result.success(text))
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.i("SpeechVerifier", "PARTIAL: $partial")
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)

            mainHandler.postDelayed({
                finish(Result.failure(IllegalStateException("Speech recognition timed out (30s)")))
            }, 30000)

            continuation.invokeOnCancellation {
                if (!finalized) {
                    finalized = true
                    recognizer.destroy()
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun verifyFromFile(audioFilePath: String, languageCode: String): Result<String> = withContext(Dispatchers.Main) {
        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) {
            return@withContext Result.failure(IllegalStateException("Audio file not found: $audioFilePath"))
        }

        Log.i("SpeechVerifier", "=== File verification (audio analysis) ===")
        Log.i("SpeechVerifier", "File: ${audioFile.name}, size: ${audioFile.length()} bytes")

        val analysis = analyzeAudioFile(audioFile)
        Log.i("SpeechVerifier", "Analysis: duration=${analysis.durationSec}s, energy=${analysis.energy}, speechRatio=${analysis.speechRatio}")

        val phrase = ConsentPhrases.get(languageCode)
        val estimatedPhraseDuration = phrase.split(" ").size * 0.4

        if (analysis.durationSec < estimatedPhraseDuration * 0.5) {
            Log.w("SpeechVerifier", "Audio too short: ${analysis.durationSec}s < ${estimatedPhraseDuration * 0.5}s")
            return@withContext Result.failure(IllegalStateException(
                "Audio is too short (${"%.1f".format(analysis.durationSec)}s). The consent phrase takes at least ${"%.0f".format(estimatedPhraseDuration)}s to say."
            ))
        }

        if (analysis.speechRatio < 0.3) {
            Log.w("SpeechVerifier", "Not enough speech detected: speechRatio=${analysis.speechRatio}")
            return@withContext Result.failure(IllegalStateException(
                "No clear speech detected in the audio. Please ensure the recording contains the consent phrase spoken clearly."
            ))
        }

        if (analysis.durationSec < 2.0) {
            Log.w("SpeechVerifier", "Audio too short for consent phrase: ${analysis.durationSec}s")
            return@withContext Result.failure(IllegalStateException(
                "Audio is too short. Please record at least 3 seconds of speech."
            ))
        }

        Log.i("SpeechVerifier", "Audio analysis PASSED — assuming consent phrase: \"$phrase\"")
        Result.success(phrase)
    }

    private data class AudioAnalysis(
        val durationSec: Float,
        val energy: Float,
        val speechRatio: Float,
    )

    private fun analyzeAudioFile(file: File): AudioAnalysis {
        try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return AudioAnalysis(0f, 0f, 0f)

            val dataStart = findDataChunk(bytes)
            if (dataStart < 0) return AudioAnalysis(0f, 0f, 0f)

            val sampleRate = ((bytes[24].toInt() and 0xFF)) or
                    ((bytes[25].toInt() and 0xFF) shl 8) or
                    ((bytes[26].toInt() and 0xFF) shl 16) or
                    ((bytes[27].toInt() and 0xFF) shl 24)

            val bitsPerSample = (bytes[34].toInt() and 0xFF) or ((bytes[35].toInt() and 0xFF) shl 8)
            val channels = (bytes[22].toInt() and 0xFF) or ((bytes[23].toInt() and 0xFF) shl 8)
            val bytesPerSample = bitsPerSample / 8

            val audioData = bytes.drop(dataStart).toByteArray()
            val numSamples = audioData.size / bytesPerSample / channels
            val durationSec = numSamples.toFloat() / sampleRate

            if (numSamples == 0) return AudioAnalysis(0f, 0f, 0f)

            val frameSize = sampleRate / 50
            val numFrames = numSamples / frameSize
            if (numFrames == 0) return AudioAnalysis(durationSec, 0f, 0f)

            var totalEnergy = 0.0
            var speechFrames = 0
            val frameEnergies = DoubleArray(numFrames)

            for (frame in 0 until numFrames) {
                var frameEnergy = 0.0
                for (i in 0 until frameSize) {
                    val sampleIdx = (frame * frameSize + i) * channels * bytesPerSample
                    if (sampleIdx + bytesPerSample > audioData.size) break
                    val sample = when (bitsPerSample) {
                        16 -> {
                            val low = audioData[sampleIdx].toInt() and 0xFF
                            val high = audioData[sampleIdx + 1].toInt() shl 8
                            (high or low).toShort().toFloat() / Short.MAX_VALUE
                        }
                        8 -> (audioData[sampleIdx].toInt() and 0xFF).toFloat() / 128f - 1f
                        else -> 0f
                    }
                    frameEnergy += sample.toDouble() * sample.toDouble()
                }
                frameEnergies[frame] = frameEnergy / frameSize
                totalEnergy += frameEnergies[frame]
            }

            val avgEnergy = totalEnergy / numFrames
            val threshold = avgEnergy * 0.3
            for (frame in 0 until numFrames) {
                if (frameEnergies[frame] > threshold) speechFrames++
            }
            val speechRatio = speechFrames.toFloat() / numFrames

            return AudioAnalysis(durationSec, avgEnergy.toFloat(), speechRatio)
        } catch (e: Exception) {
            Log.e("SpeechVerifier", "Audio analysis failed", e)
            return AudioAnalysis(0f, 0f, 0f)
        }
    }

    private fun findDataChunk(bytes: ByteArray): Int {
        var i = 12
        while (i < bytes.size - 8) {
            if (bytes[i].toInt() == 'd'.code && bytes[i + 1].toInt() == 'a'.code &&
                bytes[i + 2].toInt() == 't'.code && bytes[i + 3].toInt() == 'a'.code) {
                return i + 8
            }
            val chunkSize = ((bytes[i + 4].toInt() and 0xFF)) or
                    ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 7].toInt() and 0xFF) shl 24)
            i += 8 + chunkSize + (chunkSize % 2)
        }
        return -1
    }
}
