package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.platform.ShareService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module

class HistoryAudioSharingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun historySharesItsSavedRecordingWithoutSpeakingAndReportsFailure() {
        val original = GlobalContext.get().get<ShareService>()
        val sharedPaths = mutableListOf<String>()
        var canShare = true
        var playbackCount = 0
        val shareModule = module {
            single<ShareService> {
                object : ShareService {
                    override fun shareAudio(filePath: String): Boolean {
                        sharedPaths += filePath
                        return canShare
                    }
                    override fun shareText(text: String) = false
                    override fun shareFile(fileName: String, content: ByteArray) = false
                }
            }
        }
        loadKoinModules(shareModule)
        try {
            compose.setContent {
                AppTheme {
                    PhraseGrid(
                        phrases = listOf(Phrase(id = "history_1", text = "Example", createdAt = 0L, recordingPath = "/saved/example.mp3")),
                        onPlay = { playbackCount++ },
                        onInsert = { playbackCount++ },
                        onLongPress = {},
                        showAddTile = false,
                        readOnly = true,
                    )
                }
            }
            compose.onNodeWithContentDescription("Share soundfile").performClick()
            compose.runOnIdle {
                assertEquals(listOf("/saved/example.mp3"), sharedPaths)
                assertEquals(0, playbackCount)
                canShare = false
            }
            compose.onNodeWithContentDescription("Share soundfile").performClick()
            compose.onNodeWithText("Could not share the saved audio. The file may no longer be available.")
                .assertIsDisplayed()
        } finally {
            unloadKoinModules(shareModule)
            loadKoinModules(module { single<ShareService> { original } })
        }
    }

    @Test fun historyWithoutARecordingHasNoShareAction() {
        compose.setContent {
            AppTheme {
                PhraseGrid(
                    phrases = listOf(Phrase(id = "history_2", text = "Example", createdAt = 0L)),
                    onPlay = {}, onLongPress = {}, showAddTile = false, readOnly = true,
                )
            }
        }
        compose.onNodeWithContentDescription("Share soundfile").assertDoesNotExist()
    }
}
