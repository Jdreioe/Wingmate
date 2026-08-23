import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.phraseSubtree
import io.github.jdreioe.wingmate.infrastructure.InMemoryPhraseRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DeletePhraseUseCaseTest {
    @Test
    fun deletingAndUndoingASubtreeRestoresTheOriginalIds() = runBlocking {
        val repository = InMemoryPhraseRepository()
        val phrases = listOf(
            phrase("root"),
            phrase("child", parentId = "root"),
            phrase("grandchild", parentId = "child"),
        )
        for (phrase in phrases) repository.add(phrase)
        val removed = phraseSubtree(repository.getAll(), "root")

        DeletePhraseUseCase(repository)("root")
        assertEquals(emptyList(), repository.getAll())

        for (phrase in removed) repository.add(phrase)
        assertEquals(phrases.map { it.id }, repository.getAll().map { it.id })
    }

    private fun phrase(id: String, parentId: String? = null) = Phrase(
        id = id,
        text = id,
        parentId = parentId,
        createdAt = 1L,
    )
}
