package io.github.jdreioe.wingmate.domain.chatterbox

interface ModelDownloader {
    suspend fun downloadModel(modelId: String, onProgress: (Float) -> Unit = {}): Result<Unit>
    fun installationStatus(modelId: String): ModelInstallationStatus
    suspend fun deleteModel(modelId: String): Result<Unit>
    fun hasLegacyDownload(): Boolean = false
    suspend fun deleteLegacyDownload(): Result<Unit> = Result.success(Unit)
}
