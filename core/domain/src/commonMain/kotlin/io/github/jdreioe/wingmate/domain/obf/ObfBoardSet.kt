package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.Serializable

@Serializable
data class ObfBoardSet(
    val id: String,
    val name: String,
    val rootBoardId: String,
    val boardIds: List<String> = emptyList(),
    val isLocked: Boolean = false,
    /** Cache synthesized audio for complete sentences assembled in this board set. */
    val cacheWholeSentences: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A complete board set and all boards it owns.
 *
 * Keeping the graph together prevents the editor and communicator from loading
 * different subsets of the same board set.
 */
data class BoardSetGraph(
    val boardSet: ObfBoardSet,
    val boards: List<ObfBoard>
) {
    val boardsById: Map<String, ObfBoard>
        get() = boards.associateBy { it.id }

    val rootBoard: ObfBoard?
        get() = boardsById[boardSet.rootBoardId]

    /** Resolve an OBF page link regardless of whether it uses an ID, path, or name. */
    fun resolveLinkedBoard(link: ObfLoadBoard?): ObfBoard? {
        if (link == null) return null

        link.id?.let { id -> boardsById[id]?.let { return it } }

        val pathCandidates = listOfNotNull(link.path, link.url)
            .flatMap { value ->
                val normalized = value.substringBefore('?').substringBefore('#').trimEnd('/')
                listOf(normalized, normalized.substringAfterLast('/').removeSuffix(".obf"))
            }
            .filter(String::isNotBlank)
            .toSet()
        boards.firstOrNull { board ->
            board.id in pathCandidates || board.url?.substringBefore('?') in pathCandidates
        }?.let { return it }

        val requestedName = link.name?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return boards.singleOrNull { it.name?.trim()?.equals(requestedName, ignoreCase = true) == true }
    }

    /** Store resolvable links with the canonical local page ID and display name. */
    fun canonicalizeBoardLinks(): BoardSetGraph = copy(
        boards = boards.map { board ->
            board.copy(
                buttons = board.buttons.map buttonMap@{ button ->
                    val link = button.loadBoard ?: return@buttonMap button
                    val target = resolveLinkedBoard(link) ?: return@buttonMap button
                    button.copy(loadBoard = link.copy(id = target.id, name = target.name ?: link.name))
                }
            )
        }
    )
}
