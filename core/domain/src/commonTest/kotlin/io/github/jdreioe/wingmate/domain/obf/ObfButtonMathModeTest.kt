package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObfButtonMathModeTest {
    @Test
    fun mathModeRoundTripsThroughExtension() {
        val button = ObfButton(id = "equation")

        val enabled = button.withMathMode(true)
        assertTrue(enabled.mathMode)
        assertTrue(OBF_MATH_MODE_EXTENSION in enabled.extensions)

        val disabled = enabled.withMathMode(false)
        assertFalse(disabled.mathMode)
        assertFalse(OBF_MATH_MODE_EXTENSION in disabled.extensions)
    }
}
