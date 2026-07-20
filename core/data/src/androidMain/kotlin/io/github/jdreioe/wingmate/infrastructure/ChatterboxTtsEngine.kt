package io.github.jdreioe.wingmate.infrastructure

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxError
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

class ChatterboxTtsEngine(
    private val modelDir: File,
    private val tokenizer: ChatterboxTokenizer,
) {
    data class Conditionals(
        val conditioningEmbedding: FloatArray,
        val promptTokens: LongArray,
        val speakerEmbedding: FloatArray,
        val speakerFeatures: FloatArray,
        val speakerFeaturesShape: LongArray,
    )

    private var environment: OrtEnvironment? = null
    private var embedSession: OrtSession? = null
    private var languageSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var defaultConditionals: Conditionals? = null
    @Volatile private var loaded = false

    fun isLoaded(): Boolean = loaded

    fun load(): Result<Unit> = runCatching {
        if (loaded) return@runCatching
        val env = OrtEnvironment.getEnvironment()
        environment = env

        val defaultVoice = File(modelDir, "default_voice.wav")
        if (!defaultVoice.isFile) throw ChatterboxError.ModelNotFound("default_voice.wav")
        defaultConditionals = extractConditionalsWithTemporarySession(env, defaultVoice)

        embedSession = createSession(env, "onnx/embed_tokens.onnx").also {
            validateContract(
                "embed_tokens", it,
                setOf("input_ids", "position_ids", "exaggeration"),
                setOf("inputs_embeds"),
            )
        }
        languageSession = createSession(env, "onnx/language_model_q4.onnx").also {
            validateContract(
                "language_model_q4", it,
                setOf("inputs_embeds", "attention_mask") + kvInputNames(),
                setOf("logits") + kvOutputNames(),
            )
        }
        val decoderPath = if (hasVerifiedQuantizedDecoder()) {
            QUANTIZED_DECODER_PATH
        } else {
            FLOAT_DECODER_PATH
        }
        decoderSession = createSession(env, decoderPath).also {
            validateContract(
                "conditional_decoder", it,
                setOf("speech_tokens", "speaker_embeddings", "speaker_features"),
                setOf("waveform"),
            )
        }
        loaded = true
        Log.i(TAG, "Loaded pinned Chatterbox Q4 ONNX graph")
    }.onFailure {
        unload()
    }

    fun unload() {
        loaded = false
        defaultConditionals = null
        closeQuietly(decoderSession)
        closeQuietly(languageSession)
        closeQuietly(embedSession)
        decoderSession = null
        languageSession = null
        embedSession = null
        environment = null
    }

    fun createConditionalsFromAudio(audioFile: File): Result<Conditionals> = runCatching {
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        extractConditionalsWithTemporarySession(env, audioFile)
    }

    suspend fun synthesize(
        text: String,
        languageId: String = "en",
        exaggeration: Float = DEFAULT_EXAGGERATION,
        repetitionPenalty: Float = DEFAULT_REPETITION_PENALTY,
        maxNewTokens: Int = MAX_NEW_TOKENS,
        conditionals: Conditionals? = null,
    ): ShortArray {
        check(loaded) { "Chatterbox engine not loaded" }
        coroutineContext.ensureActive()
        val env = environment ?: error("ONNX environment unavailable")
        val activeConditionals = conditionals ?: defaultConditionals
            ?: throw ChatterboxError.InferenceError("No voice conditionals are available")
        val encoding = tokenizer.encode(text, languageId)
        val startedAt = SystemClock.elapsedRealtime()
        val textEmbedding = runEmbedding(env, encoding.inputIds, encoding.positionIds, exaggeration)
        val initialEmbedding = activeConditionals.conditioningEmbedding + textEmbedding
        val initialSequenceLength = initialEmbedding.size / HIDDEN_SIZE
        if (initialSequenceLength <= 0) throw ChatterboxError.InferenceError("Empty input embedding")

        val generated = generateSpeechTokens(
            env = env,
            initialEmbedding = initialEmbedding,
            initialSequenceLength = initialSequenceLength,
            repetitionPenalty = repetitionPenalty,
            exaggeration = exaggeration,
            maxNewTokens = maxNewTokens,
        )
        val generatedAt = SystemClock.elapsedRealtime()
        coroutineContext.ensureActive()
        return decodeWaveform(env, activeConditionals, generated).also {
            val finishedAt = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "Synthesis timing: tokens=${generated.size} generation=${generatedAt - startedAt}ms " +
                    "promptTokens=${activeConditionals.promptTokens.size} " +
                    "decoder=${finishedAt - generatedAt}ms pcmSamples=${it.size} " +
                    "total=${finishedAt - startedAt}ms",
            )
        }
    }

    private fun createSession(env: OrtEnvironment, relativePath: String): OrtSession {
        val file = File(modelDir, relativePath)
        if (!file.isFile) throw ChatterboxError.ModelNotFound(relativePath)
        val options = OrtSession.SessionOptions()
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            // The decoder has tens of thousands of small nodes. EXTENDED avoids the very
            // expensive layout-rewrite pass while retaining constant folding and fusions.
            val isDecoder = relativePath == FLOAT_DECODER_PATH || relativePath == QUANTIZED_DECODER_PATH
            val optimizationLevel = if (isDecoder) {
                OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
            } else {
                OrtSession.SessionOptions.OptLevel.ALL_OPT
            }
            options.setOptimizationLevel(optimizationLevel)
            options.setExecutionMode(
                if (isDecoder) OrtSession.SessionOptions.ExecutionMode.PARALLEL
                else OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL,
            )
            options.setMemoryPatternOptimization(true)
            options.setCPUArenaAllocator(true)
            val maxThreads = if (isDecoder) 6 else 4
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, maxThreads))
            options.setInterOpNumThreads(if (isDecoder) 2 else 1)
            env.createSession(file.absolutePath, options).also {
                Log.i(
                    TAG,
                    "Created $relativePath session provider=CPU optimization=$optimizationLevel " +
                        "in ${SystemClock.elapsedRealtime() - startedAt}ms",
                )
            }
        } finally {
            options.close()
        }
    }

    private fun hasVerifiedQuantizedDecoder(): Boolean {
        val graph = File(modelDir, QUANTIZED_DECODER_PATH)
        val data = File(modelDir, QUANTIZED_DECODER_DATA_PATH)
        if (graph.length() != QUANTIZED_DECODER_SIZE || data.length() != QUANTIZED_DECODER_DATA_SIZE) {
            return false
        }
        return sha256(graph) == QUANTIZED_DECODER_SHA256 &&
            sha256(data) == QUANTIZED_DECODER_DATA_SHA256
    }

    private fun validateContract(
        model: String,
        session: OrtSession,
        requiredInputs: Set<String>,
        requiredOutputs: Set<String>,
    ) {
        val missingInputs = requiredInputs - session.inputNames
        val missingOutputs = requiredOutputs - session.outputNames
        if (missingInputs.isNotEmpty() || missingOutputs.isNotEmpty()) {
            throw ChatterboxError.IncompatibleGraph(
                model,
                "missing inputs=$missingInputs, missing outputs=$missingOutputs",
            )
        }
    }

    private fun extractConditionalsWithTemporarySession(
        env: OrtEnvironment,
        audioFile: File,
    ): Conditionals {
        val samples = loadWavAsMono24k(audioFile)
            .let { it.copyOf(minOf(it.size, MAX_REFERENCE_SAMPLES)) }
        if (samples.isEmpty()) throw ChatterboxError.CloneFailed("Reference audio is empty")
        val session = createSession(env, "onnx/speech_encoder.onnx")
        try {
            validateContract(
                "speech_encoder", session,
                setOf("audio_values"),
                setOf("audio_features", "audio_tokens", "speaker_embeddings", "speaker_features"),
            )
            val audioTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(samples),
                longArrayOf(1, samples.size.toLong()),
            )
            try {
                session.run(mapOf("audio_values" to audioTensor)).use { result ->
                    val conditioning = result.floatArray("audio_features")
                    val prompt = result.longArray("audio_tokens")
                    val speaker = result.floatArray("speaker_embeddings")
                    val featuresTensor = result.tensor("speaker_features")
                    val features = featuresTensor.floatArray()
                    val shape = featuresTensor.info.shape
                    if (conditioning.isEmpty() || prompt.isEmpty() || speaker.size != SPEAKER_DIM || features.isEmpty()) {
                        throw ChatterboxError.InferenceError("Speech encoder returned invalid conditionals")
                    }
                    return Conditionals(conditioning, prompt, speaker, features, shape)
                }
            } finally {
                audioTensor.close()
            }
        } finally {
            session.close()
        }
    }

    private fun runEmbedding(
        env: OrtEnvironment,
        inputIds: LongArray,
        positionIds: LongArray,
        exaggeration: Float,
    ): FloatArray {
        val session = embedSession ?: error("Embedding session unavailable")
        val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong()))
        val positions = OnnxTensor.createTensor(env, LongBuffer.wrap(positionIds), longArrayOf(1, positionIds.size.toLong()))
        val emotion = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(exaggeration)), longArrayOf(1))
        return try {
            session.run(mapOf("input_ids" to ids, "position_ids" to positions, "exaggeration" to emotion)).use {
                it.floatArray("inputs_embeds")
            }
        } finally {
            ids.close()
            positions.close()
            emotion.close()
        }
    }

    private suspend fun generateSpeechTokens(
        env: OrtEnvironment,
        initialEmbedding: FloatArray,
        initialSequenceLength: Int,
        repetitionPenalty: Float,
        exaggeration: Float,
        maxNewTokens: Int,
    ): LongArray {
        val session = languageSession ?: error("Language model session unavailable")
        val initialInputs = mutableMapOf<String, OnnxTensor>()
        initialInputs["inputs_embeds"] = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(initialEmbedding),
            longArrayOf(1, initialSequenceLength.toLong(), HIDDEN_SIZE.toLong()),
        )
        initialInputs["attention_mask"] = longTensor(
            env,
            LongArray(initialSequenceLength) { 1L },
            longArrayOf(1, initialSequenceLength.toLong()),
        )
        for (name in kvInputNames()) {
            initialInputs[name] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(FloatArray(0)),
                longArrayOf(1, N_HEADS.toLong(), 0, HEAD_DIM.toLong()),
            )
        }

        var result: OrtSession.Result? = try {
            session.run(initialInputs)
        } finally {
            initialInputs.values.forEach(::closeQuietly)
        }
        val generated = mutableListOf(SOT_SPEECH)
        var totalSequenceLength = initialSequenceLength
        var stopped = false
        try {
            repeat(maxNewTokens.coerceAtMost(MAX_NEW_TOKENS)) { step ->
                coroutineContext.ensureActive()
                if (stopped) return@repeat
                val current = result ?: error("Language model result unavailable")
                val nextToken = sampleSpeechToken(
                    current.floatArray("logits"),
                    generated,
                    repetitionPenalty,
                )
                generated += nextToken.toLong()
                if (nextToken.toLong() == EOT_SPEECH) {
                    stopped = true
                    return@repeat
                }
                if (generated.size >= MAX_IDENTICAL_TOKENS &&
                    generated.takeLast(MAX_IDENTICAL_TOKENS).distinct().size == 1
                ) {
                    throw ChatterboxError.InferenceError("Speech token generation collapsed into repetition")
                }

                val nextIds = longArrayOf(nextToken.toLong())
                val nextPositions = longArrayOf((step + 1).toLong())
                val nextEmbedding = runEmbedding(env, nextIds, nextPositions, exaggeration)
                totalSequenceLength++
                val nextInputs = mutableMapOf<String, OnnxTensor>()
                nextInputs["inputs_embeds"] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(nextEmbedding),
                    longArrayOf(1, 1, HIDDEN_SIZE.toLong()),
                )
                nextInputs["attention_mask"] = longTensor(
                    env,
                    LongArray(totalSequenceLength) { 1L },
                    longArrayOf(1, totalSequenceLength.toLong()),
                )
                for (layer in 0 until N_LAYERS) {
                    for (kind in CACHE_KINDS) {
                        nextInputs["past_key_values.$layer.$kind"] = current.tensor("present.$layer.$kind")
                    }
                }
                val newResult = try {
                    session.run(nextInputs)
                } finally {
                    nextInputs["inputs_embeds"]?.close()
                    nextInputs["attention_mask"]?.close()
                }
                result = newResult
                current.close()
            }
            if (!stopped) {
                throw ChatterboxError.InferenceError("Speech token generation did not stop")
            }
            return generated.drop(1).dropLast(1).toLongArray().also {
                if (it.isEmpty()) throw ChatterboxError.InferenceError("No speech tokens were generated")
            }
        } finally {
            result?.close()
        }
    }

    private fun decodeWaveform(
        env: OrtEnvironment,
        conditionals: Conditionals,
        generatedTokens: LongArray,
    ): ShortArray {
        val session = decoderSession ?: error("Conditional decoder session unavailable")
        val speechTokens = conditionals.promptTokens + generatedTokens
        val tokensTensor = longTensor(env, speechTokens, longArrayOf(1, speechTokens.size.toLong()))
        val speakerTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(conditionals.speakerEmbedding),
            longArrayOf(1, SPEAKER_DIM.toLong()),
        )
        val featuresTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(conditionals.speakerFeatures),
            conditionals.speakerFeaturesShape,
        )
        val waveform = try {
            session.run(
                mapOf(
                    "speech_tokens" to tokensTensor,
                    "speaker_embeddings" to speakerTensor,
                    "speaker_features" to featuresTensor,
                )
            ).use { it.floatArray("waveform") }
        } finally {
            tokensTensor.close()
            speakerTensor.close()
            featuresTensor.close()
        }
        return waveformToPcm16(waveform)
    }

    private fun loadWavAsMono24k(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size < 44 || bytes.copyOfRange(0, 4).decodeToString() != "RIFF") {
            throw ChatterboxError.CloneFailed("Reference audio is not a WAV file")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = bytes.copyOfRange(offset, offset + 4).decodeToString()
            val size = buffer.getInt(offset + 4)
            val content = offset + 8
            if (size < 0 || content + size > bytes.size) break
            when (name) {
                "fmt " -> {
                    format = buffer.getShort(content).toInt() and 0xffff
                    channels = buffer.getShort(content + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(content + 4)
                    bits = buffer.getShort(content + 14).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = content
                    dataSize = size
                    break
                }
            }
            offset = content + size + (size and 1)
        }
        if (dataOffset < 0 || channels <= 0 || sampleRate <= 0) {
            throw ChatterboxError.CloneFailed("Reference WAV header is incomplete")
        }
        val bytesPerSample = bits / 8
        val frames = dataSize / (bytesPerSample * channels)
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                val index = dataOffset + (frame * channels + channel) * bytesPerSample
                sum += when {
                    format == 3 && bits == 32 -> buffer.getFloat(index)
                    format == 1 && bits == 16 -> buffer.getShort(index) / 32768f
                    else -> throw ChatterboxError.CloneFailed("Only PCM16 and float32 WAV are supported")
                }
            }
            mono[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        if (sampleRate == SAMPLE_RATE) return mono
        val outputSize = (mono.size.toLong() * SAMPLE_RATE / sampleRate).toInt()
        return FloatArray(outputSize) { index ->
            val source = index.toDouble() * sampleRate / SAMPLE_RATE
            val left = source.toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val fraction = (source - left).toFloat()
            mono[left] * (1f - fraction) + mono[right] * fraction
        }
    }

    companion object {
        private const val TAG = "ChatterboxEngine"
        const val SAMPLE_RATE = 24_000
        const val MAX_TEXT_LEN = ChatterboxTokenizer.MAX_TEXT_LEN
        const val MAX_NEW_TOKENS = 256
        const val N_LAYERS = 30
        const val N_HEADS = 16
        const val HEAD_DIM = 64
        const val HIDDEN_SIZE = 1024
        const val VOCAB_SIZE = 8194
        const val SPEAKER_DIM = 192
        const val SOT_SPEECH = 6561L
        const val EOT_SPEECH = 6562L
        const val DEFAULT_EXAGGERATION = 0.5f
        const val DEFAULT_REPETITION_PENALTY = 1.2f
        private const val MAX_REFERENCE_SECONDS = 2
        private const val MAX_REFERENCE_SAMPLES = SAMPLE_RATE * MAX_REFERENCE_SECONDS
        private const val FLOAT_DECODER_PATH = "onnx/conditional_decoder.onnx"
        private const val QUANTIZED_DECODER_PATH = "onnx/conditional_decoder_q4.onnx"
        private const val QUANTIZED_DECODER_DATA_PATH = "onnx/conditional_decoder_q4.onnx.data"
        private const val QUANTIZED_DECODER_SIZE = 6_855_016L
        private const val QUANTIZED_DECODER_DATA_SIZE = 210_111_616L
        private const val QUANTIZED_DECODER_SHA256 =
            "8bec3ad12b5315b13838b19db4d415aad3d717069860d7f2e2096ec6d8828122"
        private const val QUANTIZED_DECODER_DATA_SHA256 =
            "7436e4ea855941214e538e80c4b020e2bf0fab0abb02793dfef42b1d49164c1a"

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { source ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        private const val MAX_IDENTICAL_TOKENS = 16
        const val DEFAULT_TEMPERATURE = 0.8f
        const val DEFAULT_TOP_P = 0.95f
        const val DEFAULT_MIN_P = 0.05f
        private val CACHE_KINDS = listOf("key", "value")

        fun kvInputNames(): Set<String> = (0 until N_LAYERS).flatMap { layer ->
            CACHE_KINDS.map { kind -> "past_key_values.$layer.$kind" }
        }.toSet()

        fun kvOutputNames(): Set<String> = (0 until N_LAYERS).flatMap { layer ->
            CACHE_KINDS.map { kind -> "present.$layer.$kind" }
        }.toSet()

        fun selectArgmaxToken(logits: FloatArray, previousTokens: List<Long>, penalty: Float): Int {
            require(logits.size >= VOCAB_SIZE) { "Invalid logits length ${logits.size}" }
            val start = logits.size - VOCAB_SIZE
            val adjusted = logits.copyOfRange(start, logits.size)
            previousTokens.distinct().forEach { token ->
                val index = token.toInt()
                if (index in adjusted.indices) {
                    adjusted[index] = if (adjusted[index] < 0f) adjusted[index] * penalty else adjusted[index] / penalty
                }
            }
            return adjusted.indices.maxBy { adjusted[it] }
        }

        /** Mirrors the sampling defaults used by the upstream multilingual Chatterbox model. */
        fun sampleSpeechToken(
            logits: FloatArray,
            previousTokens: List<Long>,
            repetitionPenalty: Float,
            temperature: Float = DEFAULT_TEMPERATURE,
            topP: Float = DEFAULT_TOP_P,
            minP: Float = DEFAULT_MIN_P,
            randomValue: Float = Random.nextFloat(),
        ): Int {
            require(logits.size >= VOCAB_SIZE) { "Invalid logits length ${logits.size}" }
            require(repetitionPenalty > 0f) { "Repetition penalty must be positive" }
            require(temperature > 0f) { "Temperature must be positive" }
            require(topP in 0f..1f && topP > 0f) { "topP must be in (0, 1]" }
            require(minP in 0f..1f) { "minP must be in [0, 1]" }
            require(randomValue in 0f..1f) { "randomValue must be in [0, 1]" }

            val start = logits.size - VOCAB_SIZE
            val adjusted = logits.copyOfRange(start, logits.size)
            previousTokens.distinct().forEach { token ->
                val index = token.toInt()
                if (index in adjusted.indices) {
                    adjusted[index] = if (adjusted[index] < 0f) {
                        adjusted[index] * repetitionPenalty
                    } else {
                        adjusted[index] / repetitionPenalty
                    }
                }
            }

            val maxLogit = adjusted.maxOrNull()
                ?.takeIf(Float::isFinite)
                ?: throw ChatterboxError.InferenceError("Language model returned invalid logits")
            val probabilities = DoubleArray(adjusted.size) { index ->
                exp(((adjusted[index] - maxLogit) / temperature).toDouble())
            }
            val probabilitySum = probabilities.sum()
            if (!probabilitySum.isFinite() || probabilitySum <= 0.0) {
                throw ChatterboxError.InferenceError("Language model returned invalid probabilities")
            }
            for (index in probabilities.indices) probabilities[index] /= probabilitySum

            val minProbability = probabilities.maxOrNull()!! * minP
            val candidates = probabilities.indices
                .asSequence()
                .filter { probabilities[it] >= minProbability }
                .sortedByDescending { probabilities[it] }
                .toList()
            if (candidates.isEmpty()) return adjusted.indices.maxBy { adjusted[it] }

            val nucleus = ArrayList<Int>(candidates.size)
            var cumulative = 0.0
            for (candidate in candidates) {
                nucleus += candidate
                cumulative += probabilities[candidate]
                if (cumulative >= topP) break
            }

            val nucleusMass = nucleus.sumOf { probabilities[it] }
            var sample = randomValue.coerceAtMost(0.99999994f) * nucleusMass
            for (candidate in nucleus) {
                sample -= probabilities[candidate]
                if (sample <= 0.0) return candidate
            }
            return nucleus.last()
        }

        fun waveformToPcm16(waveform: FloatArray): ShortArray {
            if (waveform.isEmpty()) throw ChatterboxError.InvalidWaveform("empty output")
            if (waveform.any { !it.isFinite() }) throw ChatterboxError.InvalidWaveform("non-finite samples")
            val rms = sqrt(waveform.sumOf { (it * it).toDouble() } / waveform.size).toFloat()
            if (rms < 1e-5f) throw ChatterboxError.InvalidWaveform("silent output")
            return ShortArray(waveform.size) { index ->
                (waveform[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }

        private fun longTensor(env: OrtEnvironment, values: LongArray, shape: LongArray): OnnxTensor =
            OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)

        private fun OrtSession.Result.tensor(name: String): OnnxTensor =
            get(name).orElseThrow { ChatterboxError.InferenceError("Missing ONNX output '$name'") } as? OnnxTensor
                ?: throw ChatterboxError.InferenceError("ONNX output '$name' is not a tensor")

        private fun OrtSession.Result.floatArray(name: String): FloatArray = tensor(name).floatArray()

        private fun OrtSession.Result.longArray(name: String): LongArray {
            val buffer = tensor(name).longBuffer
            return LongArray(buffer.remaining()).also(buffer::get)
        }

        private fun OnnxTensor.floatArray(): FloatArray {
            val buffer = floatBuffer
            return FloatArray(buffer.remaining()).also(buffer::get)
        }

        private fun closeQuietly(closeable: AutoCloseable?) {
            runCatching { closeable?.close() }
        }
    }
}
