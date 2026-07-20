package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObfButtonActionsTest {

    @Test
    fun resolvedActions_prefersActionsList() {
        val button = ObfButton(
            id = "1",
            action = "+x",
            actions = listOf("+a", ":space", "+b")
        )
        assertEquals(listOf("+a", ":space", "+b"), button.resolvedActions())
    }

    @Test
    fun resolvedActions_fallsBackToSingularAction() {
        val button = ObfButton(id = "1", action = ":clear")
        assertEquals(listOf(":clear"), button.resolvedActions())
    }

    @Test
    fun parse_supportedActions() {
        assertEquals(ObfButtonActionEffect.AppendText("hello"), parseObfButtonAction("+hello"))
        assertEquals(ObfButtonActionEffect.AppendText(" "), parseObfButtonAction(":space"))
        assertEquals(ObfButtonActionEffect.Backspace, parseObfButtonAction(":backspace"))
        assertEquals(ObfButtonActionEffect.Clear, parseObfButtonAction(":clear"))
        assertEquals(ObfButtonActionEffect.Speak, parseObfButtonAction(":speak"))
        assertEquals(ObfButtonActionEffect.Home, parseObfButtonAction(":home"))
    }

    @Test
    fun parse_unknownAction_isUnsupported() {
        val effect = parseObfButtonAction(":jump")
        assertTrue(effect is ObfButtonActionEffect.Unsupported)
        assertEquals(":jump", (effect as ObfButtonActionEffect.Unsupported).action)
    }

    @Test
    fun parseObfButtonActions_mapsInOrder() {
        val button = ObfButton(
            id = "k",
            actions = listOf("+c", ":space", "+a", ":speak")
        )
        assertEquals(
            listOf(
                ObfButtonActionEffect.AppendText("c"),
                ObfButtonActionEffect.AppendText(" "),
                ObfButtonActionEffect.AppendText("a"),
                ObfButtonActionEffect.Speak
            ),
            parseObfButtonActions(button)
        )
    }
}
