package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ObfButtonTypeTest {
    @Test
    fun ngramPredictionTypeRoundTripsThroughObfExtensions() {
        val button = ObfButton(id = "predict").withType(ObfButtonType.NGramPrediction)

        assertEquals(ObfButtonType.NGramPrediction, button.type)
        assertEquals("ngram_prediction", (button.extensions[OBF_BUTTON_TYPE_EXTENSION] as JsonPrimitive).content)
    }
}
