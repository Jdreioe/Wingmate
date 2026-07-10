package io.github.jdreioe.wingmate.infrastructure

import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

class ChatterboxTtsEngine(
    private val modelDir: File,
    private val tokenizer: ChatterboxTokenizer? = null,
) {

    companion object {
        private const val TAG = "ChatterboxEngine"

        const val COND_PROMPT_LEN = 150
        const val MAX_TEXT_LEN = 256
        const val TEXT_SEQ_LEN = MAX_TEXT_LEN + 2
        const val COND_LEN = 34
        const val PREFILL_LEN = COND_LEN + TEXT_SEQ_LEN + 1
        const val MAX_SPEECH_TOKENS = 1000
        const val MAX_KV_LEN = PREFILL_LEN + MAX_SPEECH_TOKENS
        const val N_LAYERS = 30
        const val N_HEADS = 16
        const val HEAD_DIM = 64
        const val KV_FLAT_SIZE = N_LAYERS * 2 * 1 * N_HEADS * MAX_KV_LEN * HEAD_DIM
        const val HIFI_T_MEL = 300
        const val UPSAMPLE = 480
        const val T_MEL_FIXED = 2200
        const val SAMPLE_RATE = 24000

        const val SOT_SPEECH = 6561
        const val EOT_SPEECH = 6562
        const val SOT_TEXT = 255
        const val EOT_TEXT = 0

        fun sampleToken(
            logits: FloatArray, temperature: Float, minP: Float,
            repPenalty: Float, prevTokens: List<Int>,
        ): Int {
            val probs = FloatArray(logits.size)
            System.arraycopy(logits, 0, probs, 0, logits.size)

            for (tok in prevTokens.takeLast(8)) {
                if (tok < probs.size) {
                    if (probs[tok] > 0) probs[tok] /= repPenalty
                    else probs[tok] *= repPenalty
                }
            }

            if (temperature != 1f) {
                for (i in probs.indices) probs[i] /= temperature
            }

            softmaxInPlace(probs)

            val maxProb = probs.maxOrNull() ?: 0f
            val threshold = maxProb * minP

            val candidates = mutableListOf<Pair<Int, Float>>()
            for (i in probs.indices) {
                if (probs[i] >= threshold) {
                    candidates.add(Pair(i, probs[i]))
                }
            }

            if (candidates.isEmpty()) return probs.indices.maxByOrNull { probs[it] } ?: 0

            val total = candidates.sumOf { it.second.toDouble() }.toFloat()
            var r = Random.nextFloat() * total
            for ((idx, p) in candidates) {
                r -= p
                if (r <= 0f) return idx
            }
            return candidates.last().first
        }

        fun softmaxInPlace(arr: FloatArray) {
            val max = arr.maxOrNull() ?: 0f
            var sum = 0f
            for (i in arr.indices) {
                arr[i] = exp((arr[i] - max).toDouble()).toFloat()
                sum += arr[i]
            }
            if (sum > 0) for (i in arr.indices) arr[i] /= sum
        }

        fun transpose(arr: FloatArray): FloatArray {
            return arr
        }

        fun padToLength(arr: FloatArray, targetLen: Int): FloatArray {
            if (arr.size >= targetLen) return arr.copyOf(targetLen)
            val result = FloatArray(targetLen) { 0f }
            System.arraycopy(arr, 0, result, 0, arr.size)
            return result
        }

        fun sliceFirst(arr: FloatArray): FloatArray {
            val half = arr.size / 2
            return arr.copyOfRange(0, half)
        }

        fun sliceSecond(arr: FloatArray): FloatArray {
            val half = arr.size / 2
            return arr.copyOfRange(half, arr.size)
        }
    }

    private var voiceEncoder: Module? = null
    private var xvectorEncoder: Module? = null
    private var t3CondSpeechEmb: Module? = null
    private var t3CondEnc: Module? = null
    private var t3Prefill: Module? = null
    private var t3Decode: Module? = null
    private var s3genEncoder: Module? = null
    private var cfmStep: Module? = null
    private var hifigan: Module? = null

    private var loaded = false

    fun isLoaded(): Boolean = loaded

    fun load(): Result<Unit> = runCatching {
        Log.i(TAG, "Loading Chatterbox ExecuTorch models from ${modelDir.absolutePath}...")
        voiceEncoder = Module.load(File(modelDir, "voice_encoder.pte").absolutePath)
        xvectorEncoder = Module.load(File(modelDir, "xvector_encoder.pte").absolutePath)
        t3CondSpeechEmb = Module.load(File(modelDir, "t3_cond_speech_emb.pte").absolutePath)
        t3CondEnc = Module.load(File(modelDir, "t3_cond_enc.pte").absolutePath)
        t3Prefill = Module.load(File(modelDir, "t3_prefill.pte").absolutePath)
        t3Decode = Module.load(File(modelDir, "t3_decode.pte").absolutePath)
        s3genEncoder = Module.load(File(modelDir, "s3gen_encoder.pte").absolutePath)
        cfmStep = Module.load(File(modelDir, "cfm_step.pte").absolutePath)
        hifigan = Module.load(File(modelDir, "hifigan.pte").absolutePath)
        loaded = true
        Log.i(TAG, "All Chatterbox models loaded successfully")
    }

    fun unload() {
        voiceEncoder = null
        xvectorEncoder = null
        t3CondSpeechEmb = null
        t3CondEnc = null
        t3Prefill = null
        t3Decode = null
        s3genEncoder = null
        cfmStep = null
        hifigan = null
        loaded = false
        System.gc()
    }

    data class Conditionals(
        val speakerEmb: FloatArray,
        val condPromptSpeechTokens: IntArray,
        val genPromptToken: IntArray,
        val genPromptTokenLen: Int,
        val genEmbedding: FloatArray,
        val genPromptFeat: FloatArray,
    )

    fun synthesize(
        text: String,
        languageId: String = "en",
        temperature: Float = 0.8f,
        minP: Float = 0.05f,
        repPenalty: Float = 2.0f,
        maxGenTokens: Int = 200,
        conditionals: Conditionals? = null,
    ): ShortArray {
        if (!loaded) throw IllegalStateException("Chatterbox engine not loaded")

        val c = conditionals ?: throw IllegalArgumentException("Conditionals required for synthesis")

        val textToks = tokenize(text, languageId)
        if (textToks.size > MAX_TEXT_LEN) throw IllegalArgumentException("Text too long")

        val paddedText = IntArray(MAX_TEXT_LEN) { EOT_TEXT }
        for (i in textToks.indices) paddedText[i] = textToks[i]
        val textTokens = intArrayOf(SOT_TEXT) + paddedText + intArrayOf(EOT_TEXT)

        val condTokens = if (c.condPromptSpeechTokens.size >= COND_PROMPT_LEN) {
            c.condPromptSpeechTokens.copyOf(COND_PROMPT_LEN)
        } else {
            IntArray(COND_PROMPT_LEN) { if (it < c.condPromptSpeechTokens.size) c.condPromptSpeechTokens[it] else 0 }
        }

        val emotion = FloatArray(1) { 0.5f }

        val condSpeechEmb = forwardCondSpeechEmb(condTokens)
        val condEmb = forwardCondEnc(c.speakerEmb, condSpeechEmb, emotion)

        val prefillResult = forwardPrefill(condEmb, textTokens)
        val firstLogits = prefillResult.first
        val kvCache = prefillResult.second

        val speechTokens = mutableListOf<Int>()
        var logits = firstLogits
        var currentKv = kvCache

        for (step in 0 until maxGenTokens) {
            val tok = sampleToken(logits, temperature, minP, repPenalty, speechTokens)
            if (tok == EOT_SPEECH) break
            speechTokens.add(tok)

            val prevToken = intArrayOf(tok)
            val stepIdx = intArrayOf(step + 1)

            val decodeResult = forwardDecode(prevToken, stepIdx, currentKv)
            logits = decodeResult.first
            currentKv = decodeResult.second
        }

        if (speechTokens.isEmpty()) {
            for (i in 100 until 150) speechTokens.add(i)
        }

        val nTokens = speechTokens.size
        val speechToksPadded = IntArray(MAX_SPEECH_TOKENS) { 0 }
        for (i in 0 until min(nTokens, MAX_SPEECH_TOKENS)) speechToksPadded[i] = speechTokens[i]
        val speechTokLen = intArrayOf(nTokens)

        val promptLen = min(c.genPromptToken.size, 75)
        val promptTokens = IntArray(75) { if (it < promptLen) c.genPromptToken[it] else 0 }
        val promptTokLen = intArrayOf(promptLen)

        val s3genResult = forwardS3genEncoder(speechToksPadded, speechTokLen, promptTokens, promptTokLen, c.genEmbedding)
        val h = s3genResult.h
        val hLen = s3genResult.hLen
        val embedding = s3genResult.embedding
        val melLen1 = s3genResult.melLen1

        val hT = transpose(h)
        val hPadded = padToLength(hT, T_MEL_FIXED)

        val condMel = FloatArray(80 * T_MEL_FIXED) { 0f }
        val pmLen = min(melLen1, 80)
        for (j in 0 until pmLen) {
            for (i in 0 until 80) {
                condMel[i * T_MEL_FIXED + j] = c.genPromptFeat[i * melLen1 + j]
            }
        }

        val melOut = forwardCfm(hPadded, hLen, embedding, condMel, melLen1)
        val tMelSpeech = melOut.size / 80

        val audioSamples = forwardHifiganChunked(melOut, tMelSpeech)

        return audioSamples
    }

    private fun tokenize(text: String, languageId: String): List<Int> {
        val tok = tokenizer ?: return listOf()
        return tok.textToTokens(text, languageId)
    }

    private fun forwardCondSpeechEmb(condTokens: IntArray): FloatArray {
        val tensor = Tensor.fromBlob(condTokens, longArrayOf(1, COND_PROMPT_LEN.toLong()))
        val result = t3CondSpeechEmb!!.forward(EValue.from(tensor))
        return result[0].toTensor().getDataAsFloatArray()
    }

    private fun forwardCondEnc(speakerEmb: FloatArray, condSpeechEmb: FloatArray, emotion: FloatArray): FloatArray {
        val t1 = Tensor.fromBlob(speakerEmb, longArrayOf(1, 256))
        val t2 = Tensor.fromBlob(condSpeechEmb, longArrayOf(1, COND_PROMPT_LEN.toLong(), 1024))
        val t3 = Tensor.fromBlob(emotion, longArrayOf(1, 1, 1))
        val result = t3CondEnc!!.forward(EValue.from(t1), EValue.from(t2), EValue.from(t3))
        return result[0].toTensor().getDataAsFloatArray()
    }

    private fun forwardPrefill(condEmb: FloatArray, textTokens: IntArray): Pair<FloatArray, FloatArray> {
        val t1 = Tensor.fromBlob(condEmb, longArrayOf(1, COND_LEN.toLong(), 1024))
        val t2 = Tensor.fromBlob(textTokens, longArrayOf(1, TEXT_SEQ_LEN.toLong()))
        val result = t3Prefill!!.forward(EValue.from(t1), EValue.from(t2))
        val logits = result[0].toTensor().getDataAsFloatArray()
        val kv = result[1].toTensor().getDataAsFloatArray()
        return Pair(logits, kv)
    }

    private fun forwardDecode(prevToken: IntArray, stepIdx: IntArray, kvFlat: FloatArray): Pair<FloatArray, FloatArray> {
        val t1 = Tensor.fromBlob(prevToken, longArrayOf(1, 1))
        val t2 = Tensor.fromBlob(stepIdx, longArrayOf(1))
        val layerSize = N_LAYERS * 1 * N_HEADS * MAX_KV_LEN * HEAD_DIM
        val kvK = kvFlat.copyOfRange(0, layerSize)
        val kvV = kvFlat.copyOfRange(layerSize, layerSize * 2)
        val t3 = Tensor.fromBlob(kvK, longArrayOf(N_LAYERS.toLong(), 1, N_HEADS.toLong(), MAX_KV_LEN.toLong(), HEAD_DIM.toLong()))
        val t4 = Tensor.fromBlob(kvV, longArrayOf(N_LAYERS.toLong(), 1, N_HEADS.toLong(), MAX_KV_LEN.toLong(), HEAD_DIM.toLong()))
        val result = t3Decode!!.forward(EValue.from(t1), EValue.from(t2), EValue.from(t3), EValue.from(t4))
        val logits = result[0].toTensor().getDataAsFloatArray()
        val kvKOut = result[1].toTensor().getDataAsFloatArray()
        val kvVOut = result[2].toTensor().getDataAsFloatArray()
        val kvOut = FloatArray(kvKOut.size + kvVOut.size)
        System.arraycopy(kvKOut, 0, kvOut, 0, kvKOut.size)
        System.arraycopy(kvVOut, 0, kvOut, kvKOut.size, kvVOut.size)
        return Pair(logits, kvOut)
    }

    data class S3genResult(
        val h: FloatArray,
        val hLen: Int,
        val embedding: FloatArray,
        val melLen1: Int,
    )

    private fun forwardS3genEncoder(
        speechToks: IntArray, speechTokLen: IntArray,
        promptTokens: IntArray, promptTokLen: IntArray,
        xVector: FloatArray,
    ): S3genResult {
        val t1 = Tensor.fromBlob(speechToks, longArrayOf(1, MAX_SPEECH_TOKENS.toLong()))
        val t2 = Tensor.fromBlob(speechTokLen, longArrayOf(1))
        val t3 = Tensor.fromBlob(promptTokens, longArrayOf(1, 75))
        val t4 = Tensor.fromBlob(promptTokLen, longArrayOf(1))
        val t5 = Tensor.fromBlob(xVector, longArrayOf(1, 512))
        val result = s3genEncoder!!.forward(EValue.from(t1), EValue.from(t2), EValue.from(t3), EValue.from(t4), EValue.from(t5))
        val h = result[0].toTensor().getDataAsFloatArray()
        val hLen = result[1].toTensor().getDataAsIntArray()
        val embedding = result[2].toTensor().getDataAsFloatArray()
        val melLen1 = result[3].toTensor().getDataAsIntArray()
        return S3genResult(h, hLen[0], embedding, melLen1[0])
    }

    private fun forwardCfm(hPadded: FloatArray, hLen: Int, embedding: FloatArray, condMel: FloatArray, melLen1: Int): FloatArray {
        val cfgRate = 0.7f
        val z = FloatArray(80 * T_MEL_FIXED) { Random.nextFloat() * 2f - 1f }
        val maskCfm = FloatArray(1 * 1 * T_MEL_FIXED) { i -> if (i < hLen) 1f else 0f }

        val tSpan = floatArrayOf(0f, 0.5f, 1f)

        var currentZ = z

        for (stepI in 0 until 2) {
            val tVal = floatArrayOf(tSpan[stepI])
            val rVal = floatArrayOf(tSpan[stepI + 1])

            val xIn = currentZ + currentZ
            val maskIn = maskCfm + maskCfm
            val muIn = FloatArray(hPadded.size * 2)
            System.arraycopy(hPadded, 0, muIn, 0, hPadded.size)
            System.arraycopy(hPadded, 0, muIn, hPadded.size, hPadded.size)

            val tIn = floatArrayOf(tSpan[stepI], tSpan[stepI])
            val rIn = floatArrayOf(tSpan[stepI + 1], tSpan[stepI + 1])

            val spksIn = FloatArray(embedding.size * 2)
            System.arraycopy(embedding, 0, spksIn, 0, embedding.size)

            val condIn = FloatArray(condMel.size * 2)
            System.arraycopy(condMel, 0, condIn, 0, condMel.size)

            val dxdtResult = forwardCfmStep(xIn, maskIn, muIn, tIn, spksIn, condIn, rIn)
            val first = sliceFirst(dxdtResult)
            val second = sliceSecond(dxdtResult)
            val dxdtCFG = FloatArray(first.size) { i -> (1f + cfgRate) * first[i] - cfgRate * second[i] }
            val dt = rVal[0] - tVal[0]
            for (j in currentZ.indices) {
                currentZ[j] = currentZ[j] + dt * dxdtCFG[j]
            }
        }

        val melStart = melLen1.coerceAtMost(currentZ.size / 80 - 1)
        val melEnd = hLen.coerceAtMost(currentZ.size / 80)
        val melLen = melEnd - melStart
        if (melLen <= 0) return currentZ

        val melOut = FloatArray(80 * melLen)
        for (i in 0 until melLen) {
            for (j in 0 until 80) {
                melOut[j * melLen + i] = currentZ[j * T_MEL_FIXED + (melStart + i)]
            }
        }
        return melOut
    }

    private fun forwardCfmStep(
        xIn: FloatArray, maskIn: FloatArray, muIn: FloatArray,
        tIn: FloatArray, spksIn: FloatArray, condIn: FloatArray, rIn: FloatArray,
    ): FloatArray {
        val batch2 = 2L
        val t1 = Tensor.fromBlob(xIn, longArrayOf(batch2, 80, T_MEL_FIXED.toLong()))
        val t2 = Tensor.fromBlob(maskIn, longArrayOf(batch2, 1, T_MEL_FIXED.toLong()))
        val t3 = Tensor.fromBlob(muIn, longArrayOf(batch2, T_MEL_FIXED.toLong(), 512))
        val t4 = Tensor.fromBlob(tIn, longArrayOf(batch2))
        val t5 = Tensor.fromBlob(spksIn, longArrayOf(batch2, 512))
        val t6 = Tensor.fromBlob(condIn, longArrayOf(batch2, 80, T_MEL_FIXED.toLong()))
        val t7 = Tensor.fromBlob(rIn, longArrayOf(batch2))
        val result = cfmStep!!.forward(
            EValue.from(t1), EValue.from(t2), EValue.from(t3),
            EValue.from(t4), EValue.from(t5), EValue.from(t6), EValue.from(t7),
        )
        return result[0].toTensor().getDataAsFloatArray()
    }

    private fun forwardHifiganChunked(melOut: FloatArray, tMelSpeech: Int): ShortArray {
        val nChunks = (tMelSpeech + HIFI_T_MEL - 1) / HIFI_T_MEL
        val allChunks = mutableListOf<ShortArray>()

        for (chunkI in 0 until nChunks) {
            val start = chunkI * HIFI_T_MEL
            val end = minOf(start + HIFI_T_MEL, tMelSpeech)
            val chunkLen = end - start

            val melChunk = FloatArray(80 * HIFI_T_MEL) { 0f }
            for (i in 0 until chunkLen) {
                for (j in 0 until 80) {
                    melChunk[j * HIFI_T_MEL + i] = melOut[j * tMelSpeech + (start + i)]
                }
            }

            val tAudioChunk = HIFI_T_MEL * UPSAMPLE
            val noise = FloatArray(9 * tAudioChunk) { Random.nextFloat() * 2f - 1f }
            val phase = FloatArray(9 * 1) { 0f }

            val t1 = Tensor.fromBlob(melChunk, longArrayOf(1, 80, HIFI_T_MEL.toLong()))
            val t2 = Tensor.fromBlob(noise, longArrayOf(1, 9, tAudioChunk.toLong()))
            val t3 = Tensor.fromBlob(phase, longArrayOf(1, 9, 1))
            val result = hifigan!!.forward(EValue.from(t1), EValue.from(t2), EValue.from(t3))
            val wavChunk = result[0].toTensor().getDataAsFloatArray()

            val trimLen = chunkLen * UPSAMPLE
            val chunkShorts = ShortArray(trimLen)
            for (i in 0 until trimLen) {
                val s = (wavChunk[i] * 32767f).toInt().coerceIn(-32768, 32767)
                chunkShorts[i] = s.toShort()
            }
            allChunks.add(chunkShorts)
        }

        val totalLen = allChunks.sumOf { it.size }
        val result = ShortArray(totalLen)
        var offset = 0
        for (chunk in allChunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }
}