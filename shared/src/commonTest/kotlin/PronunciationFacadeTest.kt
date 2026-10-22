import io.github.jdreioe.wingmate.application.PronunciationFacade
import io.github.jdreioe.wingmate.application.PronunciationStatus
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.infrastructure.InMemoryPronunciationDictionaryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PronunciationFacadeTest {
    @Test
    fun addTrimsTheEntryAndDeleteRemovesIt() = runBlocking {
        val facade = PronunciationFacade(InMemoryPronunciationDictionaryRepository())

        assertTrue(facade.add(" Wingmate ", " win-mate ", "text").isSuccess)
        assertEquals(listOf(PronunciationEntry("Wingmate", "win-mate", "text")), facade.list().entries)

        assertTrue(facade.delete("Wingmate").isSuccess)
        assertTrue(facade.list().entries.isEmpty())
    }

    @Test
    fun blankEntriesAreRejectedWithoutSaving() = runBlocking {
        val repository = InMemoryPronunciationDictionaryRepository()
        val facade = PronunciationFacade(repository)

        assertEquals(PronunciationStatus.ValidationFailure, facade.add("Wingmate", "  ", "text").status)
        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun repositoryFailuresAreRetryablePersistenceFailures() = runBlocking {
        val facade = PronunciationFacade(FailingRepository(IllegalStateException("disk full")))

        assertEquals(PronunciationStatus.PersistenceFailure, facade.list().status)
        val added = facade.add("Wingmate", "win-mate", "text")
        assertEquals(PronunciationStatus.PersistenceFailure, added.status)
        assertTrue(added.isRetryable)
    }

    @Test
    fun cancellationPropagates() {
        val facade = PronunciationFacade(FailingRepository(CancellationException("left the screen")))

        assertFailsWith<CancellationException> { runBlocking { facade.list() } }
    }

    private class FailingRepository(private val failure: Exception) : PronunciationDictionaryRepository {
        override suspend fun getAll(): List<PronunciationEntry> = throw failure
        override suspend fun add(entry: PronunciationEntry) = throw failure
        override suspend fun delete(word: String) = throw failure
        override suspend fun clear() = throw failure
    }
}
