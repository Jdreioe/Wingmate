package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ObfButtonShapeTest {

    @Test
    fun eachShapeWireValueRoundTripsThroughObfExtensions() {
        ObfButtonShape.entries.forEach { expected ->
            val button = ObfButton(id = "b").withShape(expected)
            assertEquals(expected, button.shape)
            if (expected == ObfButtonShape.Square) {
                // Default shape is not persisted as an extension.
                assertNull(button.extensions[OBF_BUTTON_STYLE_EXTENSION])
            } else {
                assertEquals(
                    expected.wireValue,
                    (button.extensions[OBF_BUTTON_STYLE_EXTENSION] as JsonPrimitive).content
                )
            }
        }
    }

    @Test
    fun defaultIsSquareAndClearsExtension() {
        val button = ObfButton(id = "b")
        assertEquals(ObfButtonShape.Square, button.shape)
        assertNull(button.extensions[OBF_BUTTON_STYLE_EXTENSION])

        val defaulted = ObfButton(id = "b").withShape(ObfButtonShape.Pill).withShape(ObfButtonShape.Square)
        assertEquals(ObfButtonShape.Square, defaulted.shape)
        assertNull(defaulted.extensions[OBF_BUTTON_STYLE_EXTENSION])
    }

    @Test
    fun unknownWireValueFallsBackToSquare() {
        val button = ObfButton(
            id = "b",
            extensions = mapOf(OBF_BUTTON_STYLE_EXTENSION to JsonPrimitive("hexagon"))
        )
        assertEquals(ObfButtonShape.Square, button.shape)
        assertFalse(button.extensions.containsKey("ext_wingmate_does_not_exist"))
    }

    @Test
    fun nonStringValueFallsBackToSquare() {
        val button = ObfButton(
            id = "b",
            extensions = mapOf(OBF_BUTTON_STYLE_EXTENSION to JsonPrimitive(42))
        )
        assertEquals(ObfButtonShape.Square, button.shape)
    }

    @Test
    fun unknownValueSurvivesCopyIfNotReset() {
        val button = ObfButton(
            id = "b",
            extensions = mapOf(OBF_BUTTON_STYLE_EXTENSION to JsonPrimitive("future_shape"))
        )
        // A forward-compatible import still keeps the raw extension until the
        // author explicitly overrides it.
        assertEquals("future_shape", button.extensions[OBF_BUTTON_STYLE_EXTENSION]?.jsonPrimitive?.content)
        val overridden = button.withShape(ObfButtonShape.Thought)
        assertEquals(ObfButtonShape.Thought, overridden.shape)
        assertEquals("thought", overridden.extensions[OBF_BUTTON_STYLE_EXTENSION]?.jsonPrimitive?.content)
    }
}
