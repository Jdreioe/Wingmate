package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Linux bridge keeps categories/folders canonical: a folder is a phrase with
 * a linkedBoardId (and isGridItem == null), its items are phrases whose parentId
 * points at the folder, and the shared GetPhrasesAndCategoriesUseCase partitions
 * the two. This test pins that invariant against the real JSON-file persistence
 * across a simulated restart.
 */
class LinuxFolderCategorySemanticsTest {
    @Test
    fun `folder relationship survives restart under the shared category use case`() = runBlocking {
        val file = Files.createTempDirectory("wingmate-category-semantics-").resolve("phrases.json").toFile()
        val repository = JsonFilePhraseRepository(file)
        repository.add(Phrase("folder-pets", "Pets", linkedBoardId = "board-pets", createdAt = 1))
        repository.add(Phrase("item-cat", "Cat", parentId = "folder-pets", createdAt = 2))
        repository.add(Phrase("item-dog", "Dog", parentId = "folder-pets", createdAt = 3))
        repository.add(Phrase("folder-food", "Food", linkedBoardId = "board-food", createdAt = 4))

        val restored = GetPhrasesAndCategoriesUseCase(JsonFilePhraseRepository(file)).invoke()

        val (gridItems, folders) = restored
        assertEquals(listOf("item-cat", "item-dog"), gridItems.map { it.id })
        assertEquals(listOf("folder-pets", "folder-food"), folders.map { it.id })
        assertEquals(
            listOf("Cat", "Dog"),
            gridItems.filter { it.parentId == "folder-pets" }.map { it.text },
        )
    }
}