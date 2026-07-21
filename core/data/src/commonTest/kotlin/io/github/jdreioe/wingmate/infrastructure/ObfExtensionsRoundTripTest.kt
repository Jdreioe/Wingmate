package io.github.jdreioe.wingmate.infrastructure

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObfExtensionsRoundTripTest {

    private val parser = ObfParser()

    @Test
    fun parseBoard_preservesExtFieldsAndDataUrl() {
        val json = """
            {
              "format": "open-board-0.1",
              "id": "board-1",
              "name": "Ext Board",
              "ext_speaker_url": "http://www.example.com/mylink",
              "ext_nested": { "a": 1, "b": [true, null, "x"] },
              "license": {
                "type": "CC-By",
                "ext_license_note": "keep-me"
              },
              "buttons": [
                {
                  "id": "b1",
                  "label": "Go",
                  "ext_speaker_should_be_hidden_in_preview": true,
                  "load_board": {
                    "id": "other",
                    "data_url": "http://www.example.com/download/other.obf?auth=token",
                    "ext_link_hint": "remote"
                  }
                }
              ],
              "images": [
                {
                  "id": "i1",
                  "data_url": "http://www.example.com/download/img.png?auth=token",
                  "ext_speaker_freshness": "some",
                  "license": {
                    "type": "public domain",
                    "ext_image_license": 9
                  }
                }
              ],
              "sounds": [
                {
                  "id": "s1",
                  "data_url": "http://www.example.com/download/snd.mp3?auth=token",
                  "ext_speaker_coolness": 4
                }
              ],
              "grid": {
                "rows": 1,
                "columns": 1,
                "order": [["b1"]]
              }
            }
        """.trimIndent()

        val board = parser.parseBoard(json).getOrThrow()

        assertEquals("http://www.example.com/mylink", board.extensions["ext_speaker_url"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(board.extensions["ext_nested"])
        assertEquals("keep-me", board.license?.extensions?.get("ext_license_note")?.jsonPrimitive?.contentOrNull)

        val button = board.buttons.single()
        assertEquals(true, button.extensions["ext_speaker_should_be_hidden_in_preview"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(
            "http://www.example.com/download/other.obf?auth=token",
            button.loadBoard?.dataUrl
        )
        assertEquals("remote", button.loadBoard?.extensions?.get("ext_link_hint")?.jsonPrimitive?.contentOrNull)

        val image = board.images.single()
        assertEquals("http://www.example.com/download/img.png?auth=token", image.dataUrl)
        assertEquals("some", image.extensions["ext_speaker_freshness"]?.jsonPrimitive?.contentOrNull)
        assertEquals(9, image.license?.extensions?.get("ext_image_license")?.jsonPrimitive?.intOrNull)

        val sound = board.sounds.single()
        assertEquals("http://www.example.com/download/snd.mp3?auth=token", sound.dataUrl)
        assertEquals(4, sound.extensions["ext_speaker_coolness"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun parseManifest_preservesExtFieldsOnManifestAndPaths() {
        val json = """
            {
              "format": "open-board-0.1",
              "root": "boards/root.obf",
              "ext_pack_source": "wingmate",
              "paths": {
                "boards": { "root": "boards/root.obf" },
                "images": {},
                "sounds": {},
                "ext_paths_note": "paths-ext"
              }
            }
        """.trimIndent()

        val manifest = parser.parseManifest(json).getOrThrow()
        assertEquals("wingmate", manifest.extensions["ext_pack_source"]?.jsonPrimitive?.contentOrNull)
        assertEquals("paths-ext", manifest.paths.extensions["ext_paths_note"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun parseBoard_doesNotPutStandardFieldsIntoExtensions() {
        val json = """
            {
              "format": "open-board-0.1",
              "id": "board-1",
              "name": "Plain",
              "buttons": [],
              "images": [],
              "sounds": []
            }
        """.trimIndent()

        val board = parser.parseBoard(json).getOrThrow()
        assertTrue(board.extensions.isEmpty())
        assertNull(board.extensions["format"])
        assertNull(board.extensions["id"])
    }

    @Test
    fun parseBoard_preservesExtOnNumericIds() {
        val json = """
            {
              "format": "open-board-0.1",
              "id": 10,
              "ext_board_flag": true,
              "buttons": [
                { "id": 1, "label": "A", "ext_btn": "x" }
              ],
              "images": [
                { "id": 2, "data_url": "https://example.com/a.png", "ext_img": 3 }
              ],
              "sounds": [],
              "grid": { "rows": 1, "columns": 1, "order": [[1]] }
            }
        """.trimIndent()

        val board = parser.parseBoard(json).getOrThrow()
        assertEquals(true, board.extensions["ext_board_flag"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("x", board.buttons.single().extensions["ext_btn"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/a.png", board.images.single().dataUrl)
        assertEquals(3, board.images.single().extensions["ext_img"]?.jsonPrimitive?.intOrNull)
    }
}
