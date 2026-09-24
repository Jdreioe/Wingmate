package io.github.jdreioe.wingmate.domain.nodes

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed interface SentenceContent {
    @Serializable @SerialName("io.github.jdreioe.wingmate.ui.nodes.SentenceContent.Word") data class Word(val text: String) : SentenceContent
    @Serializable @SerialName("io.github.jdreioe.wingmate.ui.nodes.SentenceContent.Pause") data class Pause(val milliseconds: Int) : SentenceContent
}

@Serializable
data class SentenceNode(
    val id: Int,
    val content: SentenceContent,
    val x: Float,
    val y: Float,
    val next: Int? = null,
)

@Serializable
data class SentenceGraph(val nodes: List<SentenceNode> = emptyList()) {
    /** One predecessor and successor per node; a connection never silently replaces another. */
    fun connect(from: Int, to: Int): SentenceGraph? {
        val source = nodes.find { it.id == from } ?: return null
        if (from == to || nodes.none { it.id == to } || source.next != null || nodes.any { it.next == to }) return null
        if (chain(to).any { it.id == from }) return null
        return copy(nodes = nodes.map { if (it.id == from) it.copy(next = to) else it })
    }

    fun chain(start: Int): List<SentenceNode> {
        val result = mutableListOf<SentenceNode>()
        var node = nodes.find { it.id == start }
        while (node != null && result.none { it.id == node.id }) {
            result += node
            node = nodes.find { it.id == node.next }
        }
        return result
    }

    fun sentence(selected: Int?): List<SentenceNode> {
        var first = nodes.find { it.id == selected } ?: nodes.firstOrNull() ?: return emptyList()
        val visited = mutableSetOf<Int>()
        while (visited.add(first.id)) {
            first = nodes.find { it.next == first.id } ?: break
        }
        return chain(first.id)
    }

    fun append(contents: List<SentenceContent>, selected: Int?): SentenceGraph {
        if (contents.isEmpty()) return this
        val firstId = (nodes.maxOfOrNull { it.id } ?: 0) + 1
        val tail = sentence(selected).lastOrNull()
        val additions = fromContents(contents, firstId).nodes.mapIndexed { index, node ->
            val position = nodes.size + index
            node.copy(x = 24f + position % 4 * 300f, y = 24f + position / 4 * 150f)
        }
        return copy(nodes = nodes.map { if (it.id == tail?.id) it.copy(next = firstId) else it } + additions)
    }

    fun replace(id: Int, content: SentenceContent) = copy(nodes = nodes.map { if (it.id == id) it.copy(content = content) else it })

    fun remove(id: Int) = copy(nodes = nodes.filterNot { it.id == id }.map { if (it.next == id) it.copy(next = nodes.find { node -> node.id == id }?.next) else it })

    fun ssml(selected: Int?) = "<speak>" + sentence(selected).joinToString(" ") {
        when (val content = it.content) {
            is SentenceContent.Word -> content.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            is SentenceContent.Pause -> "<break time=\"${content.milliseconds}ms\"/>"
        }
    } + "</speak>"

    companion object {
        fun fromContents(contents: List<SentenceContent>, firstId: Int = 1) = SentenceGraph(contents.mapIndexed { index, content ->
            SentenceNode(firstId + index, content, 24f + (index % 4) * 300f, 24f + (index / 4) * 150f,
                if (index < contents.lastIndex) firstId + index + 1 else null)
        })
    }
}

