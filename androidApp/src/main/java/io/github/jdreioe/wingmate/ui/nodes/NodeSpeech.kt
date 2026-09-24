package io.github.jdreioe.wingmate.ui.nodes

import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.nodes.SentenceContent
import io.github.jdreioe.wingmate.domain.nodes.SentenceGraph

internal fun SentenceGraph.toMessage(selected: Int?): Message = Message(parts = sentence(selected).mapIndexed { index, node ->
    val separator = if (index == 0) "" else " "
    when (val content = node.content) {
        is SentenceContent.Word -> MessagePart(separator + content.text)
        is SentenceContent.Pause -> MessagePart(separator + "[${content.milliseconds} ms]", separator + "<break time=\"${content.milliseconds}ms\"/>")
    }
})

/** Explicit segments preserve literal words and leading, adjacent and trailing pauses. */
internal fun SentenceGraph.toSpeechSegments(selected: Int?): List<SpeechSegment> = buildList {
    sentence(selected).forEach { node ->
        when (val content = node.content) {
            is SentenceContent.Word -> {
                val last = lastOrNull()
                if (last != null && last.pauseDurationMs == 0L) {
                    removeAt(lastIndex)
                    add(last.copy(text = last.text + " " + content.text))
                } else add(SpeechSegment(content.text))
            }
            is SentenceContent.Pause -> {
                val last = lastOrNull()
                if (last == null) add(SpeechSegment("", content.milliseconds.toLong()))
                else {
                    removeAt(lastIndex)
                    add(last.copy(pauseDurationMs = last.pauseDurationMs + content.milliseconds))
                }
            }
        }
    }
}
