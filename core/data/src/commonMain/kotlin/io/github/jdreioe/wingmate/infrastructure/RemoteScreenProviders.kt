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
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * Dispatches remote Screen operations to the matching Bring-Your-Own-Storage backend.
 */
class CompositeRemoteScreenStorage(
    httpClient: HttpClient,
    linkedFileStorage: LinkedRemoteFileStorage = UnsupportedLinkedRemoteFileStorage,
    cloudShareStorage: CloudShareScreenStorage = UnsupportedCloudShareScreenStorage,
    cloudDriveTokens: CloudDriveAccessTokens = CloudDriveTokenManager(httpClient),
    githubApiBaseUrl: String = GITHUB_DEFAULT_API_BASE_URL,
    maxDownloadBytes: Long = DEFAULT_MAX_REMOTE_OBZ_BYTES,
    googleDriveApiBaseUrl: String = GoogleDriveRemoteScreenProvider.DEFAULT_API_BASE_URL,
    googleDriveUploadBaseUrl: String = GoogleDriveRemoteScreenProvider.DEFAULT_UPLOAD_BASE_URL,
    graphBaseUrl: String = OneDriveRemoteScreenProvider.DEFAULT_GRAPH_BASE_URL,
) : RemoteScreenStorage {
    private val webDav = WebDavRemoteScreenProvider(httpClient, maxDownloadBytes)
    private val github = GitHubRemoteScreenProvider(httpClient, githubApiBaseUrl, maxDownloadBytes)
    private val httpsUrl = HttpsUrlRemoteScreenProvider(httpClient, maxDownloadBytes)
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

    private fun providerFor(kind: RemoteStorageKind): RemoteScreenStorage = when (kind) {
        RemoteStorageKind.WEBDAV -> webDav
        RemoteStorageKind.GITHUB -> github
        RemoteStorageKind.HTTPS_URL -> httpsUrl
        RemoteStorageKind.LINKED_FILE -> linkedFile
        RemoteStorageKind.ICLOUD_SHARE -> cloudShare
        RemoteStorageKind.GOOGLE_DRIVE -> googleDrive
        RemoteStorageKind.ONEDRIVE -> oneDrive
    }

    companion object {
        const val GITHUB_DEFAULT_API_BASE_URL = "https://api.github.com"
        const val DEFAULT_MAX_REMOTE_OBZ_BYTES = 50L * 1024L * 1024L
    }
}

/**
 * WebDAV storage provider (Nextcloud, OwnCloud, Hetzner Storage Box, Koofr, Fastmail, NAS).
 * Uses standard HTTP HEAD / GET / PUT with ETag & If-Match optimistic concurrency.
 */
