package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.obf.ActionStripElementConfig
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.PageElement
import io.github.jdreioe.wingmate.domain.obf.PageElementTypes
import io.github.jdreioe.wingmate.domain.obf.PageNavigationElementConfig
import io.github.jdreioe.wingmate.domain.obf.PhraseCollectionElementConfig
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import io.github.jdreioe.wingmate.domain.obf.withConfiguration
import io.github.jdreioe.wingmate.domain.obf.withPageElements
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.time.Clock

const val TYPING_SCREEN_ID = "wingmate:typing"
const val TYPING_SCREEN_TEMPLATE_PAGE_ID = "typing:template"
const val TYPING_ALL_PAGE_ID = "typing:all"
const val TYPING_HISTORY_PAGE_ID = "typing:history"
const val TYPING_LIKELY_NEXT_PAGE_ID = "typing:likely-next"
const val OBF_PHRASE_ID_EXTENSION = "ext_wingmate_phrase_id"
const val OBF_HISTORY_ID_EXTENSION = "ext_wingmate_history_id"

fun typingCategoryPageId(categoryId: String): String = "typing:category:$categoryId"

/**
 * Classifies which TTS engines support a Typing Action-strip Button:
 * - ElevenLabs audio tags (`typing:tag:*` or bracketed tags such as `[whispers]`) apply only to ElevenLabs.
 * - XML SSML tags (`<emphasis>`, `<say-as>`, etc., or `typing:ssml:*` other than universal `[0.5s]` pauses) apply only to Azure.
 * - Playback controls, pause shorthands, and custom plain-text Buttons apply to all engines.
 */
fun ObfButton.typingActionEngineScope(): TypingActionEngineScope =
    typingActionEngineScopeFor(id, parseObfButtonActions(this))

fun ObfButton.isVisibleForTtsEngine(ttsEngine: TtsEngine): Boolean =
    typingActionEngineScope().matches(ttsEngine)

internal fun typingActionEngineScopeFor(
    buttonId: String,
    effects: List<ObfButtonActionEffect>,
): TypingActionEngineScope {
    if (isElevenLabsTagAction(buttonId, effects)) return TypingActionEngineScope.ElevenLabs
    if (isAzureSsmlTagAction(buttonId, effects)) return TypingActionEngineScope.Azure
    return TypingActionEngineScope.All
}

private fun isElevenLabsTagAction(buttonId: String, effects: List<ObfButtonActionEffect>): Boolean {
    if (buttonId.startsWith("typing:tag:")) return true
    return effects.any { effect ->
        effect is ObfButtonActionEffect.AppendText &&
            SpeechTextProcessor.stripAudioTags(effect.text) != effect.text
    }
}

private fun isAzureSsmlTagAction(buttonId: String, effects: List<ObfButtonActionEffect>): Boolean {
    if (buttonId.startsWith("typing:ssml:") && !buttonId.startsWith("typing:ssml:pause-")) return true
    return effects.any { effect ->
        when (effect) {
            is ObfButtonActionEffect.WrapSelection ->
                effect.prefix.contains('<') || effect.suffix.contains('<')
            is ObfButtonActionEffect.AppendText ->
                effect.text.contains('<') && effect.text.contains('>')
            else -> false
        }
    }
}

data class TypingScreenPage(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val isHistory: Boolean = false,
)

data class TypingScreenProjection(
    val graph: BoardSetGraph,
    val pages: List<TypingScreenPage>,
)

class TypingScreenUseCase(
    private val boardSetRepository: BoardSetRepository,
    private val boardRepository: BoardRepository,
) {
    suspend fun getOrCreate(columns: Int): BoardSetGraph {
        boardSetRepository.listBoardSets().firstOrNull { it.kind == ScreenKind.Typing }?.let { existing ->
            val boards = existing.boardIds.mapNotNull { boardRepository.getBoard(it) }
            if (boards.isNotEmpty()) return BoardSetGraph(existing, boards)
        }

        require(boardSetRepository.getBoardSet(TYPING_SCREEN_ID) == null) {
            "The reserved Typing Screen ID is already in use"
        }
        val graph = TypingScreenProjector.defaultTemplate(columns)
        try {
            boardRepository.saveBoards(graph.boards)
            boardSetRepository.saveBoardSet(graph.boardSet)
        } catch (failure: Throwable) {
            graph.boards.forEach { boardRepository.deleteBoard(it.id) }
            boardSetRepository.deleteBoardSet(graph.boardSet.id)
            throw failure
        }
        return graph
    }
}

object TypingScreenProjector {
    private const val PAGE_NAVIGATION_ID = "typing-element:navigation"
    private const val PHRASE_COLLECTION_ID = "typing-element:phrases"
    private const val ACTION_STRIP_ID = "typing-element:actions"

    /** English labels of the default template's Action-strip Buttons, keyed by Button ID. */
    private val defaultActionLabels: Map<String, String> by lazy {
        defaultActionButtons().associate { it.id to it.label.orEmpty() }
    }

    /**
     * The ID of a default template Button that still shows its English default label,
     * so a client can show its own translation. Null for Buttons the user added or renamed.
     */
    fun defaultActionLabelKey(button: ObfButton): String? =
        button.id.takeIf { id -> defaultActionLabels[id]?.let { it == button.label } == true }

