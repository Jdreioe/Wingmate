package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObfKeyboardLayoutTest {

    @Test
    fun withKeyboardLayout_roundTripsThroughObfExtensions() {
        val board = ObfBoard(format = "open-board-0.1", id = "kb")
            .withKeyboardLayout(ObfKeyboardLayout.Qwerty)

        assertEquals(ObfKeyboardLayout.Qwerty, board.keyboardLayout)
        assertTrue(board.isKeyboard)
        assertEquals("qwerty", (board.extensions[OBF_KEYBOARD_EXTENSION] as JsonPrimitive).content)
    }

    @Test
    fun withNullLayoutRemovesTheExtension() {
        val board = ObfBoard(format = "open-board-0.1", id = "kb")
            .withKeyboardLayout(ObfKeyboardLayout.Symbols)
            .withKeyboardLayout(null)

        assertNull(board.keyboardLayout)
        assertFalse(board.isKeyboard)
        assertFalse(OBF_KEYBOARD_EXTENSION in board.extensions)
    }

    @Test
    fun unknownWireValue_isIgnored() {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "kb",
            extensions = mapOf(OBF_KEYBOARD_EXTENSION to JsonPrimitive("dvorak"))
        )
        assertNull(board.keyboardLayout)
        assertFalse(board.isKeyboard)
    }

    @Test
    fun nonKeyboardBoardsAreNotKeyboards() {
        val board = ObfBoard(format = "open-board-0.1", id = "home")
        assertFalse(board.isKeyboard)
        assertNull(board.keyboardLayout)
    }

    @Test
    fun keyboardExtensionSurvivesSerializationRoundTrip() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val board = ObfBoard(format = "open-board-0.1", id = "kb")
            .withKeyboardLayout(ObfKeyboardLayout.Symbols)

        val encoded = json.encodeToString(ObfBoard.serializer(), board)
        val decoded = json.decodeFromString(ObfBoard.serializer(), encoded)

        assertEquals(ObfKeyboardLayout.Symbols, decoded.keyboardLayout)
        assertTrue(decoded.isKeyboard)
    }
}