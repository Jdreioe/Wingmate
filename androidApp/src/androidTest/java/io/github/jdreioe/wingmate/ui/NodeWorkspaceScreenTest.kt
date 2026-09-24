package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.nodes.*
import io.github.jdreioe.wingmate.domain.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import io.github.jdreioe.wingmate.ui.nodes.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class NodeWorkspaceScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun disconnected(): NodeWorkspaceViewModel {
        val graph = SentenceGraph(listOf(
            SentenceNode(1, SentenceContent.Word("I"), 24f, 24f),
            SentenceNode(2, SentenceContent.Word("want"), 324f, 24f),
        ))
        return NodeWorkspaceViewModel(SavedStateHandle(mapOf("nodeDocument" to Json.encodeToString(NodeDocument(graph, 1, graph.ssml(1))))))
    }

    @Test fun connectWordsWithTouchAndApplyPauseThroughSsml() {
        val vm = disconnected()
        compose.setContent { AppTheme { NodeWorkspaceRoot(onBack = {}, viewModel = vm) } }
        compose.onNodeWithContentDescription("Connect from I").performClick()
        compose.onNodeWithContentDescription("Connect to want").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("<speak>I want</speak>", vm.state.value.document.draft) }
        compose.onNodeWithText("SSML").performClick()
        compose.onNodeWithText("SSML code").performTextReplacement("<speak>I <break time=\"500ms\"/> want</speak>")
        compose.onNodeWithText("Apply SSML").performClick()
        compose.runOnIdle {
            assertEquals(3, vm.state.value.document.graph.nodes.size)
            assertEquals(SentenceContent.Pause(500), vm.state.value.sentence[1].content)
        }
        compose.onNodeWithText("SSML code").performTextReplacement("<speak><unsupported/></speak>")
        compose.onNodeWithText("Apply SSML").performClick()
        compose.runOnIdle {
            assertNotNull(vm.state.value.error)
            assertEquals(3, vm.state.value.document.graph.nodes.size)
        }
    }

    @Test fun dragOutputToInputCreatesConnection() {
        val vm = disconnected()
        compose.setContent { AppTheme { NodeWorkspaceRoot(onBack = {}, viewModel = vm) } }
        val output = compose.onNodeWithContentDescription("Connect from I")
        val input = compose.onNodeWithContentDescription("Connect to want")
        val delta = input.fetchSemanticsNode().boundsInRoot.center - output.fetchSemanticsNode().boundsInRoot.center
        output.performTouchInput { swipe(center, center + delta, durationMillis = 500) }
        compose.runOnIdle { assertEquals("<speak>I want</speak>", vm.state.value.document.draft) }
    }

    @Test fun speakReplaceCoffeeWithTeaAndSpeakAgain() {
        val session = object : CommunicationSession {
            override val state = MutableStateFlow(CommunicationSessionState(isInitialized = true))
            val actions = mutableListOf<CommunicationAction>()
            override fun accept(action: CommunicationAction) { actions += action }
            override suspend fun reloadAfterRestore() = Unit
        }
        val vm = NodeWorkspaceViewModel(SavedStateHandle(), communicationSession = session)
        val models = ViewModelStore().apply { put("nodes", vm) }
        try {
            vm.onAction(NodeAction.Input("I want coffee"))
            vm.onAction(NodeAction.AddWords)
            vm.convertWord("tea", "tea ")
            vm.onAction(NodeAction.Input(""))
            compose.setContent { AppTheme { NodeWorkspaceRoot(onBack = {}, viewModel = vm) } }
            compose.onNodeWithText("Speak").performClick()
            compose.waitUntil { session.actions.size == 1 }
            compose.onNodeWithText("Replace selected").performClick()
            compose.onAllNodesWithText("Find or add words").onLast().performTextReplacement("tea")
            compose.onNode(hasText("tea") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText("Speak").performClick()
            compose.waitUntil { session.actions.size == 2 }
            compose.runOnIdle {
                assertEquals(listOf("I want coffee", "I want tea"), session.actions.map { (it as CommunicationAction.SpeakMessage).message.displayText })
                assertEquals(4, vm.state.value.library.vocabulary.size)
                assertEquals("I want tea", vm.state.value.preview)
            }
            val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            File(directory, "node-workspace-stage2.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { models.clear() }
    }
}
