package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonFilePronunciationDictionaryRepositoryTest {
    @Test
    fun `entries survive repository restart with their alphabet`() = runBlocking {
        val file = Files.createTempDirectory("wingmate-pronunciation-")
            .resolve("pronunciations.json")
            .toFile()
        val entry = PronunciationEntry("Wingmate", "wɪŋmeɪt", "ipa")

        JsonFilePronunciationDictionaryRepository(file).add(entry)

        assertEquals(
            listOf(entry),
            JsonFilePronunciationDictionaryRepository(file).getAll(),
        )
    }

    @Test
    fun `clear is persisted for backup restore replacement`() = runBlocking {
        val file = Files.createTempDirectory("wingmate-pronunciation-")
            .resolve("pronunciations.json")
            .toFile()
        val repository = JsonFilePronunciationDictionaryRepository(file)
        repository.add(PronunciationEntry("old", "oʊld", "ipa"))

        repository.clear()

        assertEquals(emptyList(), JsonFilePronunciationDictionaryRepository(file).getAll())
    }

    @Test
    fun `phrase metadata and order survive repository restart`() = runBlocking {
        val file = Files.createTempDirectory("wingmate-phrases-").resolve("phrases.json").toFile()
        val repository = JsonFilePhraseRepository(file)
        repository.add(Phrase("one", "First", imageUrl = "file:///first.png", parentId = "folder", createdAt = 1, recordingPath = "/first.wav", isHidden = true))
        repository.add(Phrase("two", "Second", createdAt = 2))

        repository.move(1, 0)

        val restored = JsonFilePhraseRepository(file).getAll()
        assertEquals(listOf("two", "one"), restored.map { it.id })
        assertEquals(true, restored.last().isHidden)
        assertEquals("folder", restored.last().parentId)
        assertEquals("/first.wav", restored.last().recordingPath)
    }

    @Test
    fun `corrupt phrase store is quarantined and never overwritten by add`() = runBlocking {
        val directory = Files.createTempDirectory("wingmate-corrupt-phrases-").toFile()
        val file = directory.resolve("phrases.json")
        val corruptPayload = "[{\"id\":\"truncated"
        file.writeText(corruptPayload)

        val failure = assertFailsWith<PersistenceException> {
            JsonFilePhraseRepository(file).add(Phrase(id = "new", text = "New", createdAt = 1))
        }

        assertEquals(PersistenceError.CorruptOrUnsupported, failure.error)
        assertEquals(corruptPayload, file.readText())
        assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("phrases.json.corrupt-") })
    }

    @Test
    fun `failed atomic phrase write leaves previous store readable`() = runBlocking {
        val file = Files.createTempDirectory("wingmate-failed-write-").resolve("phrases.json").toFile()
        JsonFilePhraseRepository(file).add(Phrase(id = "existing", text = "Existing", createdAt = 1))
        val previousPayload = file.readText()
        val repository = JsonFilePhraseRepository(
            file = file,
            writer = { _, _ -> throw IOException("simulated write failure") },
        )

        val failure = assertFailsWith<PersistenceException> {
            repository.add(Phrase(id = "new", text = "New", createdAt = 2))
        }

        assertEquals(PersistenceError.Io, failure.error)
        assertEquals(previousPayload, file.readText())
        assertEquals(listOf("existing"), JsonFilePhraseRepository(file).getAll().map { it.id })
    }

    @Test
    fun `overlapping phrase mutations are linearized`() = runBlocking {
        val file = Files.createTempDirectory("wingmate-concurrent-phrases-").resolve("phrases.json").toFile()
        val repository = JsonFilePhraseRepository(file)

        coroutineScope {
            (0 until 40).map { index ->
                async { repository.add(Phrase(id = "phrase-$index", text = "Phrase $index", createdAt = index + 1L)) }
            }.awaitAll()
        }

        assertEquals(40, JsonFilePhraseRepository(file).getAll().map { it.id }.toSet().size)
    }

    @Test
    fun `every Linux JSON repository rejects corrupt data before mutation`() = runBlocking {
        suspend fun verify(name: String, mutation: suspend (java.io.File) -> Unit) {
            val file = Files.createTempDirectory("wingmate-corrupt-$name-").resolve("$name.json").toFile()
            val corruptPayload = "{truncated"
            file.writeText(corruptPayload)
            val failure = runCatching { mutation(file) }.exceptionOrNull()
            assertTrue(failure is PersistenceException, "$name did not return a typed persistence failure")
            assertEquals(PersistenceError.CorruptOrUnsupported, failure.error)
            assertEquals(corruptPayload, file.readText(), "$name overwrote its corrupt payload")
        }

        verify("settings") { JsonFileSettingsRepository(it).update(Settings()) }
        verify("voices") { JsonFileVoiceRepository(file = it).saveVoices(listOf(Voice(name = "Test"))) }
        verify("pronunciations") {
            JsonFilePronunciationDictionaryRepository(it).add(PronunciationEntry("word", "wɜːd", "ipa"))
        }
        verify("boards") {
            JsonFileBoardRepository(it).saveBoard(ObfBoard(format = "open-board-0.1", id = "new"))
        }
        verify("board-sets") {
            JsonFileBoardSetRepository(it).saveBoardSet(
                ObfBoardSet("new", "New", "root", listOf("root"), createdAt = 1, updatedAt = 1),
            )
        }
    }
}
