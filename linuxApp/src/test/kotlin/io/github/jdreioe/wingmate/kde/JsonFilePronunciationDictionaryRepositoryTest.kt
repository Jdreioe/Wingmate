package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.CategoryItem
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `category rename and order survive repository restart`() = runBlocking {
        val file = Files.createTempDirectory("wingmate-categories-").resolve("categories.json").toFile()
        val repository = JsonFileCategoryRepository(file)
        repository.add(CategoryItem("one", "First"))
        repository.add(CategoryItem("two", "Second"))
        repository.update(CategoryItem("one", "Renamed"))
        repository.move(1, 0)

        assertEquals(
            listOf("Second", "Renamed"),
            JsonFileCategoryRepository(file).getAll().map { it.name },
        )
    }
}
