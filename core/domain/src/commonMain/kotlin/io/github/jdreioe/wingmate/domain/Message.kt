package io.github.jdreioe.wingmate.domain

import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.shouldAddBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakSelectionImmediately
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MessagePartSource {
    @Serializable
    @SerialName("typed")
    data object Typed : MessagePartSource

    @Serializable
    @SerialName("phrase")
    data class Phrase(val phraseId: String) : MessagePartSource

    @Serializable
    @SerialName("screen_button")
    data class ScreenButton(
        val screenId: String,
        val pageId: String,
        val buttonId: String,
    ) : MessagePartSource
}

@Serializable
data class MessagePart(
    val displayText: String,
    val spokenText: String = displayText,
    val source: MessagePartSource = MessagePartSource.Typed,
)

/**
 * A structured Message whose exact rendered text is the concatenation of its parts.
 * Text edits preserve untouched parts and downgrade only edited fragments to typed text.
 */
@Serializable
data class Message(
    val parts: List<MessagePart> = emptyList(),
) {
    val displayText: String
        get() = parts.joinToString("") { it.displayText }

    val spokenText: String
        get() = parts.joinToString("") { it.spokenText }

    fun edit(newText: String): Message {
        val oldText = displayText
        if (newText == oldText) return this

        val prefixLength = commonPrefixLength(oldText, newText)
        val suffixLength = commonSuffixLength(oldText, newText, prefixLength)
        val replacement = newText.substring(prefixLength, newText.length - suffixLength)
        return replaceRange(
            start = prefixLength,
            endExclusive = oldText.length - suffixLength,
            replacement = replacement.takeIf(String::isNotEmpty)?.let(::typedPart),
        )
    }

    fun insertPhrase(cursor: Int, phrase: Phrase): Message = replaceRange(
        start = cursor,
        endExclusive = cursor,
        replacement = MessagePart(
            displayText = phrase.text,
            spokenText = phrase.name?.ifBlank { null } ?: phrase.text,
            source = MessagePartSource.Phrase(phrase.id),
        ),
    )

    fun replaceRange(start: Int, endExclusive: Int, replacement: MessagePart?): Message {
        val textLength = displayText.length
        val from = start.coerceIn(0, textLength)
        val to = endExclusive.coerceIn(from, textLength)
        return Message(
            parts = buildList {
                addAll(sliceParts(0, from))
                replacement?.takeIf { it.displayText.isNotEmpty() }?.let(::add)
                addAll(sliceParts(to, textLength))
            }.mergeAdjacentTypedParts(),
        )
    }

    private fun sliceParts(start: Int, endExclusive: Int): List<MessagePart> {
        if (start >= endExclusive) return emptyList()
        var offset = 0
        return buildList {
            parts.forEach { part ->
                val partStart = offset
                val partEnd = offset + part.displayText.length
                offset = partEnd
                val sliceStart = maxOf(start, partStart)
                val sliceEnd = minOf(endExclusive, partEnd)
                if (sliceStart >= sliceEnd) return@forEach
                if (sliceStart == partStart && sliceEnd == partEnd) {
                    add(part)
                } else {
                    add(typedPart(part.displayText.substring(sliceStart - partStart, sliceEnd - partStart)))
                }
            }
        }
    }
}

data class PhraseActivation(
    val message: Message,
    val shouldSpeak: Boolean,
)

/** Shared activation rule used by every Typing Screen adapter. */
fun Message.activatePhrase(
    phrase: Phrase,
    cursor: Int,
    activationBehavior: BoardActivationBehavior,
    speechPolicy: SpeechPolicy,
): PhraseActivation = PhraseActivation(
    message = if (shouldAddBoardSelection(activationBehavior)) insertPhrase(cursor, phrase) else this,
    shouldSpeak = shouldSpeakSelectionImmediately(speechPolicy, activationBehavior),
)

private fun typedPart(text: String): MessagePart = MessagePart(text)

private fun List<MessagePart>.mergeAdjacentTypedParts(): List<MessagePart> = buildList {
    this@mergeAdjacentTypedParts.forEach { part ->
        val previous = lastOrNull()
        if (previous?.source == MessagePartSource.Typed && part.source == MessagePartSource.Typed) {
            removeAt(lastIndex)
            add(MessagePart(previous.displayText + part.displayText))
        } else {
            add(part)
        }
    }
}

private fun commonPrefixLength(first: String, second: String): Int {
    val limit = minOf(first.length, second.length)
    var index = 0
    while (index < limit && first[index] == second[index]) index++
    return index
}

private fun commonSuffixLength(first: String, second: String, prefixLength: Int): Int {
    val limit = minOf(first.length - prefixLength, second.length - prefixLength)
    var length = 0
    while (length < limit && first[first.lastIndex - length] == second[second.lastIndex - length]) length++
    return length
}