class WebDavRemoteScreenProvider(
    private val httpClient: HttpClient,
    private val maxDownloadBytes: Long = CompositeRemoteScreenStorage.DEFAULT_MAX_REMOTE_OBZ_BYTES,
) : RemoteScreenStorage {

    override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata {
        val url = validateRemoteHttpUrl(link.location, allowQueryParams = false)
        val response = executeNetwork {
            httpClient.head(url) {
                applyWebDavAuth(link.username, secret)
            }
        }
        return when {
            response.status == HttpStatusCode.NotFound -> RemoteFileMetadata(revision = null, exists = false)
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "WebDAV authentication failed (${response.status.value})")
            response.status.isSuccess() -> RemoteFileMetadata(
                revision = normalizeEtag(response.headers[HttpHeaders.ETag]),
                exists = true,
            )
            else -> throw RemoteStorageException(
                RemoteStorageErrorCode.IO_ERROR,
                "WebDAV HEAD failed with status ${response.status.value}",
            )
        }
    }

    override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent {
        val url = validateRemoteHttpUrl(link.location, allowQueryParams = false)
        return executeNetwork {
            httpClient.prepareGet(url) {
                applyWebDavAuth(link.username, secret)
            }.execute { response ->
                when {
                    response.status == HttpStatusCode.NotFound ->
                        throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Remote file was not found")
                    response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                        throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "WebDAV authentication failed (${response.status.value})")
                    !response.status.isSuccess() ->
                        throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "WebDAV download failed (${response.status.value})")
                }
                val bytes = readBoundedBytes(response, maxDownloadBytes)
                val hash = sha256Hex(bytes)
                val revision = normalizeEtag(response.headers[HttpHeaders.ETag]) ?: "sha256:$hash"
                RemoteFileContent(bytes = bytes, revision = revision, contentHash = hash)
            }
        }
    }

    override suspend fun upload(
        link: RemoteScreenLink,
        secret: String?,
        bytes: ByteArray,
        expectedRevision: String?,
        commitMessage: String,
    ): RemoteWriteResult {
        if (link.readOnly) {
            throw RemoteStorageException(RemoteStorageErrorCode.READ_ONLY, "This remote link is read-only")
        }
        val url = validateRemoteHttpUrl(link.location, allowQueryParams = false)
        val useIfMatch = expectedRevision != null && isStrongEtag(expectedRevision)
        if (expectedRevision != null && !useIfMatch) {
            // RFC 9110 forbids weak ETags in If-Match, and some WebDAV servers omit ETags on PUT.
            // Verify the remote file hasn't changed before uploading.
            if (expectedRevision.startsWith("sha256:")) {
                val current = runCatching { download(link, secret) }.getOrNull()
                if (current != null && "sha256:${current.contentHash}" != expectedRevision) {
                    throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "Remote file changed before upload")
                }
            } else {
                val currentMeta = head(link, secret)
                if (currentMeta.exists && currentMeta.revision != null && currentMeta.revision != expectedRevision) {
                    throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "Remote file changed before upload")
                }
            }
        }
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        val hash = sha256Hex(bytes)
        val response = executeNetwork {
            httpClient.put(url) {
                applyWebDavAuth(link.username, secret)
                if (expectedRevision == null) {
                    header(HttpHeaders.IfNoneMatch, "*")
                } else if (useIfMatch) {
                    header(HttpHeaders.IfMatch, expectedRevision)
                }
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
        }
        when {
            response.status == HttpStatusCode.PreconditionFailed || response.status == HttpStatusCode.Conflict ->
                throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "Remote file changed before upload")
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "WebDAV authentication failed (${response.status.value})")
            !response.status.isSuccess() ->
                throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "WebDAV upload failed (${response.status.value})")
        }
        val revision = normalizeEtag(response.headers[HttpHeaders.ETag])
            ?: runCatching { head(link, secret).revision }.getOrNull()
            ?: "sha256:$hash"
        return RemoteWriteResult(revision = revision, contentHash = hash)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyWebDavAuth(username: String?, secret: String?) {
        val cleanSecret = secret?.takeIf { it.isNotEmpty() } ?: return
        val cleanUser = username?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanUser != null) {
            val encoded = "$cleanUser:$cleanSecret".encodeUtf8().base64()
            header(HttpHeaders.Authorization, "Basic $encoded")
        } else {
            header(HttpHeaders.Authorization, "Bearer $cleanSecret")
        }
    }
}

/**
 * GitHub repository Contents API storage provider.
 * Uses user-supplied fine-grained Personal Access Tokens (PAT) with blob SHA optimistic locking.
 */
