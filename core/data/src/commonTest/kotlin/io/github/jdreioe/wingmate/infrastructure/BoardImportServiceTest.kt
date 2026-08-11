package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ArchiveEntry
import io.github.jdreioe.wingmate.platform.ArchiveReadError
import io.github.jdreioe.wingmate.platform.ArchiveReadException
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoardImportServiceTest {

    @Test
    fun cancelledPicker_isDistinctFromFailure() = runBlocking {
        val service = BoardImportService(
            ObfParser(), InMemoryBoardRepository(), InMemoryBoardSetRepository(), FakeFilePicker()
        )
        assertEquals(BoardImportResult.Cancelled, service.importBoardSetResult())
    }

    @Test
    fun unsafeArchiveEntry_isRejectedBeforeReading() = runBlocking {
        val picker = FakeFilePicker(
            zipFiles = mapOf("unsafe.obz" to mapOf("../manifest.json" to byteArrayOf(1)))
        )
        val result = BoardImportService(
            ObfParser(), InMemoryBoardRepository(), InMemoryBoardSetRepository(), picker
        ).importBoardSetFromPathResult("unsafe.obz")
        assertEquals(BoardImportErrorCode.UNSAFE_ENTRY_NAME, assertIs<BoardImportResult.Failure>(result).code)
    }

    @Test
    fun archiveEntryLimit_hasStableFailureCode() = runBlocking {
        val picker = FakeFilePicker(
            zipFiles = mapOf("many.obz" to mapOf("manifest.json" to byteArrayOf(1), "extra" to byteArrayOf(2)))
        )
        val result = BoardImportService(
            ObfParser(), InMemoryBoardRepository(), InMemoryBoardSetRepository(), picker,
            limits = ObzImportLimits(maxEntries = 1)
        ).importBoardSetFromPathResult("many.obz")
        assertEquals(BoardImportErrorCode.TOO_MANY_ENTRIES, assertIs<BoardImportResult.Failure>(result).code)
    }

    @Test
    fun archivePolicyReportsDistinctSafetyAndLimitFailures() = runBlocking {
        suspend fun assertPolicy(
            expected: BoardImportErrorCode,
            entries: List<ArchiveEntry>,
            limits: ObzImportLimits = ObzImportLimits()
        ) {
            val picker = MetadataArchivePicker(entries)
            val result = BoardImportService(
                ObfParser(), InMemoryBoardRepository(), InMemoryBoardSetRepository(), picker,
                limits = limits
            ).importBoardSetFromPathResult("policy.obz")
            assertEquals(expected, assertIs<BoardImportResult.Failure>(result).code)
        }

        assertPolicy(BoardImportErrorCode.DUPLICATE_ENTRY, listOf(
            ArchiveEntry("manifest.json", 1, 1), ArchiveEntry("MANIFEST.JSON", 1, 1)
        ))
        assertPolicy(BoardImportErrorCode.ENCRYPTED_ENTRY, listOf(
            ArchiveEntry("manifest.json", 1, 1, isEncrypted = true)
        ))
        assertPolicy(BoardImportErrorCode.UNSAFE_ENTRY_NAME, listOf(
            ArchiveEntry("bad\uFFFDname", 1, 1)
        ))
        assertPolicy(BoardImportErrorCode.COMPRESSION_RATIO_EXCEEDED, listOf(
            ArchiveEntry("media.bin", 101, 1)
        ))
        assertPolicy(
            BoardImportErrorCode.JSON_ENTRY_TOO_LARGE,
            listOf(ArchiveEntry("manifest.json", 6, 6)),
            ObzImportLimits(maxJsonEntryBytes = 5)
        )
        assertPolicy(
            BoardImportErrorCode.MEDIA_ENTRY_TOO_LARGE,
            listOf(ArchiveEntry("media.bin", 6, 6)),
            ObzImportLimits(maxMediaEntryBytes = 5)
        )
        assertPolicy(
            BoardImportErrorCode.ARCHIVE_TOO_LARGE,
            listOf(ArchiveEntry("one.bin", 6, 6), ArchiveEntry("two.bin", 6, 6)),
            ObzImportLimits(maxTotalUncompressedBytes = 10)
        )
    }

    @Test
    fun malformedInlineMediaFallsBackToReadablePathWithWarning() = runBlocking {
        val storage = InMemoryFileStorage()
        storage.saveBytes("existing/image.png", byteArrayOf(4, 2))
        val picker = FakeFilePicker(textFiles = mapOf("fallback.obf" to """
            {
              "format":"open-board-0.1", "id":"fallback",
              "images":[{"id":"image","data":"not-base64!","path":"existing/image.png","content_type":"image/png"}]
            }
        """.trimIndent()))
        val result = BoardImportService(
            ObfParser(), InMemoryBoardRepository(), InMemoryBoardSetRepository(), picker, storage
        ).importBoardSetFromPathResult("fallback.obf")
        val success = assertIs<BoardImportResult.Success>(result)
        assertTrue(success.warnings.any { it.code == "malformed_data" })
    }

    @Test
    fun invalidGraph_isRejectedBeforePersistence() = runBlocking {
        val boardRepo = InMemoryBoardRepository()
        val setRepo = InMemoryBoardSetRepository()
        val picker = FakeFilePicker(textFiles = mapOf("bad.obf" to """
            {
              "format":"open-board-0.1", "id":"bad",
              "buttons":[{"id":"one"}],
              "grid":{"rows":1,"columns":1,"order":[["missing"]]}
            }
        """.trimIndent()))
        val result = BoardImportService(ObfParser(), boardRepo, setRepo, picker)
            .importBoardSetFromPathResult("bad.obf")
        assertEquals(BoardImportErrorCode.INVALID_GRAPH, assertIs<BoardImportResult.Failure>(result).code)
        assertTrue(boardRepo.listBoards().isEmpty())
        assertTrue(setRepo.listBoardSets().isEmpty())
    }

    @Test
    fun repositoryFailureRollsBackNewBoards() = runBlocking {
        val delegate = InMemoryBoardRepository()
        val failingRepo = object : BoardRepository {
            override suspend fun getBoard(id: String) = delegate.getBoard(id)
            override suspend fun listBoards() = delegate.listBoards()
            override suspend fun deleteBoard(id: String) = delegate.deleteBoard(id)
            override suspend fun saveBoard(board: ObfBoard) {
                delegate.saveBoard(board)
                error("injected write failure")
            }
        }
        val setRepo = InMemoryBoardSetRepository()
        val picker = FakeFilePicker(textFiles = mapOf("valid.obf" to """
            {"format":"open-board-0.1","id":"ok","buttons":[]}
        """.trimIndent()))
        val result = BoardImportService(ObfParser(), failingRepo, setRepo, picker)
            .importBoardSetFromPathResult("valid.obf")
        assertEquals(BoardImportErrorCode.PERSISTENCE_FAILED, assertIs<BoardImportResult.Failure>(result).code)
        assertTrue(delegate.listBoards().isEmpty())
        assertTrue(setRepo.listBoardSets().isEmpty())
    }

    @Test
    fun importSingleObf_persistsBoardSetGraph() = runBlocking {
        val boardRepo = InMemoryBoardRepository()
        val setRepo = InMemoryBoardSetRepository()
        val storage = InMemoryFileStorage()
        val picker = FakeFilePicker(
            textFiles = mapOf(
                "board.obf" to """
                    {
                      "format": "open-board-0.1",
                      "id": "home",
                      "name": "Home",
                      "buttons": [
                        { "id": "b1", "label": "Hi", "image_id": "img1" }
                      ],
                      "grid": { "rows": 1, "columns": 1, "order": [["b1"]] },
                      "images": [
                        {
                          "id": "img1",
                          "content_type": "image/png",
                          "data": "data:image/png;base64,aGVsbG8="
                        }
                      ]
                    }
                """.trimIndent()
            )
        )
        val service = BoardImportService(
            obfParser = ObfParser(),
            boardRepository = boardRepo,
            boardSetRepository = setRepo,
            filePicker = picker,
            fileStorage = storage
        )

        val set = service.importBoardSetFromPath("board.obf")
        assertNotNull(set)
        assertEquals(1, set.boardIds.size)
        val board = boardRepo.getBoard(set.rootBoardId)
        assertNotNull(board)
        assertEquals("Home", board.name)
        val imagePath = board.images.single().path
        assertNotNull(imagePath)
        assertTrue(imagePath.startsWith("boardsets/"))
        assertTrue(storage.exists(imagePath))
        assertEquals(
            "hello".encodeToByteArray().toList(),
            storage.loadBytes(imagePath)?.toList().orEmpty()
        )
    }

    @Test
    fun importObz_persistsLinkedBoardsAndMedia() = runBlocking {
        val boardRepo = InMemoryBoardRepository()
        val setRepo = InMemoryBoardSetRepository()
        val storage = InMemoryFileStorage()
        val homeJson = """
            {
              "format": "open-board-0.1",
              "id": "home",
              "name": "Home",
              "buttons": [
                {
                  "id": "to-food",
                  "label": "Food",
                  "load_board": { "id": "food", "path": "boards/food.obf" },
                  "image_id": "img1"
                }
              ],
              "grid": { "rows": 1, "columns": 1, "order": [["to-food"]] },
              "images": [
                { "id": "img1", "path": "images/food.png", "content_type": "image/png" }
              ]
            }
        """.trimIndent()
        val foodJson = """
            {
              "format": "open-board-0.1",
              "id": "food",
              "name": "Food",
              "ext_wingmate_page_settings": {
                "showMessageBar": false,
                "returnBehavior": "previous"
              },
              "buttons": [
                { "id": "apple", "label": "Apple", "sound_id": "snd1" }
              ],
              "grid": { "rows": 1, "columns": 1, "order": [["apple"]] },
              "sounds": [
                { "id": "snd1", "path": "sounds/beep.mp3", "content_type": "audio/mpeg" }
              ]
            }
        """.trimIndent()
        val manifest = """
            {
              "format": "open-board-0.1",
              "root": "boards/home.obf",
              "ext_wingmate_screen_settings": {
                "showSymbols": false,
                "activationBehavior": "add_only"
              },
              "paths": {
                "boards": {
                  "home": "boards/home.obf",
                  "food": "boards/food.obf"
                },
                "images": { "img1": "images/food.png" },
                "sounds": { "snd1": "sounds/beep.mp3" }
              }
            }
        """.trimIndent()
        val picker = FakeFilePicker(
            zipFiles = mapOf(
                "pack.obz" to mapOf(
                    "manifest.json" to manifest.encodeToByteArray(),
                    "boards/home.obf" to homeJson.encodeToByteArray(),
                    "boards/food.obf" to foodJson.encodeToByteArray(),
                    "images/food.png" to byteArrayOf(1, 2, 3, 4),
                    "sounds/beep.mp3" to byteArrayOf(9, 8, 7)
                )
            )
        )
        val service = BoardImportService(
            obfParser = ObfParser(),
            boardRepository = boardRepo,
            boardSetRepository = setRepo,
            filePicker = picker,
            fileStorage = storage
        )

        val set = service.importBoardSetFromPath("pack.obz")
        assertNotNull(set)
        assertEquals(2, set.boardIds.size)
        assertEquals(false, set.screenSettings.showSymbols)
        assertEquals(BoardActivationBehavior.AddOnly, set.screenSettings.activationBehavior)

        val home = boardRepo.getBoard(set.rootBoardId)
        assertNotNull(home)
        val foodId = home.buttons.single().loadBoard?.id
        assertNotNull(foodId)
        val food = boardRepo.getBoard(foodId)
        assertNotNull(food)
        assertEquals("Food", food.name)
        assertEquals(false, food.pageSettingsOverrides().showMessageBar)
        assertEquals(BoardReturnBehavior.Previous, food.pageSettingsOverrides().returnBehavior)

        val imagePath = home.images.single().path
        assertNotNull(imagePath)
        assertEquals(
            listOf<Byte>(1, 2, 3, 4),
            storage.loadBytes(imagePath)?.toList().orEmpty()
        )

        val soundPath = food.sounds.single().path
        assertNotNull(soundPath)
        assertEquals(
            listOf<Byte>(9, 8, 7),
            storage.loadBytes(soundPath)?.toList().orEmpty()
        )
        assertEquals(soundPath, food.buttons.single().let { btn ->
            food.sounds.first { it.id == btn.soundId }.path
        })
    }

    @Test
    fun importObz_prefersInArchivePathOverRemoteDataUrl() = runBlocking {
        val boardRepo = InMemoryBoardRepository()
        val storage = InMemoryFileStorage()
        val homeJson = """
            {
              "format": "open-board-0.1",
              "id": "home",
              "name": "Home",
              "buttons": [{ "id": "b", "label": "B", "image_id": "img1" }],
              "grid": { "rows": 1, "columns": 1, "order": [["b"]] },
              "images": [
                {
                  "id": "img1",
                  "content_type": "image/png",
                  "path": "images/local.png",
                  "data_url": "https://example.invalid/image.png"
                }
              ]
            }
        """.trimIndent()
        val manifest = """
            {
              "format": "open-board-0.1",
              "root": "boards/home.obf",
              "paths": { "boards": { "home": "boards/home.obf" }, "images": { "img1": "images/local.png" } }
            }
        """.trimIndent()
        val picker = FakeFilePicker(
            zipFiles = mapOf(
                "pack.obz" to mapOf(
                    "manifest.json" to manifest.encodeToByteArray(),
                    "boards/home.obf" to homeJson.encodeToByteArray(),
                    "images/local.png" to byteArrayOf(4, 2, 1)
                )
            )
        )
        // Any remote fetch would fail the test.
        val urlLoader = ObfMediaUrlLoader { _ -> error("remote fetch should not happen for local media") }
        val service = BoardImportService(
            obfParser = ObfParser(),
            boardRepository = boardRepo,
            boardSetRepository = InMemoryBoardSetRepository(),
            filePicker = picker,
            fileStorage = storage,
            urlLoader = urlLoader
        )

        val result = service.importBoardSetFromPathResult("pack.obz")

        val success = assertIs<BoardImportResult.Success>(result)
        assertTrue(success.warnings.none { it.code == "deferred_url" || it.code == "unavailable_data_url" })
        val home = boardRepo.getBoard(success.boardSet.rootBoardId)
        assertNotNull(home)
        val storedPath = home.images.single().path
        assertNotNull(storedPath)
        assertEquals(
            listOf<Byte>(4, 2, 1),
            storage.loadBytes(storedPath)?.toList().orEmpty()
        )
    }

    private class FakeFilePicker(
        private val textFiles: Map<String, String> = emptyMap(),
        private val zipFiles: Map<String, Map<String, ByteArray>> = emptyMap()
    ) : FilePicker {
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override suspend fun readFileAsText(path: String): String? = textFiles[path]
        override suspend fun openArchive(path: String): ArchiveReader? =
            zipFiles[path]?.let(::FakeArchiveReader)
    }

    private class FakeArchiveReader(private val files: Map<String, ByteArray>) : ArchiveReader {
        override suspend fun entries(): List<ArchiveEntry> = files.map { (name, bytes) ->
            ArchiveEntry(name, bytes.size.toLong(), bytes.size.toLong())
        }

        override suspend fun readEntry(
            name: String,
            maxBytes: Long,
            onChunk: suspend (ByteArray) -> Unit
        ) {
            val bytes = files[name]
                ?: throw ArchiveReadException(ArchiveReadError.ENTRY_NOT_FOUND, name)
            if (bytes.size > maxBytes) {
                throw ArchiveReadException(ArchiveReadError.ENTRY_TOO_LARGE, name)
            }
            onChunk(bytes.copyOf())
        }

        override suspend fun close() = Unit
    }

    private class MetadataArchivePicker(private val metadata: List<ArchiveEntry>) : FilePicker {
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override suspend fun readFileAsText(path: String): String? = null
        override suspend fun openArchive(path: String): ArchiveReader = object : ArchiveReader {
            override suspend fun entries(): List<ArchiveEntry> = metadata
            override suspend fun readEntry(name: String, maxBytes: Long, onChunk: suspend (ByteArray) -> Unit) = Unit
            override suspend fun close() = Unit
        }
    }
}
