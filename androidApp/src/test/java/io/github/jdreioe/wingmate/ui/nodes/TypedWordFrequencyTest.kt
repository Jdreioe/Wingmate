package io.github.jdreioe.wingmate.ui.nodes

import io.github.jdreioe.wingmate.domain.nodes.*
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class TypedWordFrequencyTest {
    @Test fun `counts completed words case insensitively without counting keystrokes or deletion`() {
        val counter = TypedWordFrequency()
        var old = ""
        "Coffee coffee ".forEach { letter ->
            val new = old + letter
            counter.edit(old, new)
            old = new
        }
        assertEquals(mapOf("coffee" to 2), counter.counts)
        counter.edit(old, "")
        assertEquals(mapOf("coffee" to 2), counter.counts)
        counter.edit("", "COFFEE!")
        assertEquals(mapOf("coffee" to 3), counter.counts)
    }

    @Test fun `typing a contraction does not count its unfinished prefix`() {
        val counter = TypedWordFrequency()
        var old = ""
        "don't ".forEach { letter ->
            counter.edit(old, old + letter)
            old += letter
        }
        assertEquals(mapOf("don't" to 1), counter.counts)
    }

    @Test fun `conversion completes final word once and later space does not count it again`() {
        val counter = TypedWordFrequency()
        counter.edit("", "coffee")
        counter.finishForConversion("coffee", "coffee")
        counter.finishForConversion("coffee", "coffee")
        counter.edit("coffee", "coffee ")
        assertEquals(mapOf("coffee" to 1), counter.counts)
        counter.edit("coffee ", "coffee coffee ")
        assertEquals(mapOf("coffee" to 2), counter.counts)
    }

    @Test fun `typing grows an existing node and conversion never duplicates it`() {
        val vm = NodeWorkspaceViewModel(SavedStateHandle())
        vm.recordTyping("", "Coffee ")
        vm.convertWord("Coffee", "Coffee ")
        vm.recordTyping("Coffee ", "Coffee coffee ")
        vm.convertWord("coffee", "Coffee coffee ")
        assertEquals(1, vm.state.value.library.vocabulary.size)
        assertEquals(2, vm.state.value.frequencies["coffee"])
        assertTrue(vm.state.value.document.graph.nodes.isEmpty())
    }

    @Test fun `restoration does not count the converted final word twice`() {
        val handle = SavedStateHandle()
        val vm = NodeWorkspaceViewModel(handle)
        vm.recordTyping("", "coffee")
        vm.convertWord("coffee", "coffee")
        val restored = NodeWorkspaceViewModel(handle)
        restored.convertWord("coffee", "coffee")
        restored.recordTyping("coffee", "coffee ")
        assertEquals(1, restored.state.value.frequencies["coffee"])
        assertEquals(1, restored.state.value.library.vocabulary.size)
    }

    @Test fun `word selection supports Danish words and rejects multiword ranges`() {
        assertEquals("blåbær", wordAtSelection("blåbær kaffe", 3, 3))
        assertEquals("kaffe", wordAtSelection("blåbær kaffe", 7, 12))
        assertNull(wordAtSelection("blåbær kaffe", 0, 12))
    }
}
