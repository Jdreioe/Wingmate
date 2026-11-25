import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.TypingActionEngineScope
import io.github.jdreioe.wingmate.application.TypingEffect
import io.github.jdreioe.wingmate.application.TypingEffectKind
import io.github.jdreioe.wingmate.application.TypingScreenFacade
import io.github.jdreioe.wingmate.application.TypingScreenUseCase
import io.github.jdreioe.wingmate.application.TypingTrayElementKind
import io.github.jdreioe.wingmate.application.isVisibleForTtsEngine
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.ObfButton
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun elevenLabsAndAzureSsmlTagsFilterByTtsEngine() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val facade = TypingScreenFacade(
                TypingScreenUseCase(InMemoryBoardSetRepository(), InMemoryBoardRepository()),
                SettingsStateManager(InMemorySettingsRepository()),
            )

            val actions = facade.tray().elements.last().actions
            fun labelsFor(engineName: String) =
                actions.filter { it.isVisibleForTtsEngineNamed(engineName) }.map { it.label }

            val elevenLabsLabels = labelsFor("ELEVENLABS")
            assertTrue("Whisper" in elevenLabsLabels)
            assertTrue("Laugh" in elevenLabsLabels)
            assertTrue("Hold" in elevenLabsLabels)
            assertTrue("0.5 s" in elevenLabsLabels)
            assertFalse("Spell" in elevenLabsLabels)
            assertFalse("Strong" in elevenLabsLabels)

            for (azureEngine in listOf("AZURE_USER_RESOURCE", "AZURE_MANAGED")) {
                val azureLabels = labelsFor(azureEngine)
                assertTrue("Spell" in azureLabels)
                assertTrue("Strong" in azureLabels)
                assertTrue("Hold" in azureLabels)
                assertTrue("0.5 s" in azureLabels)
                assertFalse("Whisper" in azureLabels)
                assertFalse("Laugh" in azureLabels)
            }

            for (otherEngine in listOf("SYSTEM", "GOOGLE_CLOUD")) {
                val otherLabels = labelsFor(otherEngine)
                assertTrue("Hold" in otherLabels)
                assertTrue("Language" in otherLabels)
                assertTrue("0.5 s" in otherLabels)
                assertFalse("Whisper" in otherLabels)
                assertFalse("Spell" in otherLabels)
                assertFalse("Strong" in otherLabels)
            }

            assertEquals(TypingActionEngineScope.ElevenLabs, actions.first { it.label == "Whisper" }.engineScope)
            assertEquals(TypingActionEngineScope.Azure, actions.first { it.label == "Spell" }.engineScope)
            assertEquals(TypingActionEngineScope.All, actions.first { it.label == "0.5 s" }.engineScope)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun customActionButtonsRespectEngineTagsAndPreservePlainText() {
        val customElevenLabs = ObfButton(id = "custom:1", label = "Giggle", action = "+ [giggles] ")
        val customSsml = ObfButton(id = "custom:2", label = "Slow", action = ":wrap=<prosody rate=\"slow\">|</prosody>")
        val customPlain = ObfButton(id = "custom:3", label = "Thanks", action = "+ Thanks! ")

        assertTrue(customElevenLabs.isVisibleForTtsEngine(TtsEngine.ELEVENLABS))
        assertFalse(customElevenLabs.isVisibleForTtsEngine(TtsEngine.AZURE_USER_RESOURCE))
        assertFalse(customElevenLabs.isVisibleForTtsEngine(TtsEngine.SYSTEM))

        assertTrue(customSsml.isVisibleForTtsEngine(TtsEngine.AZURE_USER_RESOURCE))
        assertTrue(customSsml.isVisibleForTtsEngine(TtsEngine.AZURE_MANAGED))
        assertFalse(customSsml.isVisibleForTtsEngine(TtsEngine.ELEVENLABS))
        assertFalse(customSsml.isVisibleForTtsEngine(TtsEngine.GOOGLE_CLOUD))

        TtsEngine.entries.forEach { engine ->
            assertTrue(customPlain.isVisibleForTtsEngine(engine))
        }
    }
}
