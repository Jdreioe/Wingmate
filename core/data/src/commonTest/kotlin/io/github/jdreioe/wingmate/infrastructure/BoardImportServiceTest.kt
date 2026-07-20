package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.FilePicker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoardImportServiceTest {

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
              ],
              "sounds": [
                { "id": "snd1", "path": "sounds/beep.mp3", "content_type": "audio/mpeg" }
              ]
            }
        """.trimIndent()
        val foodJson = """
            {
              "format": "open-board-0.1",
              "id": "food",
              "name": "Food",
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

        val home = boardRepo.getBoard(set.rootBoardId)
        assertNotNull(home)
        val foodId = home.buttons.single().loadBoard?.id
        assertNotNull(foodId)
        val food = boardRepo.getBoard(foodId)
        assertNotNull(food)
        assertEquals("Food", food.name)

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

    private class FakeFilePicker(
        private val textFiles: Map<String, String> = emptyMap(),
        private val zipFiles: Map<String, Map<String, ByteArray>> = emptyMap()
    ) : FilePicker {
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override suspend fun readFileAsText(path: String): String? = textFiles[path]
        override suspend fun readZipEntries(path: String): Map<String, ByteArray>? = zipFiles[path]
    }
}
