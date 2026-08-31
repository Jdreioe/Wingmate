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
    val languageTag: String? = null,
    val recordingPath: String? = null,
    val mathMode: Boolean = false,
)

@Serializable
data class MessageLanguageSpan(
    val range: TextSpan,
    val languageTag: String,
)

@Serializable
data class MessageEditProvenance(
    val range: TextSpan,
    val originalPart: MessagePart,
)

/**
 * A structured Message whose exact rendered text is the concatenation of its parts.
 * Text edits preserve untouched parts and downgrade only edited fragments to typed text.
 */
@Serializable
data class Message(
    val parts: List<MessagePart> = emptyList(),
    val languageSpans: List<MessageLanguageSpan> = emptyList(),
    val editProvenance: List<MessageEditProvenance> = emptyList(),
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
            recordingPath = phrase.recordingPath,
        ),
    )

    fun insertPart(cursor: Int, part: MessagePart): Message = replaceRange(
        start = cursor,
        endExclusive = cursor,
        replacement = part,
    )

    fun appendPart(part: MessagePart, spellingMode: Boolean): Message {
        if (part.displayText.isEmpty()) return this
        val needsSeparator = !spellingMode && displayText.isNotEmpty() &&
            !displayText.last().isWhitespace() && !part.displayText.first().isWhitespace()
        val appended = if (needsSeparator) {
            part.copy(
                displayText = " ${part.displayText}",
                spokenText = " ${part.spokenText}",
            )
        } else {
            part
        }
        return insertPart(displayText.length, appended)
    }

    fun removeLastPart(spellingMode: Boolean): Message {
        val last = parts.lastOrNull() ?: return this
        if (spellingMode && last.displayText.length > 1) {
            return replaceRange(
                start = displayText.length - 1,
                endExclusive = displayText.length,
                replacement = null,
            )
        }
        return replaceRange(
            start = displayText.length - last.displayText.length,
            endExclusive = displayText.length,
            replacement = null,
        )
    }

    fun toggleLanguage(range: TextSpan, languageTag: String): Message {
        val tag = languageTag.trim()
        if (tag.isEmpty()) return this
        val normalized = TextEditingPolicy.normalize(range, displayText.length)
        if (normalized.length == 0) return this
        val tagged = languageSpans.filter { it.languageTag == tag }.map { it.range }
        val updated = TextEditingPolicy.toggle(tagged, normalized, displayText.length)
        return copy(
            languageSpans = (
                languageSpans.filterNot { it.languageTag == tag } +
                    updated.map { MessageLanguageSpan(it, tag) }
                ).sortedBy { it.range.start },
        )
    }

    fun replaceRange(start: Int, endExclusive: Int, replacement: MessagePart?): Message {
        val textLength = displayText.length
        val from = start.coerceIn(0, textLength)
        val to = endExclusive.coerceIn(from, textLength)
        val replacementLength = replacement?.displayText?.length ?: 0
        val edit = TextSpan(from, to)
        val adjustedLanguageSpans = languageSpans
            .groupBy { it.languageTag }
            .flatMap { (languageTag, spans) ->
                TextEditingPolicy.adjustForReplacement(
                    textLength = textLength,
                    edit = edit,
                    replacementLength = replacementLength,
                    spans = spans.map { it.range },
                ).map { MessageLanguageSpan(it, languageTag) }
            }
            .sortedBy { it.range.start }
        val newlyEditedParts = structuredPartsTouchedBy(edit)
        val adjustedProvenance = (editProvenance + newlyEditedParts)
            .distinct()
            .map { provenance ->
                provenance.copy(
                    range = provenance.range.adjustForReplacement(edit, replacementLength)
                )
            }
        val updated = Message(
            parts = replacedParts(from, to, replacement),
            languageSpans = adjustedLanguageSpans,
            editProvenance = adjustedProvenance,
        )
        return updated.restoreMatchingProvenance()
    }

    private fun replacedParts(
        start: Int,
        endExclusive: Int,
        replacement: MessagePart?,
    ): List<MessagePart> = buildList {
        addAll(sliceParts(0, start))
        replacement?.takeIf { it.displayText.isNotEmpty() }?.let(::add)
        addAll(sliceParts(endExclusive, displayText.length))
    }.mergeAdjacentTypedParts()

    private fun structuredPartsTouchedBy(edit: TextSpan): List<MessageEditProvenance> {
        var offset = 0
        return parts.mapNotNull { part ->
            val partStart = offset
            val partEnd = partStart + part.displayText.length
            offset = partEnd
            val touches = if (edit.length == 0) {
                edit.start > partStart && edit.start < partEnd
            } else {
                edit.start < partEnd && edit.endExclusive > partStart
            }
            if (touches && part.source != MessagePartSource.Typed) {
                MessageEditProvenance(TextSpan(partStart, partEnd), part)
            } else {
                null
            }
        }
    }

    private fun restoreMatchingProvenance(): Message {
        val matching = editProvenance.filter { provenance ->
            val range = provenance.range
            range.start >= 0 &&
                range.endExclusive <= displayText.length &&
                displayText.substring(range.start, range.endExclusive) == provenance.originalPart.displayText
        }
        if (matching.isEmpty()) return this

        var restoredParts = parts
        matching.sortedByDescending { it.range.start }.forEach { provenance ->
            val working = copy(parts = restoredParts)
            restoredParts = working.replacedParts(
                provenance.range.start,
                provenance.range.endExclusive,
                provenance.originalPart,
            )
        }
        return copy(
            parts = restoredParts,
            editProvenance = editProvenance - matching.toSet(),
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

private fun TextSpan.adjustForReplacement(edit: TextSpan, replacementLength: Int): TextSpan {
    if (length == 0 && edit.length == 0 && start == edit.start) {
        return TextSpan(start, start + replacementLength)
    }
    val delta = replacementLength - edit.length
    fun adjustStart(position: Int): Int = when {
        position <= edit.start -> position
        position >= edit.endExclusive -> position + delta
        else -> edit.start
    }
    fun adjustEnd(position: Int): Int = when {
        position <= edit.start -> position
        position >= edit.endExclusive -> position + delta
        else -> edit.start + replacementLength
    }
    val adjustedStart = adjustStart(start)
    val adjustedEnd = adjustEnd(endExclusive).coerceAtLeast(adjustedStart)
    return TextSpan(adjustedStart, adjustedEnd)
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
        if (
            previous?.source == MessagePartSource.Typed &&
            part.source == MessagePartSource.Typed &&
            previous.languageTag == part.languageTag &&
            previous.recordingPath == part.recordingPath &&
            previous.mathMode == part.mathMode
        ) {
            removeAt(lastIndex)
            add(
                previous.copy(
                    displayText = previous.displayText + part.displayText,
                    spokenText = previous.spokenText + part.spokenText,
                )
            )
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
