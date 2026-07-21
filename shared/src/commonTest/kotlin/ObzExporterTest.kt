import io.github.jdreioe.wingmate.application.ObzExporter
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLicense
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObzExporterTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val exporter = ObzExporter(json)

    private val rootBoard = ObfBoard(
        format = "open-board-0.1",
        id = "root",
        name = "Home",
        buttons = listOf(
            ObfButton(id = "b1", label = "Hello", imageId = "img1"),
            ObfButton(id = "b2", label = "Food", loadBoard = io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard(id = "food"))
        ),
        images = listOf(
            ObfImage(id = "img1", path = "images/hello.png")
        )
    )

    private val foodBoard = ObfBoard(
        format = "open-board-0.1",
        id = "food",
        name = "Food",
        buttons = listOf(
            ObfButton(id = "b3", label = "Pizza", soundId = "sound1")
        ),
        images = emptyList()
    )

    @Test
    fun manifestContainsAllBoards() = runBlocking {
        val zip = exporter.export(
            boards = listOf(rootBoard, foodBoard),
            rootBoardId = "root"
        )
        val zipStr = String(zip)
        assertTrue(zipStr.contains("manifest.json"))
        assertTrue(zipStr.contains("boards/root.obf"))
        assertTrue(zipStr.contains("boards/food.obf"))
    }

    @Test
    fun manifestIsValidJson() = runBlocking {
        val zip = exporter.export(
            boards = listOf(rootBoard, foodBoard),
            rootBoardId = "root"
        )
        // Extract manifest.json from zip (simple approach - find it by offset)
        val manifestEntry = extractEntry(zip, "manifest.json")
        assertNotNull(manifestEntry)
        val manifestStr = manifestEntry.decodeToString()
        assertTrue(manifestStr.contains("\"format\""))
        assertTrue(manifestStr.contains("\"root\""))
    }

    @Test
    fun includesImageMediaWhenProvided() = runBlocking {
        val imageBytes = "fake-png".encodeToByteArray()
        val zip = exporter.export(
            boards = listOf(rootBoard),
            rootBoardId = "root",
            loadMedia = { path ->
                if (path == "images/hello.png") imageBytes else null
            }
        )
        val extracted = extractEntry(zip, "images/img1/hello.png")
        assertNotNull(extracted)
        assertTrue(extracted.contentEquals(imageBytes))
    }

    @Test
    fun includesSoundPathInManifest() = runBlocking {
        val zip = exporter.export(
            boards = listOf(foodBoard),
            rootBoardId = "food"
        )
        val manifestStr = extractEntry(zip, "manifest.json")?.decodeToString() ?: ""
        assertTrue(manifestStr.contains("sound1"))
    }

    @Test
    fun singleBoardExportStillValid() = runBlocking {
        val zip = exporter.export(
            boards = listOf(rootBoard),
            rootBoardId = "root"
        )
        val extracted = extractEntry(zip, "boards/root.obf")
        assertNotNull(extracted)
        val boardJson = extracted.decodeToString()
        assertTrue(boardJson.contains("\"id\":\"root\""))
    }

    @Test
    fun roundTripPreservesExtensionsAndDataUrl() = runBlocking {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "ext-board",
            name = "Ext",
            extensions = mapOf("ext_speaker_url" to JsonPrimitive("http://example.com/link")),
            license = ObfLicense(
                type = "CC-By",
                extensions = mapOf("ext_license_note" to JsonPrimitive("keep"))
            ),
            buttons = listOf(
                ObfButton(
                    id = "b1",
                    label = "Go",
                    extensions = mapOf("ext_btn" to JsonPrimitive(true)),
                    loadBoard = ObfLoadBoard(
                        id = "other",
                        dataUrl = "http://example.com/other.obf?auth=1",
                        extensions = mapOf("ext_link" to JsonPrimitive("remote"))
                    )
                )
            ),
            images = listOf(
                ObfImage(
                    id = "i1",
                    dataUrl = "http://example.com/img.png?auth=1",
                    extensions = mapOf("ext_img" to JsonPrimitive("fresh"))
                )
            ),
            sounds = listOf(
                ObfSound(
                    id = "s1",
                    dataUrl = "http://example.com/snd.mp3?auth=1",
                    extensions = mapOf("ext_snd" to JsonPrimitive(4))
                )
            )
        )

        val exported = exporter.serializeBoard(board)
        assertTrue(exported.contains("ext_speaker_url"))
        assertTrue(exported.contains("data_url"))
        assertTrue(exported.contains("ext_btn"))
        assertTrue(exported.contains("ext_link"))

        // Non-ext keys in the extensions map must never replace standard fields.
        val hostile = board.copy(
            extensions = board.extensions + (
                "id" to JsonPrimitive("hijacked")
            ) + (
                "ext_id" to JsonPrimitive("ok-ext")
            )
        )
        val hostileJson = exporter.serializeBoard(hostile)
        val reparsedHostile = ObfParser().parseBoard(hostileJson).getOrThrow()
        assertEquals("ext-board", reparsedHostile.id)
        assertFalse(reparsedHostile.extensions.containsKey("id"))
        assertEquals("ok-ext", reparsedHostile.extensions["ext_id"]?.jsonPrimitive?.contentOrNull)

        val reparsed = ObfParser().parseBoard(exported).getOrThrow()
        assertEquals("http://example.com/link", reparsed.extensions["ext_speaker_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("keep", reparsed.license?.extensions?.get("ext_license_note")?.jsonPrimitive?.contentOrNull)
        assertEquals(true, reparsed.buttons.single().extensions["ext_btn"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("http://example.com/other.obf?auth=1", reparsed.buttons.single().loadBoard?.dataUrl)
        assertEquals("remote", reparsed.buttons.single().loadBoard?.extensions?.get("ext_link")?.jsonPrimitive?.contentOrNull)
        assertEquals("http://example.com/img.png?auth=1", reparsed.images.single().dataUrl)
        assertEquals("fresh", reparsed.images.single().extensions["ext_img"]?.jsonPrimitive?.contentOrNull)
        assertEquals("http://example.com/snd.mp3?auth=1", reparsed.sounds.single().dataUrl)
        assertEquals("4", reparsed.sounds.single().extensions["ext_snd"]?.jsonPrimitive?.content)
    }

    private fun extractEntry(zip: ByteArray, entryName: String): ByteArray? {
        val nameBytes = entryName.encodeToByteArray()
        var pos = 0
        while (pos < zip.size - 4) {
            // Look for local file header signature
            if (zip[pos] == 0x50.toByte() && zip[pos + 1] == 0x4B.toByte() &&
                zip[pos + 2] == 0x03.toByte() && zip[pos + 3] == 0x04.toByte()
            ) {
                val nameLen = ((zip[pos + 27].toInt() and 0xFF) shl 8) or (zip[pos + 26].toInt() and 0xFF)
                val extraLen = ((zip[pos + 29].toInt() and 0xFF) shl 8) or (zip[pos + 28].toInt() and 0xFF)
                val compSize = ((zip[pos + 21].toInt() and 0xFF) shl 24) or
                    ((zip[pos + 20].toInt() and 0xFF) shl 16) or
                    ((zip[pos + 19].toInt() and 0xFF) shl 8) or
                    (zip[pos + 18].toInt() and 0xFF)
                val headerSize = 30 + nameLen + extraLen
                val extractedName = zip.copyOfRange(pos + 30, pos + 30 + nameLen).decodeToString()
                if (extractedName == entryName) {
                    return zip.copyOfRange(pos + headerSize, pos + headerSize + compSize)
                }
                pos += headerSize + compSize
            } else {
                pos++
            }
        }
        return null
    }
}
