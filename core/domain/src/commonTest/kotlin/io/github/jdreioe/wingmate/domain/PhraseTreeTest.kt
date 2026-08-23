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

    private fun phrase(id: String, parentId: String? = null) = Phrase(
        id = id,
        text = id,
        parentId = parentId,
        createdAt = 1L,
    )
}
