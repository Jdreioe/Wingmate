package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jdreioe.wingmate.application.EditingAccessState
import io.github.jdreioe.wingmate.domain.Settings
import com.hojmoseit.wingmate.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun stringOf(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun readyContentShowsRouteTitleAndCategoryRows() {
        composeRule.setContent {
            AppTheme {
                SettingsContent(
                    state = SettingsUiState(
                        route = SettingsRoute.Home,
                        isLoading = false,
                    ),
                    editingAccessState = EditingAccessState(supported = false),
                    editingAccessAvailable = false,
                    onBackToWelcome = null,
                    onAction = {},
                    onGuessPronunciation = { null },
                )
            }
        }

        composeRule.onNodeWithText(stringOf(R.string.ui_settings_title)).assertIsDisplayed()
        composeRule.onNodeWithText(stringOf(R.string.ui_settings_speech_title)).assertIsDisplayed()
    }

    @Test
    fun loadFailureOffersRetry() {
        var retried = false
        composeRule.setContent {
            AppTheme {
                SettingsContent(
                    state = SettingsUiState(isLoading = false, loadFailed = true),
                    editingAccessState = EditingAccessState(supported = false),
                    editingAccessAvailable = false,
                    onBackToWelcome = null,
                    onAction = { action ->
                        if (action == SettingsAction.RetryLoad) retried = true
                    },
                    onGuessPronunciation = { null },
                )
            }
        }

        composeRule.onNodeWithText(stringOf(R.string.settings_load_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(stringOf(R.string.common_retry)).performClick()

        assertTrue(retried)
    }

    @Test
    fun saveFailureBannerRetriesWithoutLosingShownValues() {
        var retried = false
        composeRule.setContent {
            AppTheme {
                SettingsContent(
                    state = SettingsUiState(
                        route = SettingsRoute.Category(SettingsTab.Display),
                        isLoading = false,
                        saveFailed = true,
                        settings = Settings(
                            showLabels = true,
                            showSymbols = true,
                            highContrastMode = true,
                        ),
                    ),
                    editingAccessState = EditingAccessState(supported = false),
                    editingAccessAvailable = false,
                    onBackToWelcome = null,
                    onAction = { action ->
                        if (action == SettingsAction.RetrySave) retried = true
                    },
                    onGuessPronunciation = { null },
                )
            }
        }

        composeRule.onNodeWithText(stringOf(R.string.settings_save_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(stringOf(R.string.common_retry)).performClick()

        assertTrue(retried)
    }

    @Test
    fun pendingRestoreDialogConfirmsThroughAction() {
        var confirmed = false
        composeRule.setContent {
            AppTheme {
                SettingsContent(
                    state = SettingsUiState(
                        route = SettingsRoute.Category(SettingsTab.General),
                        isLoading = false,
                        pendingRestorePath = "/tmp/backup.wingmate-backup",
                    ),
                    editingAccessState = EditingAccessState(supported = false),
                    editingAccessAvailable = false,
                    onBackToWelcome = null,
                    onAction = { action ->
                        if (action == SettingsAction.RestoreConfirmed) confirmed = true
                    },
                    onGuessPronunciation = { null },
                )
            }
        }

        composeRule.onNodeWithText(stringOf(R.string.backup_replace_title)).assertIsDisplayed()
        composeRule.onNodeWithText(stringOf(R.string.backup_replace_action)).performClick()

        assertTrue(confirmed)
    }
}