    fun defaultTemplate(columns: Int, now: Long = Clock.System.now().toEpochMilliseconds()): BoardSetGraph {
        val safeColumns = columns.coerceIn(1, 12)
        val buttons = defaultActionButtons()
        val elements = listOf(
            PageElement(PAGE_NAVIGATION_ID, PageElementTypes.PageNavigation, 0, 0, columnSpan = safeColumns)
                .withConfiguration(PageNavigationElementConfig()),
            PageElement(PHRASE_COLLECTION_ID, PageElementTypes.PhraseCollection, 1, 0, rowSpan = 7, columnSpan = safeColumns)
                .withConfiguration(PhraseCollectionElementConfig(columns = safeColumns)),
            PageElement(ACTION_STRIP_ID, PageElementTypes.ActionStrip, 8, 0, columnSpan = safeColumns)
                .withConfiguration(ActionStripElementConfig(buttons.map { it.id })),
        )
        val board = ObfBoard(
            format = "open-board-0.1",
            id = TYPING_SCREEN_TEMPLATE_PAGE_ID,
            name = "Typing",
            buttons = buttons,
            grid = ObfGrid(9, safeColumns, List(9) { List(safeColumns) { null } }),
        ).withPageElements(elements)
        val boardSet = ObfBoardSet(
            id = TYPING_SCREEN_ID,
            name = "Typing",
            rootBoardId = board.id,
            boardIds = listOf(board.id),
            screenSettings = BoardSettingsOverrides(
                activationBehavior = BoardActivationBehavior.SpeakOnly,
                messageBarEditable = true,
                showMessageBar = true,
            ),
            kind = ScreenKind.Typing,
            createdAt = now,
            updatedAt = now,
        )
        return BoardSetGraph(boardSet, listOf(board))
    }

    private fun defaultActionButtons(): List<ObfButton> {
        val ssmlButtons = listOf(
            actionButton("typing:ssml:pause-05", "0.5 s", "+ [0.5s] "),
            actionButton("typing:ssml:pause-10", "1.0 s", "+ [1.0s] "),
            actionButton("typing:ssml:pause-20", "2.0 s", "+ [2.0s] "),
            actionButton("typing:ssml:emphasis-reduced", "Reduced", ":wrap=<emphasis level=\"reduced\">|</emphasis>"),
            actionButton("typing:ssml:emphasis-moderate", "Moderate", ":wrap=<emphasis level=\"moderate\">|</emphasis>"),
            actionButton("typing:ssml:emphasis-strong", "Strong", ":wrap=<emphasis level=\"strong\">|</emphasis>"),
            actionButton("typing:ssml:spell", "Spell", ":wrap=<say-as interpret-as=\"spell-out\">|</say-as>"),
            actionButton("typing:ssml:number", "Number", ":wrap=<say-as interpret-as=\"number\">|</say-as>"),
            actionButton("typing:ssml:date", "Date", ":wrap=<say-as interpret-as=\"date\">|</say-as>"),
            actionButton("typing:ssml:time", "Time", ":wrap=<say-as interpret-as=\"time\">|</say-as>"),
            actionButton("typing:ssml:telephone", "Telephone", ":wrap=<say-as interpret-as=\"telephone\">|</say-as>"),
            actionButton("typing:ssml:currency", "Currency", ":wrap=<say-as interpret-as=\"currency\">|</say-as>"),
        )
        // ElevenLabs audio tags direct delivery; other voices skip them.
        val emotionButtons = listOf(
            actionButton("typing:tag:whispers", "Whisper", "+ [whispers] "),
            actionButton("typing:tag:laughs", "Laugh", "+ [laughs] "),
            actionButton("typing:tag:sighs", "Sigh", "+ [sighs] "),
            actionButton("typing:tag:happy", "Happy", "+ [happy] "),
            actionButton("typing:tag:sad", "Sad", "+ [sad] "),
            actionButton("typing:tag:excited", "Excited", "+ [excited] "),
            actionButton("typing:tag:angry", "Angry", "+ [angry] "),
            actionButton("typing:tag:sarcastic", "Sarcastic", "+ [sarcastic] "),
        )
        // Speak, Pause, Resume and Stop live in the Message bar, so the default
        // strip holds only what the bar lacks (#299). Users can still add them.
        val messageButtons = listOf(
            actionButton("typing:playback:hold", "Hold", ":hold-message"),
            actionButton("typing:playback:secondary", "Language", ":secondary-language"),
        )
        return messageButtons + emotionButtons + ssmlButtons
    }

