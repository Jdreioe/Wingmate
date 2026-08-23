import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.infrastructure.InMemoryPhraseRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdatePhraseUseCaseTest {
    @Test
    fun omittedImageUrlKeepsTheExistingImage() = runBlocking {
        val repository = repositoryWithImage()

        val updated = UpdatePhraseUseCase(repository)(
            id = "phrase-1",
            text = "Hello again",
            name = null,
            imageUrl = null,
            recordingPath = null,
        )

        assertEquals("https://example.com/hello.png", updated.imageUrl)
    }

    @Test
    fun explicitBlankImageUrlClearsTheExistingImage() = runBlocking {
        val repository = repositoryWithImage()

        val updated = UpdatePhraseUseCase(repository)(
            id = "phrase-1",
            text = "Hello again",
            name = null,
            imageUrl = "",
            recordingPath = null,
        )

        assertNull(updated.imageUrl)
        assertEquals("Hello again", updated.text)
    }

    private suspend fun repositoryWithImage(): InMemoryPhraseRepository =
        InMemoryPhraseRepository().also { repository ->
            repository.add(
                Phrase(
                    id = "phrase-1",
                    text = "Hello",
                    imageUrl = "https://example.com/hello.png",
                    createdAt = 1L,
                )
            )
        }
}
