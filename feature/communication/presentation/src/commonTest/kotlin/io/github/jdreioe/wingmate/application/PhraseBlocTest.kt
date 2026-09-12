package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PhraseBlocTest {
    @Test
    fun `a delayed load cannot overwrite a subsequently added phrase`() = runTest {
        val releaseLoad = CompletableDeferred<Unit>()
        val items = mutableListOf<Phrase>()
        var reads = 0
        val repository = object : PhraseRepository {
            override suspend fun getAll(): List<Phrase> {
                val snapshot = items.toList()
                if (reads++ == 0) releaseLoad.await()
                return snapshot
            }
            override suspend fun add(phrase: Phrase): Phrase = phrase.also(items::add)
            override suspend fun update(phrase: Phrase): Phrase = error("Not used")
            override suspend fun delete(id: String) = error("Not used")
            override suspend fun move(fromIndex: Int, toIndex: Int) = error("Not used")
        }
        val reporter = NoopFeatureUsageReporter()
        val bloc = PhraseBloc(
            PhraseUseCase(repository), reporter, CategoryUseCase(repository, reporter),
            StandardTestDispatcher(testScheduler),
        )
        val phrase = Phrase(id = "new", text = "hello", createdAt = 0L)
        try {
            bloc.dispatch(PhraseEvent.Load)
            runCurrent()
            bloc.dispatch(PhraseEvent.Add(phrase))
            runCurrent()
            releaseLoad.complete(Unit)
            runCurrent()

            assertEquals(listOf(phrase), bloc.state.value.items)
            assertEquals(listOf(phrase), items)
        } finally {
            bloc.close()
        }
    }
}
