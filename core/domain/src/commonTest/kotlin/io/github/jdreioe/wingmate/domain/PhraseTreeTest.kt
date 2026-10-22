package io.github.jdreioe.wingmate.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PhraseTreeTest {
    @Test
    fun subtreeIncludesDescendantsInParentFirstOrderForUndo() {
        val phrases = listOf(
            phrase(id = "root"),
            phrase(id = "child-a", parentId = "root"),
            phrase(id = "grandchild", parentId = "child-a"),
            phrase(id = "child-b", parentId = "root"),
            phrase(id = "unrelated"),
        )

        val removed = phraseSubtree(phrases, "root")

        assertEquals(listOf("root", "child-a", "child-b", "grandchild"), removed.map { it.id })
        assertEquals(emptyList(), phraseSubtree(phrases, "missing"))
    }

    @Test
    fun legacyCategoriesBecomeFolderPhrasesOnce() {
        val categories = listOf(
            CategoryItem(id = "food", name = " Food "),
            CategoryItem(id = "unnamed"),
            CategoryItem(id = "kept", name = "Old name"),
            CategoryItem(id = "unnamed", name = "Duplicate"),
            CategoryItem(id = "", name = "No id"),
        )

        val folders = legacyCategoryFolders(categories, listOf(phrase(id = "kept")), createdAt = 7L)

        assertEquals(listOf("food", "unnamed"), folders.map { it.id })
        assertEquals(listOf("Food", "Category"), folders.map { it.text })
        assertEquals(listOf("food", "unnamed"), folders.map { it.linkedBoardId })
        assertEquals(listOf(false, false), folders.map { it.isGridItem })
    }

    private fun phrase(id: String, parentId: String? = null) = Phrase(
        id = id,
        text = id,
        parentId = parentId,
        createdAt = 1L,
    )
}
