import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.TypingEffect
import io.github.jdreioe.wingmate.application.TypingEffectKind
import io.github.jdreioe.wingmate.application.TypingScreenFacade
import io.github.jdreioe.wingmate.application.TypingScreenUseCase
import io.github.jdreioe.wingmate.application.TypingTrayElementKind
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TypingScreenFacadeTest {
    @Test
    fun theDefaultTrayIsNavigationThenPhrasesThenActions() = runTest {
        // SettingsStateManager loads on Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val facade = TypingScreenFacade(
                TypingScreenUseCase(InMemoryBoardSetRepository(), InMemoryBoardRepository()),
                SettingsStateManager(InMemorySettingsRepository()),
            )

            val tray = facade.tray()

            assertEquals(
                listOf(
                    TypingTrayElementKind.PageNavigation,
                    TypingTrayElementKind.PhraseCollection,
                    TypingTrayElementKind.ActionStrip,
                ),
                tray.elements.map { it.kind },
            )
            assertEquals(BoardActivationBehavior.SpeakOnly, tray.activationBehavior)
            val actions = tray.elements.last().actions.associate { it.label to it.effects }
            assertEquals(listOf(TypingEffect(TypingEffectKind.HoldMessage, "", "")), actions["Hold"])
            assertEquals(listOf(TypingEffect(TypingEffectKind.InsertText, " [0.5s] ", "")), actions["0.5 s"])
            assertEquals(
                listOf(TypingEffect(TypingEffectKind.WrapSelection, "<say-as interpret-as=\"spell-out\">", "</say-as>")),
                actions["Spell"],
            )
        } finally {
            Dispatchers.resetMain()
        }
    }
}
