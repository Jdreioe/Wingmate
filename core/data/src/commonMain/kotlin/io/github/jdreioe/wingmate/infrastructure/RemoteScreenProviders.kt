package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.CloudDriveAccessTokens
import io.github.jdreioe.wingmate.domain.obf.CloudDriveFiles
import io.github.jdreioe.wingmate.domain.obf.CloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteFileContent
import io.github.jdreioe.wingmate.domain.obf.RemoteFileMetadata
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenLink
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.github.jdreioe.wingmate.domain.obf.RemoteWriteResult
import io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.UnsupportedLinkedRemoteFileStorage
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import okio.Buffer
import okio.ByteString.Companion.toByteString

/**
 * Dispatches remote Screen operations to the matching Bring-Your-Own-Storage backend.
 */
class CompositeRemoteScreenStorage(
    httpClient: HttpClient,
    linkedFileStorage: LinkedRemoteFileStorage = UnsupportedLinkedRemoteFileStorage,
    cloudShareStorage: CloudShareScreenStorage = UnsupportedCloudShareScreenStorage,
    cloudDriveTokens: CloudDriveAccessTokens = CloudDriveTokenManager(httpClient),
    maxDownloadBytes: Long = DEFAULT_MAX_REMOTE_OBZ_BYTES,
    googleDriveApiBaseUrl: String = GoogleDriveRemoteScreenProvider.DEFAULT_API_BASE_URL,
    googleDriveUploadBaseUrl: String = GoogleDriveRemoteScreenProvider.DEFAULT_UPLOAD_BASE_URL,
    graphBaseUrl: String = OneDriveRemoteScreenProvider.DEFAULT_GRAPH_BASE_URL,
) : RemoteScreenStorage {
    private val linkedFile = LinkedFileRemoteScreenProvider(linkedFileStorage, maxDownloadBytes)
    private val cloudShare = CloudShareRemoteScreenProvider(cloudShareStorage, maxDownloadBytes)
    private val googleDrive = GoogleDriveRemoteScreenProvider(
        httpClient, cloudDriveTokens, maxDownloadBytes, googleDriveApiBaseUrl, googleDriveUploadBaseUrl,
    )
    private val oneDrive = OneDriveRemoteScreenProvider(httpClient, cloudDriveTokens, maxDownloadBytes, graphBaseUrl)

    /** Listing and creating Screen files in Google Drive and OneDrive before a Screen is linked. */
    val cloudDriveFiles: CloudDriveFiles = CloudDriveFileOperations(googleDrive, oneDrive)

    override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata =
        providerFor(link.kind).head(link, secret)

    override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent =
        providerFor(link.kind).download(link, secret)

    override suspend fun upload(
        link: RemoteScreenLink,
        secret: String?,
        bytes: ByteArray,
        expectedRevision: String?,
        commitMessage: String,
    ): RemoteWriteResult = providerFor(link.kind).upload(link, secret, bytes, expectedRevision, commitMessage)

    override suspend fun delete(link: RemoteScreenLink, secret: String?) = providerFor(link.kind).delete(link, secret)

    private fun providerFor(kind: RemoteStorageKind): RemoteScreenStorage = when (kind) {
        RemoteStorageKind.LINKED_FILE -> linkedFile
        RemoteStorageKind.ICLOUD_SHARE -> cloudShare
        RemoteStorageKind.GOOGLE_DRIVE -> googleDrive
        RemoteStorageKind.ONEDRIVE -> oneDrive
    }

    companion object {
        const val DEFAULT_MAX_REMOTE_OBZ_BYTES = 50L * 1024L * 1024L
    }
}

/**
 * Draw.io-style OS-managed / cloud-synced file provider (iCloud Drive, Google Drive, OneDrive,
 * Dropbox, Syncthing, or local folder via platform persistent file handles).
 */
class LinkedFileRemoteScreenProvider(
    private val fileStorage: LinkedRemoteFileStorage,
    private val maxDownloadBytes: Long = CompositeRemoteScreenStorage.DEFAULT_MAX_REMOTE_OBZ_BYTES,
) : RemoteScreenStorage {

    override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata {
        ensureSupported()
        val bytes = try {
            fileStorage.readBytes(link.location)
        } catch (error: RemoteStorageException) {
            if (error.code == RemoteStorageErrorCode.NOT_FOUND) {
                return RemoteFileMetadata(revision = null, exists = false)
            }
            throw error
        }
        // Treat freshly created 0-byte SAF/file-exporter placeholders as non-existent so initial push succeeds.
        if (bytes.isEmpty()) return RemoteFileMetadata(revision = null, exists = false)
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        return RemoteFileMetadata(
            revision = "sha256:${sha256Hex(bytes)}",
            exists = true,
        )
    }

    override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent {
        ensureSupported()
        val bytes = fileStorage.readBytes(link.location)
        if (bytes.isEmpty()) {
            throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file is empty")
        }
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        val hash = sha256Hex(bytes)
        return RemoteFileContent(
            bytes = bytes,
            revision = "sha256:$hash",
            contentHash = hash,
        )
    }

    override suspend fun upload(
        link: RemoteScreenLink,
        secret: String?,
        bytes: ByteArray,
        expectedRevision: String?,
        commitMessage: String,
    ): RemoteWriteResult {
        ensureSupported()
        if (link.readOnly) {
            throw RemoteStorageException(RemoteStorageErrorCode.READ_ONLY, "This linked file is read-only")
        }
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        val current = runCatching { fileStorage.readBytes(link.location) }.getOrNull()
        if (expectedRevision == null) {
            if (current != null && current.isNotEmpty()) {
                throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "Linked file already has content")
            }
        } else if (current != null && current.isNotEmpty()) {
            val currentRevision = "sha256:${sha256Hex(current)}"
            if (currentRevision != expectedRevision) {
                throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "Linked file changed before saving")
            }
        }
        fileStorage.writeBytes(link.location, bytes)
        val hash = sha256Hex(bytes)
        return RemoteWriteResult(
            revision = "sha256:$hash",
            contentHash = hash,
        )
    }

    private fun ensureSupported() {
        if (!fileStorage.isSupported) {
            throw RemoteStorageException(
                RemoteStorageErrorCode.UNSUPPORTED_PLATFORM,
                "Linked files are not available on this platform",
            )
        }
    }
}

