import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.application.bloc.PhraseListStoreFactory
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetAllItemsUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.MovePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.infrastructure.InMemoryPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CommunicationFacadeTest {
    @Test
    fun historyListsOnlyVisibleSaidTextsAsPhrases() = runBlocking {
        val saidRepo = InMemorySaidTextRepository()
        val facade = facade(saidRepo)

        saidRepo.add(SaidText(id = 1, saidText = "Hello", audioFilePath = "/tmp/hello.m4a", createdAt = 1000L))
        saidRepo.add(SaidText(id = 2, saidText = "Hidden", createdAt = 2000L, visibleInHistory = false))

        val history = facade.listHistoryAsPhrases()

        assertEquals(1, history.size)
        assertEquals("history-1", history[0].id)
        assertEquals("Hello", history[0].text)
        assertEquals("/tmp/hello.m4a", history[0].recordingPath)
        assertEquals(1000L, history[0].createdAt)
    }

    @Test
    fun storeAccessorsReturnTheSameInstance() {
        val facade = facade()

        assertSame(facade.phraseListStore(), facade.phraseListStoreOrNull())
    }

    private fun facade(
        saidRepo: io.github.jdreioe.wingmate.domain.SaidTextRepository = InMemorySaidTextRepository(),
    ): CommunicationFacade {
        val phraseRepo = InMemoryPhraseRepository()
        val phraseListStore = PhraseListStoreFactory(
            storeFactory = DefaultStoreFactory(),
            getPhrasesAndCategoriesUseCase = GetPhrasesAndCategoriesUseCase(phraseRepo),
            addPhraseUseCase = AddPhraseUseCase(phraseRepo),
            deletePhraseUseCase = DeletePhraseUseCase(phraseRepo),
            updatePhraseUseCase = UpdatePhraseUseCase(phraseRepo),
            movePhraseUseCase = MovePhraseUseCase(phraseRepo),
            getAllItemsUseCase = GetAllItemsUseCase(phraseRepo),
            phraseRepository = phraseRepo,
        ).create()
        return CommunicationFacade(
            phraseListStore = phraseListStore,
            saidTextRepository = saidRepo,
        )
    }
}