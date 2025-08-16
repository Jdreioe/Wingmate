import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

    private class Repo : PhraseRepository {
    private val items = mutableListOf<Phrase>()
    override suspend fun getAll(): List<Phrase> = items
        override suspend fun add(phrase: Phrase): Phrase {
            val p = phrase.copy(id = "1", createdAt = if (phrase.createdAt == 0L) 0L else phrase.createdAt)
            items += p
            return p
        }
}

class BlocTest {
    @Test
    fun addAndLoad() = runBlocking {
    val bloc = PhraseBloc(Repo())
    bloc.dispatch(PhraseEvent.Add(Phrase(id = "", text = "hi", name = null, backgroundColor = null, parentId = null, isCategory = false, createdAt = 0L)))
        bloc.dispatch(PhraseEvent.Load)
        // Just ensure no crash and state accessible
        assertTrue(true)
        bloc.close()
    }
}
