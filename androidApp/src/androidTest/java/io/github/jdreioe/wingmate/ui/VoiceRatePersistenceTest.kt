package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.infrastructure.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module

class VoiceRatePersistenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dialogReopensWithSavedRate() = saveAndReopen(fullPage = false)
    @Test fun settingsPageReopensWithSavedRate() = saveAndReopen(fullPage = true)

    private fun saveAndReopen(fullPage: Boolean) {
        val originalVoice = GlobalContext.get().get<VoiceUseCase>()
        val originalSettings = GlobalContext.get().get<SettingsUseCase>()
        val repo = InMemoryVoiceRepository()
        val settings = InMemorySettingsRepository()
        val config = InMemoryConfigRepository()
        val voice = Voice(
            name = "google|CHIRP_3_HD|Kore", displayName = "Chirp test",
            provider = VoiceProvider.GOOGLE, googleModel = GoogleVoiceModel.CHIRP_3_HD,
            providerVoiceName = "Kore", primaryLanguage = "en-US",
            selectedLanguage = "en-US", supportedLanguages = listOf("en-US"), rate = 1.0,
        )
        runBlocking {
            repo.saveVoices(listOf(voice))
            repo.saveSelected(voice)
            settings.update(Settings(ttsEngine = TtsEngine.GOOGLE_CLOUD, primaryLanguage = "en-US"))
        }
        val useCase = VoiceUseCase(repo, AzureVoiceCatalog(config), GoogleVoiceCatalog(config), config, NoopFeatureUsageReporter())
        val overrides = module {
            single { useCase }
            single { SettingsUseCase(settings) }
        }
        loadKoinModules(overrides)
        try {
            compose.setContent {
                AppTheme {
                    if (fullPage) VoiceSelectionPage(onBack = {})
                    else VoiceSelectionDialog(show = true, onDismiss = {})
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Chirp test").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Settings").performClick()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))[1]
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { runBlocking { repo.getSelected()?.rate == 0.75 } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(0.75, runBlocking { useCase.selected()?.rate })
            compose.onNodeWithText("Settings").performClick()
            val reopenedRate = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))[1]
                .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            assertEquals(0.75f, reopenedRate)
        } finally {
            unloadKoinModules(overrides)
            loadKoinModules(module {
                single { originalVoice }
                single { originalSettings }
            })
        }
    }
}
