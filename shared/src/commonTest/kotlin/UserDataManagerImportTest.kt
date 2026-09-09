import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.UserDataManager
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserDataManagerImportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun importReplacesExistingHistory() = runBlocking {
        val repo = InMemorySaidTextRepository()
        val manager = UserDataManager(repo)
        repo.add(SaidText(id = 1, saidText = "Old", createdAt = 1L))

        manager.importData(jsonOf(SaidText(id = 2, saidText = "New", createdAt = 2L)))

        assertEquals(listOf("New"), repo.list().map { it.saidText })
    }

    @Test
    fun failedImportNeverDestroysExistingHistory() = runBlocking {
        val repo = FailFirstAddAllRepository(InMemorySaidTextRepository())
        val manager = UserDataManager(repo)
        repo.add(SaidText(id = 1, saidText = "Keep me", createdAt = 1L))

        assertFailsWith<IllegalStateException> {
            manager.importData(
                jsonOf(
                    SaidText(id = 2, saidText = "A", createdAt = 2L),
                    SaidText(id = 3, saidText = "B", createdAt = 3L),
                )
            )
        }

        assertEquals(listOf("Keep me"), repo.list().map { it.saidText })
    }

    private fun jsonOf(vararg items: SaidText): String =
        json.encodeToString(ListSerializer(SaidText.serializer()), items.toList())

    /** Delegates to an in-memory repo; the first addAll (the import) fails, the restore succeeds. */
    private class FailFirstAddAllRepository(
        private val delegate: InMemorySaidTextRepository,
    ) : SaidTextRepository {
        private var firstAddAllDone = false

        override suspend fun add(item: SaidText) = delegate.add(item)
        override suspend fun list() = delegate.list()
        override suspend fun deleteAll() = delegate.deleteAll()
        override suspend fun addAll(items: List<SaidText>) {
            if (!firstAddAllDone) {
                firstAddAllDone = true
                throw IllegalStateException("simulated mid-import failure")
            }
            delegate.addAll(items)
        }
    }
}
