package io.github.jdreioe.wingmate.domain.chatterbox

data class ModelArtifact(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
)

sealed interface ModelInstallationStatus {
    data object NotInstalled : ModelInstallationStatus
    data class Partial(val downloadedBytes: Long, val totalBytes: Long) : ModelInstallationStatus
    data class Installed(val storagePath: String) : ModelInstallationStatus
    data class Invalid(val reason: String) : ModelInstallationStatus
}
