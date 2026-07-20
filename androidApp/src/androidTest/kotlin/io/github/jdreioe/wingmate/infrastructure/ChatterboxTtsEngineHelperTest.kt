package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatterboxTtsEngineHelperTest {
    @Test
    fun cacheNamesMatchAllThirtyLayers() {
        val inputs = ChatterboxTtsEngine.kvInputNames()
        val outputs = ChatterboxTtsEngine.kvOutputNames()
        assertEquals(60, inputs.size)
        assertEquals(60, outputs.size)
        assertTrue("past_key_values.0.key" in inputs)
        assertTrue("past_key_values.29.value" in inputs)
        assertTrue("present.0.key" in outputs)
        assertTrue("present.29.value" in outputs)
    }

    @Test
    fun argmaxAppliesRepetitionPenalty() {
        val logits = FloatArray(ChatterboxTtsEngine.VOCAB_SIZE)
        logits[10] = 10f
        logits[11] = 9f
        assertEquals(10, ChatterboxTtsEngine.selectArgmaxToken(logits, emptyList(), 1.2f))
        assertEquals(11, ChatterboxTtsEngine.selectArgmaxToken(logits, listOf(10), 2f))
    }

    @Test
    fun samplingUsesNucleusInsteadOfAlwaysTakingArgmax() {
        val logits = FloatArray(ChatterboxTtsEngine.VOCAB_SIZE) { -100f }
        logits[10] = 2f
        logits[11] = 1.9f

        assertEquals(
            11,
            ChatterboxTtsEngine.sampleSpeechToken(
                logits = logits,
                previousTokens = emptyList(),
                repetitionPenalty = 1f,
                temperature = 1f,
                topP = 1f,
                minP = 0f,
                randomValue = 0.99f,
            ),
        )
    }

    @Test
    fun samplingPenaltyCanMoveSelectionAwayFromRepeatedToken() {
        val logits = FloatArray(ChatterboxTtsEngine.VOCAB_SIZE) { -100f }
        logits[10] = 10f
        logits[11] = 9f

        assertEquals(
            11,
            ChatterboxTtsEngine.sampleSpeechToken(
                logits = logits,
                previousTokens = listOf(10),
                repetitionPenalty = 2f,
                temperature = 1f,
                topP = 0.5f,
                minP = 0f,
                randomValue = 0f,
            ),
        )
    }

    @Test
    fun waveformConversionRejectsInvalidOutput() {
        assertThrows(ChatterboxError.InvalidWaveform::class.java) {
            ChatterboxTtsEngine.waveformToPcm16(floatArrayOf())
        }
        assertThrows(ChatterboxError.InvalidWaveform::class.java) {
            ChatterboxTtsEngine.waveformToPcm16(floatArrayOf(Float.NaN))
        }
        assertThrows(ChatterboxError.InvalidWaveform::class.java) {
            ChatterboxTtsEngine.waveformToPcm16(FloatArray(100))
        }
    }

    @Test
    fun waveformConversionProducesPcm16() {
        val pcm = ChatterboxTtsEngine.waveformToPcm16(floatArrayOf(-1f, -0.5f, 0.5f, 1f))
        assertEquals(4, pcm.size)
        assertTrue(pcm.first() < 0)
        assertTrue(pcm.last() > 0)
    }
}
