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
