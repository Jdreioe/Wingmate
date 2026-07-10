package io.github.jdreioe.wingmate.infrastructure

import org.junit.Test
import org.junit.Assert.*

class ChatterboxTtsEngineHelperTest {

    @Test
    fun softmaxInPlace_producesValidDistribution() {
        val arr = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        ChatterboxTtsEngine.softmaxInPlace(arr)
        assertTrue(arr.all { it > 0f })
        assertEquals(1.0f, arr.sum(), 1e-5f)
    }

    @Test
    fun softmaxInPlace_uniformInput() {
        val arr = floatArrayOf(2f, 2f, 2f)
        ChatterboxTtsEngine.softmaxInPlace(arr)
        assertEquals(1.0f, arr.sum(), 1e-5f)
        assertTrue(arr.all { kotlin.math.abs(it - 1f / 3f) < 1e-5f })
    }

    @Test
    fun softmaxInPlace_negativeInput() {
        val arr = floatArrayOf(-1f, -2f, -3f)
        ChatterboxTtsEngine.softmaxInPlace(arr)
        assertEquals(1.0f, arr.sum(), 1e-5f)
        assertTrue(arr.all { it > 0f })
        assertTrue(arr[0] > arr[1])
        assertTrue(arr[1] > arr[2])
    }

    @Test
    fun softmaxInPlace_singleElement() {
        val arr = floatArrayOf(42f)
        ChatterboxTtsEngine.softmaxInPlace(arr)
        assertEquals(1.0f, arr[0], 1e-5f)
    }

    @Test
    fun padToLength_padsWithZeros() {
        val arr = floatArrayOf(1f, 2f, 3f)
        val padded = ChatterboxTtsEngine.padToLength(arr, 5)
        assertEquals(5, padded.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 0f, 0f), padded, 1e-5f)
    }

    @Test
    fun padToLength_truncatesWhenLarger() {
        val arr = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val truncated = ChatterboxTtsEngine.padToLength(arr, 3)
        assertEquals(3, truncated.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), truncated, 1e-5f)
    }

    @Test
    fun padToLength_exactSize() {
        val arr = floatArrayOf(1f, 2f, 3f)
        val result = ChatterboxTtsEngine.padToLength(arr, 3)
        assertArrayEquals(arr, result, 1e-5f)
    }

    @Test
    fun padToLength_emptyToNonZero() {
        val arr = floatArrayOf()
        val padded = ChatterboxTtsEngine.padToLength(arr, 4)
        assertEquals(4, padded.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), padded, 1e-5f)
    }

    @Test
    fun sliceFirst_returnsFirstHalf() {
        val arr = floatArrayOf(1f, 2f, 3f, 4f)
        val first = ChatterboxTtsEngine.sliceFirst(arr)
        assertArrayEquals(floatArrayOf(1f, 2f), first, 1e-5f)
    }

    @Test
    fun sliceSecond_returnsSecondHalf() {
        val arr = floatArrayOf(1f, 2f, 3f, 4f)
        val second = ChatterboxTtsEngine.sliceSecond(arr)
        assertArrayEquals(floatArrayOf(3f, 4f), second, 1e-5f)
    }

    @Test
    fun sliceFirst_emptyArray() {
        val arr = floatArrayOf()
        val first = ChatterboxTtsEngine.sliceFirst(arr)
        assertArrayEquals(floatArrayOf(), first, 1e-5f)
    }

    @Test
    fun sliceSecond_emptyArray() {
        val arr = floatArrayOf()
        val second = ChatterboxTtsEngine.sliceSecond(arr)
        assertArrayEquals(floatArrayOf(), second, 1e-5f)
    }

    @Test
    fun sliceFirst_oddLength() {
        val arr = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val first = ChatterboxTtsEngine.sliceFirst(arr)
        assertArrayEquals(floatArrayOf(1f, 2f), first, 1e-5f)
    }

    @Test
    fun sliceSecond_oddLength() {
        val arr = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val second = ChatterboxTtsEngine.sliceSecond(arr)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), second, 1e-5f)
    }

    @Test
    fun sampleToken_returnsValidToken() {
        val logits = FloatArray(100) { kotlin.math.exp((-it.toFloat()).toDouble()).toFloat() }
        val tok = ChatterboxTtsEngine.sampleToken(logits, 1.0f, 0.0f, 2.0f, emptyList())
        assertTrue(tok in 0 until 100)
    }

    @Test
    fun sampleToken_greedyAtZeroTemperature() {
        val logits = FloatArray(100) { -it.toFloat() }
        logits[5] = 100f
        val tok = ChatterboxTtsEngine.sampleToken(logits, 1e-6f, 0.0f, 2.0f, emptyList())
        assertEquals(5, tok)
    }

    @Test
    fun sampleToken_repPenaltySuppressesRecentTokens() {
        val logits = FloatArray(10) { 10f - it.toFloat() }
        logits[0] = 100f
        val tokNoRep = ChatterboxTtsEngine.sampleToken(logits, 1e-6f, 0.0f, 1.0f, emptyList())
        assertEquals(0, tokNoRep)
    }

    @Test
    fun softmaxInPlace_zeroSum() {
        val arr = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        ChatterboxTtsEngine.softmaxInPlace(arr)
        assertTrue(arr.all { it.isNaN() || it == 0f })
    }

    @Test
    fun transpose_identity() {
        val arr = floatArrayOf(1f, 2f, 3f)
        val result = ChatterboxTtsEngine.transpose(arr)
        assertArrayEquals(arr, result, 1e-5f)
    }

    @Test
    fun engineConstantsAreCorrect() {
        assertEquals(150, ChatterboxTtsEngine.COND_PROMPT_LEN)
        assertEquals(256, ChatterboxTtsEngine.MAX_TEXT_LEN)
        assertEquals(34, ChatterboxTtsEngine.COND_LEN)
        assertEquals(1000, ChatterboxTtsEngine.MAX_SPEECH_TOKENS)
        assertEquals(30, ChatterboxTtsEngine.N_LAYERS)
        assertEquals(16, ChatterboxTtsEngine.N_HEADS)
        assertEquals(64, ChatterboxTtsEngine.HEAD_DIM)
        assertEquals(24000, ChatterboxTtsEngine.SAMPLE_RATE)
    }
}
