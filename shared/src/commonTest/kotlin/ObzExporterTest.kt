import io.github.jdreioe.wingmate.application.ObzExporter
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
