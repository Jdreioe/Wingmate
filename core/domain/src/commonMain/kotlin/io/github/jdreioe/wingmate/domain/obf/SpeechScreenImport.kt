package io.github.jdreioe.wingmate.domain.obf

import io.github.jdreioe.wingmate.domain.SpeechPolicy
import kotlinx.serialization.Serializable

@Serializable
data class SpeechImportPage(val name: String, val paragraphs: List<String>)

/** Local paragraph parsing shared by the import preview and generated Screen; wording stays intact. */
object SpeechScreenImport {
    const val DEFAULT_PARAGRAPHS_PER_PAGE = 4
    const val MAX_PARAGRAPHS_PER_PAGE = 8
    private val headingPattern = Regex("^#{1,6}[ \t]+(.+?)(?:[ \t]+#+)?[ \t]*$")

    fun preview(name: String, text: String, paragraphsPerPage: Int): List<SpeechImportPage> {
        require(paragraphsPerPage in 1..MAX_PARAGRAPHS_PER_PAGE) { "Choose between 1 and 8 paragraphs per Page." }
        val pages = mutableListOf<SpeechImportPage>()
        var sectionName = name.trim()
        val paragraphs = mutableListOf<String>()
        val lines = mutableListOf<String>()
        fun flushParagraph() {
            val paragraph = lines.joinToString("\n").trim()
            if (paragraph.isNotEmpty()) paragraphs += paragraph
            lines.clear()
        }
        fun flushSection() {
            flushParagraph()
            val chunks = paragraphs.chunked(paragraphsPerPage)
            chunks.forEachIndexed { index, chunk ->
                val pageName = if (chunks.size > 1) "$sectionName · ${index + 1}" else sectionName
                pages += SpeechImportPage(pageName, chunk)
            }
            paragraphs.clear()
        }
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
            val heading = headingPattern.matchEntire(line.trim())
            when {
                heading != null -> {
                    flushSection()
                    sectionName = heading.groupValues[1].trim()
                }
                line.isBlank() -> flushParagraph()
                else -> lines += line
            }
        }
        flushSection()
        return pages
    }

    /** Spoken text is never shortened. Labels use the opening words as a visual cue. */
    fun buttonLabel(paragraph: String): String {
        val opening = paragraph.replace(Regex("\\s+"), " ")
        if (opening.length <= 72) return opening
        val prefix = opening.take(72)
        return prefix.substringBeforeLast(' ', prefix).trimEnd() + "…"
    }

    fun createGraph(
        id: String,
        name: String,
        text: String,
        paragraphsPerPage: Int,
        previousLabel: String,
        nextLabel: String,
        now: Long,
    ): BoardSetGraph {
        require(name.isNotBlank()) { "Give the speech a name." }
        val pages = preview(name, text, paragraphsPerPage)
        require(pages.isNotEmpty()) { "Add at least one paragraph." }
        val pageIds = pages.indices.map { "$id-page-$it" }
        val boards = pages.mapIndexed { index, page ->
            val buttons = page.paragraphs.mapIndexed { paragraphIndex, paragraph ->
                ObfButton(
                    id = "$id-p$index-paragraph-$paragraphIndex",
                    label = buttonLabel(paragraph),
                    vocalization = paragraph,
                )
            }
            val previous = if (index > 0) ObfButton(
                id = "$id-p$index-previous", label = previousLabel,
                loadBoard = ObfLoadBoard(id = pageIds[index - 1]),
            ) else null
            val next = if (index < pages.lastIndex) ObfButton(
                id = "$id-p$index-next", label = nextLabel,
                loadBoard = ObfLoadBoard(id = pageIds[index + 1]),
            ) else null
            ObfBoard(
                format = "open-board-0.1",
                id = pageIds[index],
                name = page.name,
                buttons = buttons + listOfNotNull(previous, next),
                grid = ObfGrid(
                    rows = paragraphsPerPage + 1,
                    columns = 2,
                    order = List(paragraphsPerPage) { row -> List(2) { buttons.getOrNull(row)?.id } } +
                        listOf(listOf(previous?.id, next?.id)),
                ),
            )
        }
        return BoardSetGraph(
            ObfBoardSet(
                id = id, name = name.trim(), rootBoardId = pageIds.first(), boardIds = pageIds,
                createdAt = now, updatedAt = now,
                screenSettings = BoardSettingsOverrides(
                    showLabels = true, showSymbols = false, showMessageBar = false,
                    activationBehavior = BoardActivationBehavior.SpeakOnly,
                    returnBehavior = BoardReturnBehavior.Stay,
                    speechPolicy = SpeechPolicy.Immediate,
                ),
            ),
            boards,
        ).also { it.requireValid() }
    }
}
