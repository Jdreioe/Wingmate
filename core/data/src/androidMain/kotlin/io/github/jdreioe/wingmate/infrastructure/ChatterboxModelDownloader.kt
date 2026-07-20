package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxError
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxRuntimeStatus
import io.github.jdreioe.wingmate.domain.chatterbox.ModelArtifact
import io.github.jdreioe.wingmate.domain.chatterbox.ModelDownloader
import io.github.jdreioe.wingmate.domain.chatterbox.ModelInstallationStatus
import io.github.jdreioe.wingmate.infrastructure.chatterbox.OfficialModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class ChatterboxModelDownloader(
    private val context: Context,
    private val runtimeState: AndroidChatterboxRuntimeState? = null,
) : ModelDownloader {
    companion object {
        const val DEFAULT_MODEL_ID = OfficialModelRegistry.Q4_MODEL_ID
        val MODELS: List<ModelArtifact> = OfficialModelRegistry.q4Artifacts
        const val TOTAL_SIZE_BYTES = OfficialModelRegistry.Q4_TOTAL_SIZE_BYTES
        private const val SAFETY_MARGIN_BYTES = 100L * 1024L * 1024L
        private const val VERIFIED_MARKER = ".verified"

        fun getModelsRoot(context: Context): File = File(context.filesDir, "chatterbox/models")

        fun getModelDir(context: Context, modelId: String = DEFAULT_MODEL_ID): File =
            File(getModelsRoot(context), "$modelId/${OfficialModelRegistry.REVISION}")

        fun getLegacyModelDir(context: Context): File = File(context.filesDir, "chatterbox_models")
    }

    private val maxRetries = 3

    override fun installationStatus(modelId: String): ModelInstallationStatus {
        val artifacts = OfficialModelRegistry.getModelArtifacts(modelId)
        if (artifacts.isEmpty()) return ModelInstallationStatus.Invalid("Unsupported model '$modelId'")
        val modelDir = getModelDir(context, modelId)
        val marker = File(modelDir, VERIFIED_MARKER)
        if (marker.readTextOrNull()?.trim() == OfficialModelRegistry.REVISION &&
            artifacts.all { File(modelDir, it.relativePath).length() == it.sizeBytes }
        ) {
            return ModelInstallationStatus.Installed(modelDir.absolutePath)
        }

        val downloaded = artifacts.sumOf { artifact ->
            val complete = File(modelDir, artifact.relativePath)
            val partial = File(modelDir, "${artifact.relativePath}.part")
            when {
                complete.exists() -> complete.length().coerceAtMost(artifact.sizeBytes)
                partial.exists() -> partial.length().coerceAtMost(artifact.sizeBytes)
                else -> 0L
            }
        }
        return if (downloaded == 0L) ModelInstallationStatus.NotInstalled
        else ModelInstallationStatus.Partial(downloaded, artifacts.sumOf { it.sizeBytes })
    }

    override suspend fun downloadModel(
        modelId: String,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val artifacts = OfficialModelRegistry.getModelArtifacts(modelId)
            if (artifacts.isEmpty()) throw ChatterboxError.ModelNotFound(modelId)
            val modelDir = getModelDir(context, modelId)
            modelDir.mkdirs()
            File(modelDir, VERIFIED_MARKER).delete()
            runtimeState?.update(ChatterboxRuntimeStatus.Verifying)

            var verifiedBytes = 0L
            val verifiedArtifacts = mutableSetOf<String>()
            for (artifact in artifacts) {
                coroutineContext.ensureActive()
                val destination = File(modelDir, artifact.relativePath)
                if (destination.length() == artifact.sizeBytes && sha256(destination) == artifact.sha256) {
                    verifiedBytes += artifact.sizeBytes
                    verifiedArtifacts += artifact.relativePath
                } else if (destination.exists()) {
                    destination.delete()
                }
            }

            val partialBytes = artifacts.sumOf { artifact ->
                File(modelDir, "${artifact.relativePath}.part").length().coerceAtMost(artifact.sizeBytes)
            }
            val required = (artifacts.sumOf { it.sizeBytes } - verifiedBytes - partialBytes).coerceAtLeast(0L)
            val available = context.filesDir.usableSpace
            if (available < required + SAFETY_MARGIN_BYTES) {
                throw ChatterboxError.StorageLow(required + SAFETY_MARGIN_BYTES, available)
            }

            val totalBytes = artifacts.sumOf { it.sizeBytes }
            var completedBytes = verifiedBytes
            runtimeState?.update(ChatterboxRuntimeStatus.Downloading((completedBytes + partialBytes).toFloat() / totalBytes))
            onProgress((completedBytes + partialBytes).toFloat() / totalBytes)

            for (artifact in artifacts) {
                coroutineContext.ensureActive()
                val destination = File(modelDir, artifact.relativePath)
                if (artifact.relativePath in verifiedArtifacts) continue
                destination.parentFile?.mkdirs()
                downloadArtifact(artifact, destination, completedBytes, totalBytes, onProgress)
                runtimeState?.update(ChatterboxRuntimeStatus.Verifying)
                if (destination.length() != artifact.sizeBytes || sha256(destination) != artifact.sha256) {
                    destination.delete()
                    throw ChatterboxError.DownloadIntegrity(artifact.relativePath)
                }
                completedBytes += artifact.sizeBytes
                onProgress(completedBytes.toFloat() / totalBytes)
            }

            File(modelDir, VERIFIED_MARKER).writeText(OfficialModelRegistry.REVISION)
            runtimeState?.update(ChatterboxRuntimeStatus.Ready(modelId, null))
            Unit
        }.onFailure { runtimeState?.update(ChatterboxRuntimeStatus.Error(it.message ?: "Model download failed", false)) }
    }

    private suspend fun downloadArtifact(
        artifact: ModelArtifact,
        destination: File,
        completedBytes: Long,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ) {
        val partial = File("${destination.absolutePath}.part")
        if (partial.length() > artifact.sizeBytes) partial.delete()
        if (partial.length() == artifact.sizeBytes) {
            if (sha256(partial) == artifact.sha256 && partial.renameTo(destination)) return
            partial.delete()
        }

        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            attempt++
            var connection: HttpURLConnection? = null
            try {
                var offset = partial.length()
                connection = URL(artifact.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.instanceFollowRedirects = true
                if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
                connection.connect()

                val response = connection.responseCode
                val validResume = response == HttpURLConnection.HTTP_PARTIAL &&
                    connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true
                if (offset > 0L && !validResume) {
                    partial.delete()
                    offset = 0L
                }
                if (response !in 200..299) {
                    throw IllegalStateException("HTTP $response for ${artifact.relativePath}")
                }

                var fileBytes = offset
                connection.inputStream.use { input ->
                    FileOutputStream(partial, validResume).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            fileBytes += read
                            val progress = (completedBytes + fileBytes).toFloat() / totalBytes
                            runtimeState?.update(ChatterboxRuntimeStatus.Downloading(progress.coerceIn(0f, 1f)))
                            onProgress(progress.coerceIn(0f, 1f))
                        }
                        output.fd.sync()
                    }
                }
                if (fileBytes != artifact.sizeBytes) {
                    throw IllegalStateException(
                        "Incomplete ${artifact.relativePath}: $fileBytes/${artifact.sizeBytes} bytes"
                    )
                }
                if (!partial.renameTo(destination)) {
                    throw IllegalStateException("Could not finalize ${artifact.relativePath}")
                }
                return
            } catch (error: Exception) {
                if (attempt >= maxRetries) throw error
                delay(attempt * 2_000L)
            } finally {
                connection?.disconnect()
            }
        }
    }

    override suspend fun deleteModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val modelRoot = File(getModelsRoot(context), modelId)
            if (modelRoot.exists() && !modelRoot.deleteRecursively()) {
                error("Could not delete Chatterbox model '$modelId'")
            }
            runtimeState?.update(ChatterboxRuntimeStatus.NotInstalled)
            Unit
        }
    }

    override fun hasLegacyDownload(): Boolean = getLegacyModelDir(context).exists()

    override suspend fun deleteLegacyDownload(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { getLegacyModelDir(context).deleteRecursively(); Unit }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { if (exists()) readText() else null }.getOrNull()
}
