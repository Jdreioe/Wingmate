package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.buttonSpeechPart

internal fun replaceMessageTextAction(
    currentText: String,
    newText: String,
    mathMode: Boolean = false,
): CommunicationAction.ReplaceRange {
    val prefixLength = commonPrefixLength(currentText, newText)
    val suffixLength = commonSuffixLength(currentText, newText, prefixLength)
    return CommunicationAction.ReplaceRange(
        range = TextSpan(prefixLength, currentText.length - suffixLength),
        replacement = newText.substring(prefixLength, newText.length - suffixLength),
        mathMode = mathMode,
    )
}

internal fun screenMessagePart(
    screenId: String,
    board: ObfBoard,
    button: ObfButton,
    primaryLanguage: String,
): MessagePart? {
    val speechPart = board.buttonSpeechPart(button, primaryLanguage)
    val recordingPath = speechPart?.recordingPath ?: button.soundId
        ?.let { soundId -> board.sounds.firstOrNull { it.id == soundId } }
        ?.path
        ?.takeIf(String::isNotBlank)
    if (speechPart == null && recordingPath == null) return null
    return MessagePart(
        displayText = speechPart?.text.orEmpty(),
        spokenText = speechPart?.text.orEmpty(),
        source = MessagePartSource.ScreenButton(
            screenId = screenId,
            pageId = board.id,
            buttonId = button.id,
        ),
        languageTag = speechPart?.language ?: button.locale,
        recordingPath = recordingPath,
        mathMode = speechPart?.mathMode ?: button.mathMode,
    )
}

internal fun Message.toScreenButtons(graph: BoardSetGraph): List<ObfButton> = parts.mapIndexed { index, part ->
    val source = part.source as? MessagePartSource.ScreenButton
    val original = source
        ?.takeIf { it.screenId == graph.boardSet.id }
        ?.let { graph.boardsById[it.pageId] }
        ?.buttons
        ?.firstOrNull { it.id == source.buttonId }
    original ?: ObfButton(
        id = source?.buttonId ?: "message-part-$index",
        label = part.displayText.trimStart(),
        vocalization = part.spokenText.trimStart(),
        locale = part.languageTag,
    ).withMathMode(part.mathMode)
}

internal fun legacyScreenMessage(
    screenId: String,
    graph: BoardSetGraph,
    buttons: List<ObfButton>,
    primaryLanguage: String,
): Message = buttons.fold(Message()) { message, button ->
    val board = graph.boards.firstOrNull { candidate ->
        candidate.buttons.any { it.id == button.id }
    } ?: graph.boards.firstOrNull()
    val part = board?.let { screenMessagePart(screenId, it, button, primaryLanguage) }
        ?: MessagePart(button.vocalization ?: button.label.orEmpty())
    message.appendPart(part, spellingMode = board?.spellingMode == true)
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