class GitHubRemoteScreenProvider(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = CompositeRemoteScreenStorage.GITHUB_DEFAULT_API_BASE_URL,
    private val maxDownloadBytes: Long = CompositeRemoteScreenStorage.DEFAULT_MAX_REMOTE_OBZ_BYTES,
) : RemoteScreenStorage {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata {
        val payload = getContent(parseTarget(link), secret)
        return RemoteFileMetadata(revision = payload?.sha, exists = payload != null)
    }

    private suspend fun getContent(target: GitHubTarget, secret: String?): GitHubContentResponse? = executeNetwork {
        httpClient.prepareGet(target.contentsUrl(apiBaseUrl)) {
            applyGitHubHeaders(secret, accept = "application/vnd.github.object+json")
            parameter("ref", target.branch)
        }.execute { response ->
            when (response.status) {
                HttpStatusCode.NotFound -> null
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                    throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "GitHub authentication failed (${response.status.value})")
                HttpStatusCode.OK -> {
                    val bytes = readBoundedBytes(response, 2L * 1024 * 1024)
                    val payload = json.decodeFromString<GitHubContentResponse>(bytes.decodeToString())
                    if (payload.type != "file" || !isGitSha(payload.sha)) {
                        throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "The GitHub path must point to a file")
                    }
                    payload
                }
                else -> throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "GitHub request failed (${response.status.value})")
            }
        }
    }

    override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent {
        val target = parseTarget(link)
        val payload = getContent(target, secret)
            ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "GitHub file was not found")
        payload.size?.let { checkSize(it, maxDownloadBytes) }
        val bytes = if (!payload.content.isNullOrBlank() && payload.encoding.equals("base64", ignoreCase = true)) {
            val cleaned = payload.content.replace("\n", "").replace("\r", "").trim()
            cleaned.decodeBase64()?.toByteArray()
                ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "GitHub response had invalid base64 content")
        } else {
            // Files larger than 1 MB omit inline content; fetch raw bytes from the same API endpoint.
            executeNetwork {
                httpClient.prepareGet(target.contentsUrl(apiBaseUrl)) {
                    applyGitHubHeaders(secret, accept = "application/vnd.github.raw+json")
                    parameter("ref", target.branch)
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "GitHub raw download failed (${response.status.value})")
                    }
                    readBoundedBytes(response, maxDownloadBytes)
                }
            }
        }
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        // The branch can move between metadata and raw requests.
        val blobSha = Buffer().writeUtf8("blob ${bytes.size}\u0000").write(bytes).sha1().hex()
        if (!blobSha.equals(payload.sha, ignoreCase = true)) {
            throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "GitHub file changed while downloading. Try again")
        }
        val hash = sha256Hex(bytes)
        return RemoteFileContent(
            bytes = bytes,
            revision = payload.sha,
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
        if (link.readOnly) {
            throw RemoteStorageException(RemoteStorageErrorCode.READ_ONLY, "This GitHub link is read-only")
        }
        val token = secret?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "A GitHub Personal Access Token is required to save changes")
        val target = parseTarget(link)
        if (expectedRevision != null && !isGitSha(expectedRevision)) {
            throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "GitHub did not provide the current file revision")
        }
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        val requestBody = GitHubPutRequest(
            message = commitMessage.ifBlank { "Update Wingmate Screen" },
            content = bytes.toByteString().base64(),
            branch = target.branch,
            sha = expectedRevision?.takeIf { isGitSha(it) },
        )
        val response = executeNetwork {
            httpClient.put(target.contentsUrl(apiBaseUrl)) {
                applyGitHubHeaders(token, accept = "application/vnd.github+json")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(GitHubPutRequest.serializer(), requestBody))
            }
        }
        when {
            response.status == HttpStatusCode.Conflict ->
                throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "GitHub file changed before upload")
            response.status == HttpStatusCode.UnprocessableEntity -> {
                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                if (body.contains("sha", ignoreCase = true) || body.contains("exists", ignoreCase = true)) {
                    throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "GitHub file already exists or has a newer revision")
                }
                throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "GitHub rejected upload (422)")
            }
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "GitHub authentication failed (${response.status.value})")
            !response.status.isSuccess() ->
                throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "GitHub upload failed (${response.status.value})")
        }
        val putResponse = json.decodeFromString(GitHubPutResponse.serializer(), response.bodyAsText())
        val hash = sha256Hex(bytes)
        return RemoteWriteResult(
            revision = putResponse.content?.sha?.takeIf(::isGitSha),
            contentHash = hash,
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyGitHubHeaders(secret: String?, accept: String) {
        header(HttpHeaders.Accept, accept)
        header("X-GitHub-Api-Version", "2022-11-28")
        header(HttpHeaders.UserAgent, "Wingmate-AAC")
        secret?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    private data class GitHubTarget(
        val owner: String,
        val repo: String,
        val branch: String,
        val filePath: String,
    ) {
        fun contentsUrl(baseUrl: String): String {
            val encodedPath = filePath.split('/').joinToString("/") { it.encodeURLPathPart() }
            return "${baseUrl.trimEnd('/')}/repos/${owner.encodeURLPathPart()}/${repo.encodeURLPathPart()}/contents/$encodedPath"
        }
    }

    private fun parseTarget(link: RemoteScreenLink): GitHubTarget {
        val parts = link.location.trim().trim('/').split('/')
        if (parts.size != 2 || parts.any { it == "." || it == ".." || !SAFE_GH_SEGMENT.matches(it) }) {
            throw RemoteStorageException(
                RemoteStorageErrorCode.INVALID_CONFIGURATION,
                "GitHub repository must be formatted as 'owner/repo'",
            )
        }
        val rawPath = link.filePath?.trim()?.trimStart('/') ?: ""
        if (rawPath.isBlank() || rawPath.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw RemoteStorageException(
                RemoteStorageErrorCode.INVALID_CONFIGURATION,
                "GitHub file path must be a valid relative path (e.g. screens/home.obz)",
            )
        }
        val branch = link.branch?.trim()?.ifBlank { null } ?: "main"
        return GitHubTarget(parts[0], parts[1], branch, rawPath)
    }

    @Serializable
    private data class GitHubContentResponse(
        val sha: String = "",
        val type: String? = null,
        val size: Long? = null,
        val content: String? = null,
        val encoding: String? = null,
    )

    @Serializable
    private data class GitHubPutRequest(
        val message: String,
        val content: String,
        val branch: String,
        val sha: String? = null,
    )

    @Serializable
    private data class GitHubPutResponse(
        val content: GitHubContentResponse? = null,
    )

    private companion object {
        val SAFE_GH_SEGMENT = Regex("^[A-Za-z0-9._-]+$")
        val GIT_SHA = Regex("^[0-9a-fA-F]{40}$")
        fun isGitSha(value: String): Boolean = GIT_SHA.matches(value.trim())
    }
}

/**
 * Read-only HTTPS URL subscription provider (e.g. Nextcloud public link, school web server, raw URL).
 */
class HttpsUrlRemoteScreenProvider(
    private val httpClient: HttpClient,
    private val maxDownloadBytes: Long = CompositeRemoteScreenStorage.DEFAULT_MAX_REMOTE_OBZ_BYTES,
) : RemoteScreenStorage {

    override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata {
        val url = validateRemoteHttpUrl(link.location, allowQueryParams = true)
        val response = executeNetwork {
            httpClient.head(url) {
                secret?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    header(HttpHeaders.Authorization, "Bearer $it")
                }
            }
        }
        return when {
            response.status == HttpStatusCode.NotFound -> RemoteFileMetadata(revision = null, exists = false)
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Remote URL requires authorization (${response.status.value})")
            response.status.isSuccess() -> RemoteFileMetadata(
                revision = normalizeEtag(response.headers[HttpHeaders.ETag]),
                exists = true,
            )
            // Some static hosts reject HEAD with 405; treat as unknown revision so sync falls back to GET.
            response.status == HttpStatusCode.MethodNotAllowed -> RemoteFileMetadata(revision = null, exists = true)
            else -> throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Remote check failed (${response.status.value})")
        }
    }

    override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent {
        val url = validateRemoteHttpUrl(link.location, allowQueryParams = true)
        return executeNetwork {
            httpClient.prepareGet(url) {
                secret?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    header(HttpHeaders.Authorization, "Bearer $it")
                }
            }.execute { response ->
                when {
                    response.status == HttpStatusCode.NotFound ->
                        throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Remote file was not found")
                    response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                        throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Remote URL requires authorization (${response.status.value})")
                    !response.status.isSuccess() ->
                        throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Download failed (${response.status.value})")
                }
                val bytes = readBoundedBytes(response, maxDownloadBytes)
                val hash = sha256Hex(bytes)
                val revision = normalizeEtag(response.headers[HttpHeaders.ETag]) ?: "sha256:$hash"
                RemoteFileContent(bytes = bytes, revision = revision, contentHash = hash)
            }
        }
    }

    override suspend fun upload(
        link: RemoteScreenLink,
        secret: String?,
        bytes: ByteArray,
        expectedRevision: String?,
        commitMessage: String,
    ): RemoteWriteResult {
        throw RemoteStorageException(
            RemoteStorageErrorCode.READ_ONLY,
            "HTTPS URL links are read-only; use WebDAV, GitHub, or a Linked File to push changes",
        )
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
            if (allowQueryParams) {
                "Enter a direct HTTPS file URL without embedded credentials or fragments"
            } else {
                "Enter a direct HTTPS file URL without embedded credentials, query parameters, or fragments"
            },
        )
    }
    if (host.isBlank()) {
        throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Remote URL must include a host")
    }
    return trimmed
}

internal fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

private fun normalizeEtag(etag: String?): String? = etag?.trim()?.takeIf { it.isNotEmpty() }

private fun isStrongEtag(revision: String): Boolean =
    revision.length >= 2 && revision.first() == '"' && revision.last() == '"'

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
