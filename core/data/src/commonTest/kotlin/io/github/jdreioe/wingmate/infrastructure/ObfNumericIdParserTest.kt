package io.github.jdreioe.wingmate.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObfNumericIdParserTest {

    private val parser = ObfParser()

    @Test
    fun parseBoard_stringifiesNumericIds() {
        val json = """
            {
              "format": "open-board-0.1",
              "id": 42,
              "name": "Numeric",
              "buttons": [
                {
                  "id": 7,
                  "label": "Hi",
                  "image_id": 3,
                  "sound_id": 9
                }
              ],
              "grid": {
                "rows": 1,
                "columns": 1,
                "order": [[7]]
              },
              "images": [
                { "id": 3, "url": "https://example.com/a.png" }
              ],
              "sounds": [
                { "id": 9, "path": "sounds/a.mp3" }
              ]
            }
        """.trimIndent()

        val board = parser.parseBoard(json).getOrThrow()

        assertEquals("42", board.id)
        assertEquals("7", board.buttons.single().id)
        assertEquals("3", board.buttons.single().imageId)
        assertEquals("9", board.buttons.single().soundId)
        assertEquals("3", board.images.single().id)
        assertEquals("9", board.sounds.single().id)
        assertEquals(listOf(listOf("7")), board.grid?.order)
    }

    @Test
    fun parseBoard_keepsStringIds() {
        val json = """
            {
              "format": "open-board-0.1",
              "id": "board-a",
              "buttons": [
                { "id": "btn-1", "label": "A", "image_id": "img-1" }
              ],
              "grid": {
                "rows": 1,
                "columns": 1,
                "order": [["btn-1", null]]
              },
              "images": [
                { "id": "img-1", "path": "images/a.png" }
              ]
            }
        """.trimIndent()

        val board = parser.parseBoard(json).getOrThrow()

        assertEquals("board-a", board.id)
        assertEquals("btn-1", board.buttons.single().id)
        assertEquals("img-1", board.buttons.single().imageId)
        assertEquals(listOf(listOf("btn-1", null)), board.grid?.order)
    }

    @Test
    fun parseBoard_supportsMixedStringAndNumericIds() {
        val json = """
            {
              "format": "open-board-0.1",
              "id": "root",
              "buttons": [
                { "id": 1, "label": "One" },
                { "id": "two", "label": "Two", "image_id": 5 }
              ],
              "grid": {
                "rows": 1,
                "columns": 2,
                "order": [[1, "two"]]
              },
              "images": [
                { "id": 5, "url": "https://example.com/x.png" }
              ]
            }
        """.trimIndent()

        val board = parser.parseBoard(json).getOrThrow()

        assertEquals("root", board.id)
        assertEquals(listOf("1", "two"), board.buttons.map { it.id })
        assertEquals("5", board.buttons[1].imageId)
        assertEquals(listOf(listOf("1", "two")), board.grid?.order)
        assertEquals("5", board.images.single().id)
    }

    @Test
    fun parseManifest_stringifiesNumericBoardKeysAndRootPathStillWorks() {
        val json = """
            {
              "format": "open-board-0.1",
              "root": "boards/root.obf",
              "paths": {
                "boards": {
                  "1": "boards/root.obf",
                  "home": "boards/home.obf"
                },
                "images": {
                  "10": "images/a.png"
                },
                "sounds": {}
              }
            }
        """.trimIndent()

        val manifest = parser.parseManifest(json).getOrThrow()

        assertEquals("boards/root.obf", manifest.root)
        assertEquals("boards/root.obf", manifest.paths.boards["1"])
        assertEquals("boards/home.obf", manifest.paths.boards["home"])
        assertEquals("images/a.png", manifest.paths.images["10"])
        assertNotNull(manifest.paths.boards)
        assertTrue(manifest.paths.sounds.isEmpty())
    }
}
