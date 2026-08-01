import io.github.jdreioe.wingmate.application.ObzExporter
import io.github.jdreioe.wingmate.application.ObzExportErrorCode
import io.github.jdreioe.wingmate.application.ObzExportResult
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLicense
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.OBF_SCREEN_SETTINGS_EXTENSION
import io.github.jdreioe.wingmate.domain.obf.encodeBoardSettings
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryFileStorage
import io.github.jdreioe.wingmate.platform.ArchiveEntry
import io.github.jdreioe.wingmate.platform.ArchiveReadError
import io.github.jdreioe.wingmate.platform.ArchiveReadException
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.FilePicker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ObzExporterTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val exporter = ObzExporter(json)
    private val mediaLoader: suspend (String) -> ByteArray? = { path ->
        when (path) {
            "images/hello.png" -> "fake-png".encodeToByteArray()
            "sounds/pizza.mp3" -> "fake-mp3".encodeToByteArray()
            else -> null
        }
    }

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
        sounds = listOf(ObfSound(id = "sound1", path = "sounds/pizza.mp3", contentType = "audio/mpeg")),
        images = emptyList()
    )

    @Test
    fun manifestContainsAllBoards() = runBlocking {
        val zip = exporter.export(
            boards = listOf(rootBoard, foodBoard),
            rootBoardId = "root",
            loadMedia = mediaLoader
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
            rootBoardId = "root",
            loadMedia = mediaLoader
        )
        // Extract manifest.json from zip (simple approach - find it by offset)
        val manifestEntry = extractEntry(zip, "manifest.json")
        assertNotNull(manifestEntry)
        val manifestStr = manifestEntry.decodeToString()
        assertTrue(manifestStr.contains("\"format\""))
        assertTrue(manifestStr.contains("\"root\""))
        assertFalse(json.parseToJsonElement(manifestStr).containsObjectKey("extensions"))
    }

    @Test
    fun manifestIncludesScreenSettingsExtension() = runBlocking {
        val settings = BoardSettingsOverrides(
            showLabels = false,
            activationBehavior = BoardActivationBehavior.SpeakOnly
        )
        val zip = exporter.export(
            boards = listOf(rootBoard),
            rootBoardId = "root",
            loadMedia = mediaLoader,
            manifestExtensions = mapOf(
                OBF_SCREEN_SETTINGS_EXTENSION to encodeBoardSettings(settings)
            )
        )

        val manifest = extractEntry(zip, "manifest.json")?.decodeToString().orEmpty()
        assertTrue(manifest.contains(OBF_SCREEN_SETTINGS_EXTENSION))
        assertTrue(manifest.contains("speak_only"))
        assertFalse(json.parseToJsonElement(manifest).containsObjectKey("extensions"))
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
        val extracted = extractEntry(zip, "images/img1.png")
        assertNotNull(extracted)
        assertTrue(extracted.contentEquals(imageBytes))
    }

    @Test
    fun includesSoundPathInManifest() = runBlocking {
        val zip = exporter.export(
            boards = listOf(foodBoard),
            rootBoardId = "food",
            loadMedia = mediaLoader
        )
        val manifestStr = extractEntry(zip, "manifest.json")?.decodeToString() ?: ""
        assertTrue(manifestStr.contains("sound1"))
        assertNotNull(extractEntry(zip, "sounds/sound1.mp3"))
        Unit
    }

    @Test
    fun missingPathOnlyMediaReturnsTypedFailure() = runBlocking {
        val result = exporter.exportResult(listOf(rootBoard), "root")
        val failure = assertIs<ObzExportResult.Failure>(result)
        assertEquals(ObzExportErrorCode.UNRESOLVED_MEDIA, failure.code)
        assertTrue(failure.resources.single().contains("img1"))
    }

    @Test
    fun exportedBoardsReferenceOnlyPackagedPaths() = runBlocking {
        val privateBoard = rootBoard.copy(
            images = listOf(ObfImage(id = "img1", path = "boardsets/private/images/source", contentType = "image/png"))
        )
        val result = exporter.exportResult(
            listOf(privateBoard, foodBoard),
            "root",
            loadMedia = { path -> if (path.contains("private")) byteArrayOf(1) else mediaLoader(path) }
        )
        val zip = assertIs<ObzExportResult.Success>(result).bytes
        val boardJson = extractEntry(zip, "boards/root.obf")?.decodeToString().orEmpty()
        assertFalse(boardJson.contains("boardsets/private"))
        assertTrue(boardJson.contains("images/img1.png"))
        assertTrue(boardJson.contains("boards/food.obf"))
        assertNotNull(extractEntry(zip, "images/img1.png"))
        Unit
    }

    @Test
    fun duplicateAndUnsafeIdsAreRejected() = runBlocking {
        val duplicate = exporter.exportResult(listOf(rootBoard, rootBoard), "root", mediaLoader)
        assertEquals(ObzExportErrorCode.DUPLICATE_ID, assertIs<ObzExportResult.Failure>(duplicate).code)

        val unsafe = exporter.exportResult(listOf(rootBoard.copy(id = "../root")), "../root", mediaLoader)
        assertEquals(ObzExportErrorCode.UNSAFE_ID, assertIs<ObzExportResult.Failure>(unsafe).code)
    }

    @Test
    fun unicodeIdsProduceSafeUtf8Entries() = runBlocking {
        val board = ObfBoard(format = "open-board-0.1", id = "hjem-æøå")
        val result = exporter.exportResult(listOf(board), board.id)
        val zip = assertIs<ObzExportResult.Success>(result).bytes
        assertNotNull(extractEntry(zip, "boards/hjem-æøå.obf"))
        Unit
    }

    @Test
    fun exportedArchiveReimportsCompleteGraphAndMedia() = runBlocking {
        val exported = exporter.exportResult(listOf(rootBoard, foodBoard), "root", mediaLoader)
        val zip = assertIs<ObzExportResult.Success>(exported).bytes
        val paths = listOf(
            "manifest.json", "boards/root.obf", "boards/food.obf",
            "images/img1.png", "sounds/sound1.mp3"
        )
        val entries = paths.associateWith { path -> assertNotNull(extractEntry(zip, path)) }
        val boardRepo = InMemoryBoardRepository()
        val setRepo = InMemoryBoardSetRepository()
        val storage = InMemoryFileStorage()
        val importer = BoardImportService(
            ObfParser(), boardRepo, setRepo, MapArchivePicker(entries), storage
        )
        val imported = assertIs<BoardImportResult.Success>(importer.importBoardSetFromPathResult("roundtrip.obz"))
        assertEquals(2, imported.boardSet.boardIds.size)
        val home = assertNotNull(boardRepo.getBoard(imported.boardSet.rootBoardId))
        assertNotNull(boardRepo.getBoard(assertNotNull(home.buttons.first { it.id == "b2" }.loadBoard?.id)))
        assertTrue(storage.exists(assertNotNull(home.images.single().path)))
        Unit
    }

    @Test
    fun singleBoardExportStillValid() = runBlocking {
        val zip = exporter.export(
            boards = listOf(rootBoard),
            rootBoardId = "root",
            loadMedia = mediaLoader
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
        assertFalse(json.parseToJsonElement(exported).containsObjectKey("extensions"))
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

    @Test
    fun exportRemovesEmptyInternalExtensionMapsAtEveryLevel() {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "empty-extensions",
            license = ObfLicense(type = "private"),
            buttons = listOf(
                ObfButton(id = "b1", loadBoard = ObfLoadBoard(id = "other"))
            ),
            images = listOf(ObfImage(id = "i1", license = ObfLicense(type = "private"))),
            sounds = listOf(ObfSound(id = "s1", license = ObfLicense(type = "private")))
        )

        val exported = json.parseToJsonElement(exporter.serializeBoard(board))

        assertFalse(exported.containsObjectKey("extensions"))
    }

    @Test
    fun internalSerializationRetainsExtensionMaps() {
        val board = rootBoard.copy(
            extensions = mapOf("ext_internal" to JsonPrimitive("preserved"))
        )

        val stored = json.encodeToString(ObfBoard.serializer(), board)
        val restored = json.decodeFromString(ObfBoard.serializer(), stored)

        assertEquals("preserved", restored.extensions["ext_internal"]?.jsonPrimitive?.content)
    }

    private fun JsonElement.containsObjectKey(key: String): Boolean = when (this) {
        is JsonObject -> key in this || values.any { it.containsObjectKey(key) }
        is JsonArray -> any { it.containsObjectKey(key) }
        else -> false
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

    private class MapArchivePicker(private val files: Map<String, ByteArray>) : FilePicker {
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override suspend fun readFileAsText(path: String): String? = null
        override suspend fun openArchive(path: String): ArchiveReader = object : ArchiveReader {
            override suspend fun entries() = files.map { (name, bytes) ->
                ArchiveEntry(name, bytes.size.toLong(), bytes.size.toLong())
            }

            override suspend fun readEntry(
                name: String,
                maxBytes: Long,
                onChunk: suspend (ByteArray) -> Unit
            ) {
                val bytes = files[name]
                    ?: throw ArchiveReadException(ArchiveReadError.ENTRY_NOT_FOUND, name)
                if (bytes.size > maxBytes) throw ArchiveReadException(ArchiveReadError.ENTRY_TOO_LARGE, name)
                onChunk(bytes)
            }

            override suspend fun close() = Unit
        }
    }
}