    fun project(
        template: BoardSetGraph,
        phrases: List<Phrase>,
        categories: List<CategoryItem>,
        history: List<SaidText>,
        columns: Int,
        includeHistory: Boolean,
    ): TypingScreenProjection {
        require(template.boardSet.kind == ScreenKind.Typing) { "Typing projection requires a Typing Screen template" }
        val templateBoard = requireNotNull(template.rootBoard) { "Typing Screen template has no root Page" }
        val pages = buildList {
            add(TypingScreenPage(TYPING_ALL_PAGE_ID, "All Phrases"))
            categories.forEach { category ->
                add(TypingScreenPage(typingCategoryPageId(category.id), category.name.orEmpty().ifBlank { "Category" }, category.id))
            }
            if (includeHistory && history.any { it.visibleInHistory }) {
                add(TypingScreenPage(TYPING_HISTORY_PAGE_ID, "History", isHistory = true))
            }
        }
        val projectedBoards = pages.map { page ->
            if (page.isHistory) {
                projectHistoryPage(
                    templateBoard,
                    page,
                    history.filter { it.visibleInHistory }.sortedByDescending { it.date ?: it.createdAt ?: 0L },
                    columns,
                )
            } else {
                val visible = phrases.filter { page.categoryId == null || it.parentId == page.categoryId }
                projectPhrasePage(templateBoard, page, visible, columns)
            }
        }
        val projectedSet = template.boardSet.copy(
            rootBoardId = TYPING_ALL_PAGE_ID,
            boardIds = projectedBoards.map { it.id },
        )
        return TypingScreenProjection(BoardSetGraph(projectedSet, projectedBoards), pages)
    }

    fun projectPhrasePage(
        template: ObfBoard,
        page: TypingScreenPage,
        phrases: List<Phrase>,
        columns: Int,
    ): ObfBoard {
        val safeColumns = columns.coerceIn(1, 12)
        val phraseButtons = phrases.map(::phraseButton)
        val rows = ceil(phraseButtons.size / safeColumns.toDouble()).toInt().coerceAtLeast(1)
        return template.copy(
            id = page.id,
            name = page.name,
            buttons = template.buttons + phraseButtons,
            images = template.images + phrases.mapNotNull(::phraseImage),
            sounds = template.sounds + phrases.mapNotNull(::phraseSound),
            grid = ObfGrid(
                rows,
                safeColumns,
                List(rows) { row -> List(safeColumns) { column -> phraseButtons.getOrNull(row * safeColumns + column)?.id } },
            ),
        )
    }

    /** Projects read-only History entries, in the given order, as a Page of Buttons. */
    fun projectHistoryPage(
        template: ObfBoard,
        page: TypingScreenPage,
        items: List<SaidText>,
        columns: Int,
    ): ObfBoard {
        val safeColumns = columns.coerceIn(1, 12)
        val buttons = items.mapIndexed { index, item -> historyButton(item, index) }
        val rows = ceil(buttons.size / safeColumns.toDouble()).toInt().coerceAtLeast(1)
        return template.copy(
            id = page.id,
            name = page.name,
            buttons = template.buttons + buttons,
            sounds = template.sounds + items.mapIndexedNotNull(::historySound),
            grid = ObfGrid(
                rows,
                safeColumns,
                List(rows) { row -> List(safeColumns) { column -> buttons.getOrNull(row * safeColumns + column)?.id } },
            ),
        )
    }

    private fun actionButton(id: String, label: String, action: String) = ObfButton(
        id = id,
        label = label,
        action = action,
    )

    private fun phraseButton(phrase: Phrase): ObfButton = ObfButton(
        id = "typing:phrase:${phrase.id}",
        label = phrase.text,
        vocalization = phrase.name?.ifBlank { null } ?: phrase.text,
        imageId = phrase.imageUrl?.takeIf(String::isNotBlank)?.let { "typing:image:${phrase.id}" },
        soundId = phrase.recordingPath?.takeIf(String::isNotBlank)?.let { "typing:sound:${phrase.id}" },
        backgroundColor = phrase.backgroundColor,
        hidden = phrase.isHidden,
        extensions = mapOf(OBF_PHRASE_ID_EXTENSION to JsonPrimitive(phrase.id)),
    )

    private fun phraseImage(phrase: Phrase): ObfImage? = phrase.imageUrl?.takeIf(String::isNotBlank)?.let { source ->
        ObfImage(id = "typing:image:${phrase.id}", url = source)
    }

    private fun phraseSound(phrase: Phrase): ObfSound? = phrase.recordingPath?.takeIf(String::isNotBlank)?.let { path ->
        ObfSound(id = "typing:sound:${phrase.id}", path = path)
    }

    private fun historyButton(item: SaidText, index: Int): ObfButton {
        val stableId = historyStableId(item, index)
        return ObfButton(
            id = "typing:history-item:$stableId",
            label = item.saidText.orEmpty(),
            vocalization = item.saidText.orEmpty(),
            soundId = item.audioFilePath?.takeIf(String::isNotBlank)?.let { "typing:history-sound:$stableId" },
            extensions = mapOf(OBF_HISTORY_ID_EXTENSION to JsonPrimitive(stableId)),
        )
    }

    private fun historySound(index: Int, item: SaidText): ObfSound? =
        item.audioFilePath?.takeIf(String::isNotBlank)?.let { path ->
            ObfSound(id = "typing:history-sound:${historyStableId(item, index)}", path = path)
        }

    private fun historyStableId(item: SaidText, index: Int): String =
        item.id?.toString() ?: (item.date ?: item.createdAt ?: index.toLong()).toString()
}
