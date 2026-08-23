package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class QuickCorePreset(val slug: String, val title: String, val url: String, val archiveBytes: Long) {
    Core24("quick-core-24", "Quick Core 24", "https://openboards.s3.amazonaws.com/examples/quick-core-24.obz", 9_459_924),
    Core40("quick-core-40", "Quick Core 40", "https://openboards.s3.amazonaws.com/examples/quick-core-40.obz", 35_437_986),
    Core60("quick-core-60", "Quick Core 60", "https://openboards.s3.amazonaws.com/examples/quick-core-60.obz", 34_898_633),
    Core84("quick-core-84", "Quick Core 84", "https://openboards.s3.amazonaws.com/examples/quick-core-84.obz", 70_257_878),
    Core112("quick-core-112", "Quick Core 112", "https://openboards.s3.amazonaws.com/examples/quick-core-112.obz", 70_393_954);

    companion object {
        fun fromSlug(value: String): QuickCorePreset? = entries.firstOrNull {
            it.slug.equals(value, ignoreCase = true)
        }
    }
}

data class QuickCoreDownloadProgress(
    val stage: String = "idle",
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
) {
    val fraction: Double?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0) }
}

/**
 * Downloads only allowlisted Quick Core archives and imports them through the shared OBZ
 * pipeline. Validated downloads are cached on device (like the Linux client) so repeat
 * imports skip the network transfer; cache writes that fail are ignored, not fatal.
 */
class QuickCorePresetService(
    private val client: HttpClient,
    private val importer: BoardImportService,
    private val cache: FileStorage? = null,
) {
    private val mutableProgress = MutableStateFlow(QuickCoreDownloadProgress())
    val progress: StateFlow<QuickCoreDownloadProgress> = mutableProgress.asStateFlow()

    suspend fun importPreset(slug: String): BoardImportResult {
        val preset = QuickCorePreset.fromSlug(slug) ?: return BoardImportResult.Failure(
            BoardImportErrorCode.FILE_UNREADABLE,
            "Unknown Quick Core preset",
        )
        return try {
            when (val source = archiveBytes(preset)) {
                is ArchiveSource.Failed -> source.result
                is ArchiveSource.Ready -> {
                    val downloaded = source.bytes.size.toLong()
                    val total = preset.archiveBytes
                    mutableProgress.value = QuickCoreDownloadProgress("importing", downloaded, total)
                    // Parsing, media persistence and board-list serialization are CPU/IO-heavy;
                    // keep them off the caller's (often main) dispatcher.
                    withContext(Dispatchers.Default) {
                        importer.importBoardSetFromBytesResult(source.bytes)
                    }.also { result ->
                        val stage = if (result is BoardImportResult.Success) "complete" else "failed"
                        mutableProgress.value = QuickCoreDownloadProgress(stage, downloaded, total)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mutableProgress.value = QuickCoreDownloadProgress("failed")
            BoardImportResult.Failure(
                BoardImportErrorCode.FILE_UNREADABLE,
                "Could not download the Quick Core preset",
            )
        }
    }

    /** Returns a cached archive, or downloads + caches a validated one. */
    private suspend fun archiveBytes(preset: QuickCorePreset): ArchiveSource {
        cache?.let { storage ->
            val cached = runCatching { storage.loadBytes(cacheKey(preset)) }.getOrNull()
            if (cached != null && cached.isNotEmpty() && cached.size.toLong() == preset.archiveBytes) {
                return ArchiveSource.Ready(cached)
            }
        }

        val response = client.get(preset.url)
        if (!response.status.isSuccess()) {
            return ArchiveSource.Failed(
                BoardImportResult.Failure(
                    BoardImportErrorCode.FILE_UNREADABLE,
                    "Quick Core download failed (${response.status.value})",
                )
            )
        }
        val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: preset.archiveBytes
        if (total !in 1..MAX_ARCHIVE_BYTES) {
            return ArchiveSource.Failed(
                BoardImportResult.Failure(BoardImportErrorCode.ARCHIVE_TOO_LARGE, "Quick Core archive is too large")
            )
        }
        mutableProgress.value = QuickCoreDownloadProgress("downloading", totalBytes = total)
        val chunks = mutableListOf<ByteArray>()
        var downloaded = 0L
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count == -1) break
            if (count == 0) continue
            downloaded += count
            if (downloaded > MAX_ARCHIVE_BYTES) {
                return ArchiveSource.Failed(
                    BoardImportResult.Failure(BoardImportErrorCode.ARCHIVE_TOO_LARGE, "Quick Core archive is too large")
                )
            }
            chunks += buffer.copyOf(count)
            mutableProgress.value = QuickCoreDownloadProgress("downloading", downloaded, total)
        }
        if (downloaded == 0L) {
            return ArchiveSource.Failed(
                BoardImportResult.Failure(BoardImportErrorCode.FILE_UNREADABLE, "Quick Core download was empty")
            )
        }
        val content = ByteArray(downloaded.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(content, offset)
            offset += chunk.size
        }
        // Persist the validated archive so repeat imports skip the download. A failed cache
        // write must never fail the import itself.
        cache?.let { storage -> runCatching { storage.saveBytes(cacheKey(preset), content) } }
        return ArchiveSource.Ready(content)
    }

    private fun cacheKey(preset: QuickCorePreset): String = "quick-core/${preset.slug}.obz"

    private sealed interface ArchiveSource {
        data class Ready(val bytes: ByteArray) : ArchiveSource
        data class Failed(val result: BoardImportResult) : ArchiveSource
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
    }
}
