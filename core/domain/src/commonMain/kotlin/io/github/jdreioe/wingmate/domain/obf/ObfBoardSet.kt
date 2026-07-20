package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.Serializable

@Serializable
data class ObfBoardSet(
    val id: String,
    val name: String,
    val rootBoardId: String,
    val boardIds: List<String> = emptyList(),
    val isLocked: Boolean = false,
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
}
