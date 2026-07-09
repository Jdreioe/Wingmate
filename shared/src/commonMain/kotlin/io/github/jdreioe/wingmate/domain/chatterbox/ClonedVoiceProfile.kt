package io.github.jdreioe.wingmate.domain.chatterbox

import kotlinx.serialization.Serializable

@Serializable
data class ClonedVoiceProfile(
    val id: String,
    val name: String,
    val modelId: String,
    val createdAt: Long,
    val sourceRecordingPath: String? = null,
    val sourceDurationMs: Long? = null,
    val profilePath: String,
    val previewPath: String? = null,
    val metadataPath: String,
)