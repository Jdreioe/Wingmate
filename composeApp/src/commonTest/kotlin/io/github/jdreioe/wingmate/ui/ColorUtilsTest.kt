package io.github.jdreioe.wingmate.ui

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ColorUtilsTest {
    @Test
    fun acceptsSupportedObfColors() {
        assertNotNull(parseObfColorOrNull("#123"))
        assertNotNull(parseObfColorOrNull("#80112233"))
        assertNotNull(parseObfColorOrNull("rgb(1, 2, 3)"))
        assertNotNull(parseObfColorOrNull("rgba(1, 2, 3, 0.5)"))
    }

    @Test
    fun rejectsInvalidObfColors() {
        assertNull(parseObfColorOrNull(null))
        assertNull(parseObfColorOrNull("#12"))
        assertNull(parseObfColorOrNull("#GGGGGG"))
        assertNull(parseObfColorOrNull("rgb(300, 2, 3)"))
        assertNull(parseObfColorOrNull("rgba(1, 2, 3, 2)"))
    }
}