/**
 * Apple CloudKit share provider (iOS → iOS). Revisions are CloudKit record change tags, so
 * concurrent edits from the owner and participants surface as conflicts instead of overwrites.
 */
class CloudShareRemoteScreenProvider(
    private val cloudStorage: CloudShareScreenStorage,
    private val maxDownloadBytes: Long = CompositeRemoteScreenStorage.DEFAULT_MAX_REMOTE_OBZ_BYTES,
) : RemoteScreenStorage {

    override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata {
        ensureSupported()
        val record = cloudStorage.fetch(validLocation(link), includePackage = false)
        val revision = record?.revision ?: return RemoteFileMetadata(revision = null, exists = false)
        return RemoteFileMetadata(revision = revision, exists = true)
    }

    override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent {
        ensureSupported()
        val record = cloudStorage.fetch(validLocation(link), includePackage = true)
            ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "The shared Screen is no longer available in iCloud")
        val bytes = record.bytes?.takeIf { it.isNotEmpty() }
            ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "The shared Screen has not been uploaded yet")
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        val hash = sha256Hex(bytes)
        return RemoteFileContent(bytes = bytes, revision = record.revision ?: "sha256:$hash", contentHash = hash)
    }

    override suspend fun upload(
        link: RemoteScreenLink,
        secret: String?,
        bytes: ByteArray,
        expectedRevision: String?,
        commitMessage: String,
    ): RemoteWriteResult {
        ensureSupported()
        if (link.readOnly) {
            throw RemoteStorageException(RemoteStorageErrorCode.READ_ONLY, "You can view this shared Screen but not change it")
        }
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        val revision = cloudStorage.save(validLocation(link), bytes, expectedRevision)
        return RemoteWriteResult(revision = revision, contentHash = sha256Hex(bytes))
    }

    private fun validLocation(link: RemoteScreenLink): String {
        val location = link.location.trim()
        if (!CLOUD_LOCATION.matches(location)) {
            throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "The iCloud share address is not valid")
        }
        return location
    }

    private fun ensureSupported() {
        if (!cloudStorage.isSupported) {
            throw RemoteStorageException(
                RemoteStorageErrorCode.UNSUPPORTED_PLATFORM,
                "iCloud shared Screens are only available on iPhone and iPad",
            )
        }
    }

    private companion object {
        val CLOUD_LOCATION = Regex("^icloud://(private/[^/]+/[^/]+|shared/[^/]+/[^/]+/[^/]+)$")
    }
}

internal fun validateRemoteHttpUrl(rawUrl: String, allowQueryParams: Boolean = false): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
        throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Remote URL is required")
    }
    val parsed = runCatching { Url(trimmed) }.getOrElse {
        throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Remote URL is not valid", it)
    }
    val scheme = parsed.protocol.name.lowercase()
    val host = parsed.host.lowercase()
    if (scheme != "https") {
        throw RemoteStorageException(
            RemoteStorageErrorCode.INSECURE_URL,
            "Remote Screen URLs must use HTTPS",
        )
    }
    val disallowedQuery = !allowQueryParams && parsed.parameters.names().isNotEmpty()
    if (parsed.user != null || parsed.password != null || disallowedQuery || parsed.fragment.isNotEmpty()) {
        throw RemoteStorageException(
            RemoteStorageErrorCode.INVALID_CONFIGURATION,
            "Remote URL must be a direct HTTPS URL without embedded credentials or fragments",
        )
    }
    if (host.isBlank()) {
        throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Remote URL must include a host")
    }
    return trimmed
}

internal fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

internal fun checkSize(size: Long, maxBytes: Long) {
    if (size > maxBytes) {
        throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Remote file exceeds maximum size limit")
    }
}

internal suspend fun readBoundedBytes(response: HttpResponse, maxBytes: Long): ByteArray {
    response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { checkSize(it, maxBytes) }
    val channel = response.bodyAsChannel()
    val buffer = Buffer()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val count = channel.readAvailable(chunk)
        if (count == -1) return buffer.readByteArray()
        checkSize(buffer.size + count, maxBytes)
        buffer.write(chunk, 0, count)
    }
}

internal suspend inline fun <T> executeNetwork(block: () -> T): T = try {
    block()
} catch (error: RemoteStorageException) {
    throw error
} catch (error: kotlinx.coroutines.CancellationException) {
    throw error
} catch (error: Exception) {
    throw RemoteStorageException(
        RemoteStorageErrorCode.NETWORK_UNAVAILABLE,
        "Could not reach remote storage",
        error,
    )
}
