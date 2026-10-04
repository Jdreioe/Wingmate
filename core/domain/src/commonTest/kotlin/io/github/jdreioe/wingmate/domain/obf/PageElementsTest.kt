package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageElementsTest {
    @Test
    fun `unknown elements survive board serialization helpers`() {
        val unknown = PageElement(
            id = "future",
            type = "future_gallery",
            row = 2,
            column = 1,
            rowSpan = 3,
            configuration = JsonObject(mapOf("future_option" to JsonPrimitive("kept"))),
        )

        val board = ObfBoard(format = "open-board-0.1", id = "page").withPageElements(listOf(unknown))
        val restored = board.pageElements().single()

        assertEquals(unknown, restored)
        assertFalse(restored.isSupported)
    }

    @Test
    fun `unknown top-level properties survive edits by an older client`() {
        val rawElement = JsonObject(
            mapOf(
                "id" to JsonPrimitive("future"),
                "type" to JsonPrimitive("future_gallery"),
                "row" to JsonPrimitive(2),
                "column" to JsonPrimitive(1),
                "future_layout" to JsonObject(mapOf("mode" to JsonPrimitive("masonry"))),
            )
        )
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "page",
            extensions = mapOf(OBF_PAGE_ELEMENTS_EXTENSION to kotlinx.serialization.json.JsonArray(listOf(rawElement))),
        )

        val edited = board.pageElements().single().copy(row = 4)
        val saved = board.withPageElements(listOf(edited))
        val savedElement = (saved.extensions.getValue(OBF_PAGE_ELEMENTS_EXTENSION) as kotlinx.serialization.json.JsonArray)
            .single() as JsonObject

        assertEquals(JsonPrimitive(4), savedElement["row"])
        assertEquals(rawElement["future_layout"], savedElement["future_layout"])
    }

    @Test
    fun `action strip configuration is typed`() {
        val element = PageElement("actions", PageElementTypes.ActionStrip, 0, 0)
            .withConfiguration(ActionStripElementConfig(listOf("play", "stop")))

        assertTrue(element.isSupported)
        assertTrue(element.content is PageElementContent.ActionStrip)
        assertEquals(
            listOf("play", "stop"),
            element.decodeConfiguration<ActionStripElementConfig>()?.buttonIds,
        )
    }

    @Test
    fun `an undecodable element survives edits to known elements`() {
        val malformedFutureElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("future_gallery"),
                "payload" to JsonPrimitive("keep me"),
            )
        )
        val known = PageElement("phrases", PageElementTypes.PhraseCollection, 0, 0)
        val seeded = ObfBoard(
            format = "open-board-0.1",
            id = "page",
            extensions = mapOf(
                OBF_PAGE_ELEMENTS_EXTENSION to JsonArray(
                    listOf(known.encodeForTest(), malformedFutureElement)
                )
            ),
        )

        val saved = seeded.withPageElements(listOf(known.copy(row = 3)))
        val raw = saved.extensions.getValue(OBF_PAGE_ELEMENTS_EXTENSION) as JsonArray

        assertEquals(malformedFutureElement, raw[1])
        assertEquals(3, saved.pageElements().single().row)
    }

    private fun PageElement.encodeForTest() =
        ObfBoard(format = "open-board-0.1", id = "temp")
            .withPageElements(listOf(this))
            .extensions.getValue(OBF_PAGE_ELEMENTS_EXTENSION)
            .let { it as JsonArray }
            .single()
}
