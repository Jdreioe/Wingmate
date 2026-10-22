package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.Phrase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AddPhraseDialogContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun stringOf(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val phrase = Phrase(id = "phrase-1", text = "Label", name = "Spoken", createdAt = 0L)

    @Test
    fun suppliedMediaRendersErrorAndForwardsActionsWithoutServices() {
        val actions = mutableListOf<PhraseMediaAction>()
        var saved: Phrase? = null
        setContent(
            media = PhraseMediaState(
                imageUrl = "file:///cache/cat.png",
                recordingPath = "/rec/phrase-1.m4a",
                recordingSupported = true,
                error = PhraseMediaError.FinalizeFailed,
            ),
            onMediaAction = { actions += it },
            onSave = { saved = it },
        )

        composeRule.onNodeWithText(stringOf(R.string.phrase_recording_finalize_failed)).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasText(stringOf(R.string.phrase_image_label)) and hasClickAction()).performScrollTo().performClick()
        composeRule.onNodeWithText(stringOf(R.string.phrase_play_button)).performScrollTo().performClick()
        composeRule.onNodeWithText(stringOf(R.string.common_save)).performClick()

        assertEquals(
            listOf(PhraseMediaAction.PickImage, PhraseMediaAction.PlayRecording(textForHistory = "Spoken")),
            actions,
        )
        assertEquals("/rec/phrase-1.m4a", saved?.recordingPath)
        assertEquals("file:///cache/cat.png", saved?.imageUrl)
    }

    @Test
    fun saveAndStopWaitWhileRecorderIsStopping() {
        setContent(
            media = PhraseMediaState(
                recordingSupported = true,
                recordingStatus = PhraseRecordingStatus.Stopping,
            ),
        )

        composeRule.onNodeWithText(stringOf(R.string.common_save)).assertIsNotEnabled()
        composeRule.onNodeWithText(stringOf(R.string.phrase_stop_button)).performScrollTo().assertIsNotEnabled()
    }

    private fun setContent(
        media: PhraseMediaState,
        onMediaAction: (PhraseMediaAction) -> Unit = {},
        onSave: (Phrase) -> Unit = {},
    ) {
        composeRule.setContent {
            AppTheme {
                AddPhraseDialogContent(
                    categories = emptyList(),
                    defaultCategoryId = null,
                    initialPhrase = phrase,
                    draftId = phrase.id,
                    isEditing = true,
                    saving = false,
                    saveFailed = false,
                    media = media,
                    microphoneDenial = null,
                    onMediaAction = onMediaAction,
                    onSave = onSave,
                    onDelete = null,
                    onDismiss = {},
                )
            }
        }
    }
}
