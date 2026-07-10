package io.github.jdreioe.wingmate.domain.chatterbox

import kotlinx.serialization.Serializable

@Serializable
data class ChatterboxModel(
    val id: String,
    val name: String,
    val version: String,
    val sizeBytes: Long,
    val storagePath: String? = null,
    val source: ModelSource,
    val modelFileUrl: String? = null,
    val languages: List<String> = emptyList(),
    val isInstalled: Boolean = false,
    val requiresGpu: Boolean = false,
)

@Serializable
enum class ModelSource {
    Official,
    Community
}