package io.github.jdreioe.wingmate.ui.nodes

import io.github.jdreioe.wingmate.domain.nodes.*
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NodeWorkspaceTest {
    @Test fun `connections define order regardless of canvas positions and reject loops or occupied ports`() {
        val graph = SentenceGraph(listOf(
            SentenceNode(1, SentenceContent.Word("coffee"), 0f, 0f),
            SentenceNode(2, SentenceContent.Word("want"), 200f, 0f),
            SentenceNode(3, SentenceContent.Word("I"), 400f, 0f),
        ))
        val connected = graph.connect(3, 2)!!.connect(2, 1)!!
        assertEquals("<speak>I want coffee</speak>", connected.ssml(2))
        assertNull(connected.connect(1, 3))
        assertNull(connected.connect(3, 1))
        assertNull(graph.connect(1, 1))
        assertNull(graph.connect(1, 99))
    }

    @Test fun `SSML round trips escaped words pauses and Danish characters`() {
        val contents = listOf(SentenceContent.Word("blåbær"), SentenceContent.Word("&"), SentenceContent.Pause(1500), SentenceContent.Word("<kaffe>"))
        assertEquals(contents, parseSentenceSsml(SentenceGraph.fromContents(contents).ssml(1)))
        assertEquals(listOf(SentenceContent.Pause(1500)), parseSentenceSsml("<speak><break time=\"1.5s\"/></speak>"))
    }

    @Test fun `invalid or unsupported SSML and XML entities are rejected`() {
        listOf(
            "<speak>unfinished", "<speak><emphasis>hello</emphasis></speak>",
            "<speak><break time=\"0ms\"/></speak>", "<speak><break time=\"11s\"/></speak>",
            "<speak><break time=\"2ms\" strength=\"strong\"/></speak>",
            "<!DOCTYPE speak [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><speak>&secret;</speak>",
            "<speak><?processing x?></speak>",
        ).forEach { assertNull(parseSentenceSsml(it)) }
    }

    @Test fun `invalid code keeps graph and draft through recreation and blocks canvas edits`() {
        val handle = SavedStateHandle()
        val vm = NodeWorkspaceViewModel(handle)
        vm.onAction(NodeAction.Input("hello"))
        vm.onAction(NodeAction.AddWords)
        val graph = vm.state.value.document.graph
        vm.onAction(NodeAction.Draft("<speak>"))
        vm.onAction(NodeAction.ApplySsml)
        vm.onAction(NodeAction.Delete)
        assertNotNull(vm.state.value.error)
        assertEquals(graph, vm.state.value.document.graph)
        assertEquals(vm.state.value.document, NodeWorkspaceViewModel(handle).state.value.document)
    }

    @Test fun `applying code replaces only selected sentence and undo restores it`() {
        val original = SentenceGraph(listOf(
            SentenceNode(1, SentenceContent.Word("hello"), 24f, 24f),
            SentenceNode(2, SentenceContent.Word("coffee"), 324f, 24f),
        ))
        val vm = NodeWorkspaceViewModel(SavedStateHandle(mapOf("nodeDocument" to Json.encodeToString(NodeDocument(original, 1, original.ssml(1))))))
        val before = vm.state.value.document
        vm.onAction(NodeAction.Draft("<speak>tea <break time=\"500ms\"/></speak>"))
        vm.onAction(NodeAction.ApplySsml)
        assertEquals(3, vm.state.value.document.graph.nodes.size)
        assertTrue(vm.state.value.document.graph.nodes.any { it.content == SentenceContent.Word("coffee") })
        vm.onAction(NodeAction.Undo)
        // Undo also restores the editable draft from immediately before Apply.
        assertEquals(before.graph, vm.state.value.document.graph)
    }
}
