package io.github.jdreioe.wingmate.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.PageBackgroundFit
import io.github.jdreioe.wingmate.domain.obf.PageBackgroundImage
import io.github.jdreioe.wingmate.infrastructure.InMemoryFileStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class BoardSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun backgroundImageFitAndRemovalAreCommittedWithThePage() {
        val commits = mutableListOf<BackgroundImageEdit>()
        render(PageBackgroundImage("bg", PageBackgroundFit.Fill)) { commits += it }

        composeRule.onNodeWithText(context.getString(R.string.board_settings_background_image_fit)).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        assertEquals(listOf<BackgroundImageEdit>(BackgroundImageEdit.Current(PageBackgroundFit.Fit)), commits)
    }

    @Test
    fun removingTheBackgroundImageCommitsNone() {
        val commits = mutableListOf<BackgroundImageEdit>()
        render(PageBackgroundImage("bg", PageBackgroundFit.Fill)) { commits += it }

        composeRule.onNodeWithContentDescription(context.getString(R.string.board_settings_background_image_remove)).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        assertEquals(listOf<BackgroundImageEdit>(BackgroundImageEdit.None), commits)
    }

    @Test
    fun pickedPicturesAreStoredWithTheScreenAndOtherFilesAreRejected() = runBlocking {
        val storage = InMemoryFileStorage()
        val png = File(context.cacheDir, "background-test.png").apply {
            outputStream().use { Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val text = File(context.cacheDir, "background-test.txt").apply { writeText("not a picture") }
        try {
            val stored = storeBackgroundImage("screen", "file://${png.absolutePath}", storage, urlLoader = null)

            assertEquals("image/png", stored?.contentType)
            assertEquals(8 to 4, stored?.width to stored?.height)
            assertEquals(true, stored?.path?.startsWith("boardsets/screen/images/"))
            assertEquals(png.length().toInt(), storage.loadBytes(stored!!.path!!)?.size)
            assertNull(storeBackgroundImage("screen", "file://${text.absolutePath}", storage, urlLoader = null))
        } finally {
            png.delete()
            text.delete()
        }
    }

    private fun render(background: PageBackgroundImage?, onCommit: (BackgroundImageEdit) -> Unit) {
        composeRule.setContent {
            AppTheme {
                BoardSettingsScreen(
                    target = BoardSettingsTarget.Page,
                    initialName = "Home",
                    initialBackgroundColor = null,
                    initialBackgroundImage = background,
                    screenSettings = BoardSettingsOverrides(),
                    pageSettings = BoardSettingsOverrides(),
                    appShowLabels = true,
                    appShowSymbols = true,
                    appLabelAtTop = false,
                    appShowMessageBar = true,
                    appShowSpeakButton = true,
                    appMessageBarEditable = true,
                    appActivationBehavior = BoardActivationBehavior.SpeakAndAdd,
                    appReturnBehavior = BoardReturnBehavior.Stay,
                    onCommit = { _, _, _, edit -> onCommit(edit) },
                    onBack = {},
                )
            }
        }
    }
}
