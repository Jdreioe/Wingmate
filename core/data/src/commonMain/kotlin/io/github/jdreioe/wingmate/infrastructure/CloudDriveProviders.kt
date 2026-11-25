package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.CloudDriveAccessTokens
import io.github.jdreioe.wingmate.domain.obf.CloudDriveFiles
import io.github.jdreioe.wingmate.domain.obf.CloudDriveSignIn
import io.github.jdreioe.wingmate.domain.obf.RemoteFileChoice
import io.github.jdreioe.wingmate.domain.obf.RemoteFileContent
import io.github.jdreioe.wingmate.domain.obf.RemoteFileMetadata
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenLink
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.github.jdreioe.wingmate.domain.obf.RemoteWriteResult
import io.github.jdreioe.wingmate.domain.obf.SyncConnection
import io.github.jdreioe.wingmate.domain.obf.SyncFolderHandle
import io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudDriveSignIn
import io.github.jdreioe.wingmate.domain.obf.UnsupportedRemoteScreenCredentialStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val cloudJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

// region OAuth

/**
 * Tokens for one signed-in cloud drive account. Stored only in [RemoteScreenCredentialStorage];
 * never shown in the UI, exported in OBZ, or included in backups.
 */
@Serializable
data class OAuthTokenBundle(
    val provider: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long,
    val tokenUrl: String? = null,
    val clientId: String? = null,
    /** Only for Google "Desktop app" clients, whose secret Google documents as non-confidential. */
    val clientSecret: String? = null,
    val scope: String? = null,
) {
    fun encode(): String = cloudJson.encodeToString(serializer(), this)

    companion object {
        fun decode(value: String): OAuthTokenBundle = runCatching {
            cloudJson.decodeFromString(serializer(), value)
        }.getOrElse {
            throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Sign in again: the saved sign-in could not be read", it)
        }
    }
}

/** OAuth 2.0 public-client configuration (authorization code + PKCE). */
data class OAuthClientConfig(
    val kind: RemoteStorageKind,
    val clientId: String,
    /** Redirect URI. `{port}` is replaced by loopback browsers with the port they listen on. */
    val redirectUri: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val scopes: List<String>,
    val clientSecret: String? = null,
    val extraAuthorizeParameters: Map<String, String> = emptyMap(),
)

object CloudDriveOAuth {
    const val GOOGLE_AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val GOOGLE_DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val MICROSOFT_AUTHORIZE_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    const val MICROSOFT_TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    val MICROSOFT_SCOPES = listOf("Files.ReadWrite", "offline_access")

    /** Mobile redirect registered for the Microsoft Entra app (custom scheme). */
    const val MOBILE_REDIRECT_URI = "wingmate-auth://oauth"

    fun google(clientId: String, redirectUri: String, clientSecret: String? = null) = OAuthClientConfig(
        kind = RemoteStorageKind.GOOGLE_DRIVE,
        clientId = clientId,
        redirectUri = redirectUri,
        authorizeUrl = GOOGLE_AUTHORIZE_URL,
        tokenUrl = GOOGLE_TOKEN_URL,
        scopes = listOf(GOOGLE_DRIVE_FILE_SCOPE),
        clientSecret = clientSecret,
        extraAuthorizeParameters = mapOf("access_type" to "offline"),
    )

    fun microsoft(clientId: String, redirectUri: String = MOBILE_REDIRECT_URI) = OAuthClientConfig(
        kind = RemoteStorageKind.ONEDRIVE,
        clientId = clientId,
        redirectUri = redirectUri,
        authorizeUrl = MICROSOFT_AUTHORIZE_URL,
        tokenUrl = MICROSOFT_TOKEN_URL,
        scopes = MICROSOFT_SCOPES,
        extraAuthorizeParameters = mapOf("prompt" to "select_account"),
    )

    /** Google iOS clients redirect to the reversed client ID scheme. */
    fun googleIosRedirectUri(clientId: String): String = "${googleIosCallbackScheme(clientId)}:/oauth2redirect"

    fun googleIosCallbackScheme(clientId: String): String =
        "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
}

data class OAuthRedirect(val redirectUri: String, val callbackUrl: String)

/** Platform adapter that shows the provider's sign-in page and returns the redirect. */
interface OAuthBrowser {
    /**
     * Opens the page from [authorizeUrlFor] and waits for the redirect to [redirectUriTemplate]
     * (which may contain `{port}` for loopback browsers). Returns null when cancelled.
     */
    suspend fun authorize(redirectUriTemplate: String, authorizeUrlFor: (redirectUri: String) -> String): OAuthRedirect?
}

/** Authorization-code + PKCE sign-in shared by every platform. No client secret is required. */
class OAuthPkceSignIn(
    private val httpClient: HttpClient,
    private val configs: List<OAuthClientConfig>,
    private val browser: OAuthBrowser,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CloudDriveSignIn {

    override fun isSupported(kind: RemoteStorageKind): Boolean = configs.any { it.kind == kind }

    override suspend fun signIn(kind: RemoteStorageKind): String? {
        val config = configs.firstOrNull { it.kind == kind }
            ?: throw RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Sign-in for this storage is not set up")
        val verifier = randomToken()
        val state = randomToken()
        val challenge = pkceChallenge(verifier)
        val redirect = browser.authorize(config.redirectUri) { redirectUri ->
            URLBuilder(config.authorizeUrl).apply {
                parameters.append("client_id", config.clientId)
                parameters.append("response_type", "code")
                parameters.append("redirect_uri", redirectUri)
                parameters.append("scope", config.scopes.joinToString(" "))
                parameters.append("state", state)
                parameters.append("code_challenge", challenge)
                parameters.append("code_challenge_method", "S256")
                config.extraAuthorizeParameters.forEach { (key, value) -> parameters.append(key, value) }
            }.buildString()
        } ?: return null
        val query = parseCallbackQuery(redirect.callbackUrl)
        query["error"]?.let { error ->
            if (error == "access_denied") return null
            throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Sign-in failed ($error)")
        }
        if (query["state"] != state) {
            throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Sign-in response did not match this request")
        }
        val code = query["code"]
            ?: throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Sign-in did not return an authorization code")
        val token = requestToken(httpClient, config.tokenUrl) {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirect.redirectUri)
            append("client_id", config.clientId)
            append("code_verifier", verifier)
            config.clientSecret?.let { append("client_secret", it) }
        }
        return OAuthTokenBundle(
            provider = kind.wireValue,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtMillis = now() + token.expiresInSeconds * 1000L,
            tokenUrl = config.tokenUrl,
            clientId = config.clientId,
            clientSecret = config.clientSecret,
            scope = config.scopes.joinToString(" "),
        ).encode()
    }

    companion object {
        fun pkceChallenge(verifier: String): String = verifier.encodeUtf8().sha256().base64Url().trimEnd('=')

        /** 64 lowercase hex characters from a cryptographically secure source (valid PKCE verifier). */
        fun randomToken(): String = Uuid.random().toHexString() + Uuid.random().toHexString()

        fun parseCallbackQuery(callbackUrl: String): Map<String, String> {
            val query = callbackUrl.substringAfter('?', "").substringBefore('#')
            if (query.isEmpty()) return emptyMap()
            return query.split('&').filter(String::isNotEmpty).associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                key.decodeURLQueryComponent(plusIsSpace = true) to value.decodeURLQueryComponent(plusIsSpace = true)
            }
        }
    }
}

/** Combines per-provider sign-in adapters (for example Play services for Google on Android). */
class CompositeCloudDriveSignIn(private val adapters: Map<RemoteStorageKind, CloudDriveSignIn>) : CloudDriveSignIn {
    override fun isSupported(kind: RemoteStorageKind): Boolean = adapters[kind]?.isSupported(kind) == true
    override suspend fun signIn(kind: RemoteStorageKind): String? = (adapters[kind] ?: UnsupportedCloudDriveSignIn).signIn(kind)
    override suspend fun refreshPlatformToken(kind: RemoteStorageKind): String? = adapters[kind]?.refreshPlatformToken(kind)
}

/**
 * Keeps access tokens fresh. Refreshed tokens are written back to the credential store so
 * Microsoft's rotating refresh tokens survive app restarts.
 */
class CloudDriveTokenManager(
    private val httpClient: HttpClient,
    private val credentials: RemoteScreenCredentialStorage = UnsupportedRemoteScreenCredentialStorage,
    private val signIn: CloudDriveSignIn = UnsupportedCloudDriveSignIn,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CloudDriveAccessTokens {
    private val mutex = Mutex()

    override suspend fun accessToken(link: RemoteScreenLink, secret: String?, forceRefresh: Boolean): String = mutex.withLock {
        val stored = link.credentialId?.let { id -> runCatching { credentials.read(id) }.getOrNull() }
        val raw = stored ?: secret
            ?: throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Sign in to ${providerName(link.kind)} again")
        val bundle = OAuthTokenBundle.decode(raw)
        if (!forceRefresh && bundle.expiresAtMillis - EXPIRY_MARGIN_MS > now()) return@withLock bundle.accessToken
        val refreshed = refresh(link.kind, bundle)
        link.credentialId?.let { id -> credentials.write(id, refreshed.encode()) }
        refreshed.accessToken
    }

    private suspend fun refresh(kind: RemoteStorageKind, bundle: OAuthTokenBundle): OAuthTokenBundle {
        val refreshToken = bundle.refreshToken
        if (refreshToken == null || bundle.tokenUrl == null || bundle.clientId == null) {
            val platform = signIn.refreshPlatformToken(kind)
                ?: throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Sign in to ${providerName(kind)} again")
            return OAuthTokenBundle.decode(platform)
        }
        val token = requestToken(httpClient, bundle.tokenUrl) {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", bundle.clientId)
            bundle.clientSecret?.let { append("client_secret", it) }
            bundle.scope?.takeIf { kind == RemoteStorageKind.ONEDRIVE }?.let { append("scope", it) }
        }
        return bundle.copy(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: refreshToken,
            expiresAtMillis = now() + token.expiresInSeconds * 1000L,
        )
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

@Serializable
private class TokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long = 3600,
    val error: String? = null,
)

private suspend fun requestToken(
    httpClient: HttpClient,
    tokenUrl: String,
    form: io.ktor.http.ParametersBuilder.() -> Unit,
): TokenResponse {
    val response = executeNetwork { httpClient.submitForm(tokenUrl, parameters(form)) }
    val body = runCatching { response.bodyAsText() }.getOrDefault("")
    val token = runCatching { cloudJson.decodeFromString(TokenResponse.serializer(), body) }.getOrNull()
    if (!response.status.isSuccess() || token == null || token.accessToken.isEmpty()) {
        val code = if (response.status.value in 400..499) RemoteStorageErrorCode.UNAUTHORIZED else RemoteStorageErrorCode.IO_ERROR
        throw RemoteStorageException(code, "Sign-in expired or was revoked (${token?.error ?: response.status.value}). Sign in again")
    }
    return token
}

private fun providerName(kind: RemoteStorageKind) = when (kind) {
    RemoteStorageKind.GOOGLE_DRIVE -> "Google Drive"
    RemoteStorageKind.ONEDRIVE -> "OneDrive"
    else -> kind.wireValue
}

// endregion

// region Shared request helpers

/** Runs an authorized request, refreshing the access token once on HTTP 401. */
private suspend fun <T> authorized(
    tokens: CloudDriveAccessTokens,
    link: RemoteScreenLink,
    secret: String?,
    request: suspend (accessToken: String) -> T,
    isUnauthorized: (T) -> Boolean,
): T {
    val first = request(tokens.accessToken(link, secret))
    if (!isUnauthorized(first)) return first
    return request(tokens.accessToken(link, secret, forceRefresh = true))
}

private fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun HttpResponse.failure(provider: String, action: String): Nothing {
    val body = runCatching { bodyAsText() }.getOrDefault("")
    val code = when {
        status == HttpStatusCode.Unauthorized ->
            RemoteStorageErrorCode.UNAUTHORIZED
        status == HttpStatusCode.TooManyRequests || status.value >= 500 ||
            body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded") ->
            RemoteStorageErrorCode.NETWORK_UNAVAILABLE
        status == HttpStatusCode.Forbidden -> RemoteStorageErrorCode.READ_ONLY
        status == HttpStatusCode.NotFound -> RemoteStorageErrorCode.NOT_FOUND
        status == HttpStatusCode.PreconditionFailed || status == HttpStatusCode.Conflict -> RemoteStorageErrorCode.CONFLICT
        else -> RemoteStorageErrorCode.IO_ERROR
    }
    val message = when (code) {
        RemoteStorageErrorCode.UNAUTHORIZED -> "$provider sign-in expired. Sign in again"
        RemoteStorageErrorCode.READ_ONLY -> "You do not have permission to change this $provider file"
        RemoteStorageErrorCode.NOT_FOUND -> "The $provider file was not found or Wingmate no longer has access to it"
        RemoteStorageErrorCode.CONFLICT -> "The $provider file changed before saving"
        RemoteStorageErrorCode.NETWORK_UNAVAILABLE -> "$provider is busy or unreachable. Changes stay on this device"
        else -> "$provider $action failed (${status.value})"
    }
    throw RemoteStorageException(code, message)
}

/** Downloads bytes, following one redirect to a pre-authenticated URL without forwarding the token. */
private suspend fun downloadFollowingRedirect(
    httpClient: HttpClient,
    url: String,
    accessToken: String,
    maxBytes: Long,
    provider: String,
): ByteArray? = executeNetwork {
    httpClient.prepareGet(url) { bearer(accessToken) }.execute { response ->
        when {
            response.status == HttpStatusCode.Unauthorized -> null
            response.status.value in 300..399 -> {
                val location = response.headers[HttpHeaders.Location]
                    ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "$provider download redirect had no location")
                val target = validateRemoteHttpUrl(location, allowQueryParams = true)
                httpClient.prepareGet(target).execute { redirected ->
                    if (!redirected.status.isSuccess()) redirected.failure(provider, "download")
                    readBoundedBytes(redirected, maxBytes)
                }
            }
            !response.status.isSuccess() -> response.failure(provider, "download")
            else -> readBoundedBytes(response, maxBytes)
        }
    }
}

// endregion

// region Google Drive

/**
 * Google Drive provider using the Drive v3 REST API and the non-sensitive `drive.file` scope.
 * Wingmate can only reach files it created or that a person opened with Wingmate.
 *
 * Drive v3 has no conditional upload, so uploads compare `headRevisionId` immediately before
 * writing; downloads verify the content MD5 against the metadata they were matched with.
 */
class GoogleDriveRemoteScreenProvider(
    private val httpClient: HttpClient,
    private val tokens: CloudDriveAccessTokens,
    private val maxDownloadBytes: Long = CompositeRemoteScreenStorage.DEFAULT_MAX_REMOTE_OBZ_BYTES,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val uploadBaseUrl: String = DEFAULT_UPLOAD_BASE_URL,
) : RemoteScreenStorage {

    @Serializable
    internal class DriveFile(
        val id: String = "",
        val name: String = "",
        val size: String? = null,
        val headRevisionId: String? = null,
        val md5Checksum: String? = null,
        val trashed: Boolean = false,
        val ownedByMe: Boolean? = null,
        val owners: List<DriveUser> = emptyList(),
    ) {
        val hasContent: Boolean get() = (size?.toLongOrNull() ?: 0L) > 0L
        val revision: String? get() = headRevisionId ?: md5Checksum?.let { "md5:$it" }
        val primaryOwnerName: String?
            get() = owners.firstOrNull()?.let { user ->
                user.displayName?.trim()?.takeIf(String::isNotEmpty)
                    ?: user.emailAddress?.trim()?.takeIf(String::isNotEmpty)
            }
    }

    @Serializable
    private class DriveFileList(val files: List<DriveFile> = emptyList())

    @Serializable
    private class DriveAbout(val user: DriveUser? = null)

    @Serializable
    internal class DriveUser(val emailAddress: String? = null, val displayName: String? = null)

    override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata {
        val file = metadata(link, secret) ?: return RemoteFileMetadata(revision = null, exists = false)
        if (file.trashed || !file.hasContent) return RemoteFileMetadata(revision = null, exists = false)
        return RemoteFileMetadata(revision = file.revision, exists = true)
    }

    override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent {
        val file = metadata(link, secret)?.takeIf { !it.trashed && it.hasContent }
            ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "The Google Drive file is empty or was removed")
        file.size?.toLongOrNull()?.let { checkSize(it, maxDownloadBytes) }
        val url = "${apiBaseUrl.trimEnd('/')}/files/${fileId(link).encodeURLPathPart()}?alt=media&supportsAllDrives=true"
        val bytes = authorized(tokens, link, secret, { token ->
            downloadFollowingRedirect(httpClient, url, token, maxDownloadBytes, PROVIDER)
        }, { it == null }) ?: throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Google Drive sign-in expired. Sign in again")
        val md5 = bytes.toByteString().md5().hex()
        if (file.md5Checksum != null && !file.md5Checksum.equals(md5, ignoreCase = true)) {
            throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "The Google Drive file changed while downloading. Try again")
        }
        return RemoteFileContent(bytes = bytes, revision = file.revision, contentHash = sha256Hex(bytes))
    }

    override suspend fun upload(
        link: RemoteScreenLink,
        secret: String?,
        bytes: ByteArray,
        expectedRevision: String?,
        commitMessage: String,
    ): RemoteWriteResult {
        if (link.readOnly) throw RemoteStorageException(RemoteStorageErrorCode.READ_ONLY, "This Google Drive link is read-only")
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        val current = metadata(link, secret)
            ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "The Google Drive file was not found or Wingmate no longer has access to it")
        val currentRevision = current.revision.takeIf { current.hasContent }
        if (currentRevision != expectedRevision) {
            throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "The Google Drive file changed before saving")
        }
        val url = "${uploadBaseUrl.trimEnd('/')}/files/${fileId(link).encodeURLPathPart()}"
        val response = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request(url) {
                    method = HttpMethod.Patch
                    bearer(token)
                    parameter("uploadType", "media")
                    parameter("supportsAllDrives", "true")
                    parameter("fields", FILE_FIELDS)
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!response.status.isSuccess()) response.failure(PROVIDER, "upload")
        val saved = cloudJson.decodeFromString(DriveFile.serializer(), response.bodyAsText())
        return RemoteWriteResult(revision = saved.revision, contentHash = sha256Hex(bytes))
    }

    suspend fun ensureWingmateFolder(secret: String): SyncFolderHandle {
        val link = accountLink()
        val existingResponse = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request("${apiBaseUrl.trimEnd('/')}/files") {
                    bearer(token)
                    parameter(
                        "q",
                        "mimeType = '$FOLDER_MIME_TYPE' and name = '${SyncConnection.WINGMATE_SYNC_FOLDER_NAME}' and trashed = false",
                    )
                    parameter("fields", "files(id,name,ownedByMe)")
                    parameter("orderBy", "modifiedTime desc")
                    parameter("pageSize", "10")
                    parameter("supportsAllDrives", "true")
                    parameter("includeItemsFromAllDrives", "true")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (existingResponse.status.isSuccess()) {
            val found = runCatching {
                cloudJson.decodeFromString(DriveFileList.serializer(), existingResponse.bodyAsText()).files
            }.getOrDefault(emptyList())
                .filter { DRIVE_ID.matches(it.id) }
                .sortedByDescending { it.ownedByMe == true }
                .firstOrNull()
            if (found != null) {
                return SyncFolderHandle(
                    location = found.id,
                    displayName = found.name.ifBlank { SyncConnection.WINGMATE_SYNC_FOLDER_NAME },
                )
            }
        }
        val createResponse = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request("${apiBaseUrl.trimEnd('/')}/files") {
                    method = HttpMethod.Post
                    bearer(token)
                    parameter("fields", "id,name")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"name":${cloudJson.encodeToString(String.serializer(), SyncConnection.WINGMATE_SYNC_FOLDER_NAME)},"mimeType":"$FOLDER_MIME_TYPE"}""",
                    )
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!createResponse.status.isSuccess()) createResponse.failure(PROVIDER, "folder creation")
        val created = cloudJson.decodeFromString(DriveFile.serializer(), createResponse.bodyAsText())
        return SyncFolderHandle(
            location = created.id,
            displayName = created.name.ifBlank { SyncConnection.WINGMATE_SYNC_FOLDER_NAME },
        )
    }

    override suspend fun delete(link: RemoteScreenLink, secret: String?) {
        if (link.readOnly) throw RemoteStorageException(RemoteStorageErrorCode.READ_ONLY, "This Google Drive link is read-only")
        val url = "${apiBaseUrl.trimEnd('/')}/files/${fileId(link).encodeURLPathPart()}"
        val response = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request(url) {
                    method = HttpMethod.Patch
                    bearer(token)
                    parameter("supportsAllDrives", "true")
                    parameter("fields", "id,trashed")
                    contentType(ContentType.Application.Json)
                    // Trash, not permanent delete: the owner can restore it from Google Drive for 30 days.
                    setBody("""{"trashed":true}""")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (response.status == HttpStatusCode.NotFound) return
        if (!response.status.isSuccess()) response.failure(PROVIDER, "delete")
    }

    suspend fun listScreenFiles(secret: String, folderLocation: String? = null): List<RemoteFileChoice> {
        val link = accountLink()
        val folderId = folderLocation?.trim()?.takeIf { DRIVE_ID.matches(it) }
        val query = if (folderId != null) {
            "trashed = false and ('$folderId' in parents or not 'me' in owners)"
        } else {
            "trashed = false"
        }
        val response = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request("${apiBaseUrl.trimEnd('/')}/files") {
                    bearer(token)
                    // `drive.file` only exposes Wingmate's files; `name contains` matches word prefixes
                    // only, so filter by the .obz suffix below instead.
                    parameter("q", query)
                    parameter("fields", "files(id,name,ownedByMe,owners(displayName,emailAddress))")
                    parameter("orderBy", "modifiedTime desc")
                    parameter("pageSize", "100")
                    parameter("supportsAllDrives", "true")
                    parameter("includeItemsFromAllDrives", "true")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!response.status.isSuccess()) response.failure(PROVIDER, "listing")
        return cloudJson.decodeFromString(DriveFileList.serializer(), response.bodyAsText()).files
            .filter { it.name.endsWith(".obz", ignoreCase = true) && DRIVE_ID.matches(it.id) }
            .map {
                RemoteFileChoice(
                    location = it.id,
                    name = it.name,
                    ownedByMe = it.ownedByMe,
                    ownerName = if (it.ownedByMe == false) it.primaryOwnerName else null,
                )
            }
    }

    suspend fun createScreenFile(secret: String, fileName: String, folderLocation: String? = null): RemoteFileChoice {
        val link = accountLink()
        val folderId = folderLocation?.trim()?.takeIf { DRIVE_ID.matches(it) }
        val parentsField = folderId?.let { ""","parents":[${cloudJson.encodeToString(String.serializer(), it)}]""" }.orEmpty()
        val response = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request("${apiBaseUrl.trimEnd('/')}/files") {
                    method = HttpMethod.Post
                    bearer(token)
                    parameter("fields", "id,name")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":${cloudJson.encodeToString(String.serializer(), fileName)},"mimeType":"application/octet-stream"$parentsField}""")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!response.status.isSuccess()) response.failure(PROVIDER, "file creation")
        val created = cloudJson.decodeFromString(DriveFile.serializer(), response.bodyAsText())
        return RemoteFileChoice(location = created.id, name = created.name.ifBlank { fileName }, ownedByMe = true)
    }

    suspend fun accountLabel(secret: String): String? {
        val response = authorized(tokens, accountLink(), secret, { token ->
            executeNetwork {
                httpClient.request("${apiBaseUrl.trimEnd('/')}/about") {
                    bearer(token)
                    parameter("fields", "user(emailAddress,displayName)")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!response.status.isSuccess()) return null
        val user = runCatching { cloudJson.decodeFromString(DriveAbout.serializer(), response.bodyAsText()).user }.getOrNull()
        return user?.emailAddress ?: user?.displayName
    }

    private suspend fun metadata(link: RemoteScreenLink, secret: String?): DriveFile? {
        val url = "${apiBaseUrl.trimEnd('/')}/files/${fileId(link).encodeURLPathPart()}"
        val response = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request(url) {
                    bearer(token)
                    parameter("fields", FILE_FIELDS)
                    parameter("supportsAllDrives", "true")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) response.failure(PROVIDER, "check")
        return cloudJson.decodeFromString(DriveFile.serializer(), response.bodyAsText())
    }

    private fun fileId(link: RemoteScreenLink): String {
        val id = link.location.trim()
        if (!DRIVE_ID.matches(id)) {
            throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "The Google Drive file ID is not valid")
        }
        return id
    }

    private fun accountLink() = RemoteScreenLink(kind = RemoteStorageKind.GOOGLE_DRIVE, location = "")

    companion object {
        const val DEFAULT_API_BASE_URL = "https://www.googleapis.com/drive/v3"
        const val DEFAULT_UPLOAD_BASE_URL = "https://www.googleapis.com/upload/drive/v3"
        private const val PROVIDER = "Google Drive"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS = "id,name,size,headRevisionId,md5Checksum,trashed"
        private val DRIVE_ID = Regex("^[A-Za-z0-9_-]{10,200}$")
    }
}

// endregion

// region OneDrive

/**
 * OneDrive provider using Microsoft Graph. Uploads use `If-Match` with the item eTag, so a
 * concurrent change always surfaces as a conflict.
 */
class OneDriveRemoteScreenProvider(
    private val httpClient: HttpClient,
    private val tokens: CloudDriveAccessTokens,
    private val maxDownloadBytes: Long = CompositeRemoteScreenStorage.DEFAULT_MAX_REMOTE_OBZ_BYTES,
    private val graphBaseUrl: String = DEFAULT_GRAPH_BASE_URL,
) : RemoteScreenStorage {

    @Serializable
    internal class DriveItem(
        val id: String = "",
        val name: String = "",
        val size: Long = 0,
        val eTag: String? = null,
        val parentReference: ParentReference? = null,
        val file: FileFacet? = null,
        val remoteItem: RemoteItemFacet? = null,
    )

    @Serializable
    internal class RemoteItemFacet(val shared: SharedFacet? = null)

    @Serializable
    internal class SharedFacet(val owner: Owner? = null)

    @Serializable
    internal class ParentReference(val driveId: String? = null)

    @Serializable
    internal class FileFacet(val mimeType: String? = null)

    @Serializable
    private class DriveItemList(val value: List<DriveItem> = emptyList())

    @Serializable
    private class Drive(val owner: Owner? = null)

    @Serializable
    internal class Owner(val user: Identity? = null)

    @Serializable
    internal class Identity(val displayName: String? = null)

    override suspend fun head(link: RemoteScreenLink, secret: String?): RemoteFileMetadata {
        val item = item(link, secret)
        if (item == null || item.size <= 0L) return RemoteFileMetadata(revision = null, exists = false)
        return RemoteFileMetadata(revision = item.eTag, exists = true)
    }

    override suspend fun download(link: RemoteScreenLink, secret: String?): RemoteFileContent {
        val before = item(link, secret)?.takeIf { it.size > 0 }
            ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "The OneDrive file is empty or was removed")
        checkSize(before.size, maxDownloadBytes)
        val bytes = authorized(tokens, link, secret, { token ->
            downloadFollowingRedirect(httpClient, "${itemUrl(link)}/content", token, maxDownloadBytes, PROVIDER)
        }, { it == null }) ?: throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "OneDrive sign-in expired. Sign in again")
        val after = item(link, secret)
        if (after?.eTag != before.eTag) {
            throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "The OneDrive file changed while downloading. Try again")
        }
        return RemoteFileContent(bytes = bytes, revision = before.eTag, contentHash = sha256Hex(bytes))
    }

    override suspend fun upload(
        link: RemoteScreenLink,
        secret: String?,
        bytes: ByteArray,
        expectedRevision: String?,
        commitMessage: String,
    ): RemoteWriteResult {
        if (link.readOnly) throw RemoteStorageException(RemoteStorageErrorCode.READ_ONLY, "This OneDrive link is read-only")
        checkSize(bytes.size.toLong(), maxDownloadBytes)
        val ifMatch = expectedRevision ?: run {
            // First upload into an empty placeholder: pin the placeholder's current eTag.
            val current = item(link, secret)
                ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "The OneDrive file was not found")
            if (current.size > 0) {
                throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "The OneDrive file already has content")
            }
            current.eTag
        }
        val response = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request("${itemUrl(link)}/content") {
                    method = HttpMethod.Put
                    bearer(token)
                    ifMatch?.let { header(HttpHeaders.IfMatch, it) }
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!response.status.isSuccess()) response.failure(PROVIDER, "upload")
        val saved = cloudJson.decodeFromString(DriveItem.serializer(), response.bodyAsText())
        return RemoteWriteResult(revision = saved.eTag, contentHash = sha256Hex(bytes))
    }

    suspend fun ensureWingmateFolder(secret: String): SyncFolderHandle {
        val folderName = SyncConnection.WINGMATE_SYNC_FOLDER_NAME
        val check = authorized(tokens, accountLink(), secret, { token ->
            executeNetwork {
                httpClient.request("${graphBaseUrl.trimEnd('/')}/me/drive/root:/${folderName.encodeURLPathPart()}") {
                    bearer(token)
                    parameter("\$select", "id,name")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (check.status.isSuccess()) {
            return SyncFolderHandle(location = folderName, displayName = folderName)
        }
        val create = authorized(tokens, accountLink(), secret, { token ->
            executeNetwork {
                httpClient.request("${graphBaseUrl.trimEnd('/')}/me/drive/root/children") {
                    method = HttpMethod.Post
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":${cloudJson.encodeToString(String.serializer(), folderName)},"folder":{},"@microsoft.graph.conflictBehavior":"fail"}""")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!create.status.isSuccess() && create.status != HttpStatusCode.Conflict) {
            create.failure(PROVIDER, "folder creation")
        }
        return SyncFolderHandle(location = folderName, displayName = folderName)
    }

    override suspend fun delete(link: RemoteScreenLink, secret: String?) {
        if (link.readOnly) throw RemoteStorageException(RemoteStorageErrorCode.READ_ONLY, "This OneDrive link is read-only")
        // Graph moves the item to the OneDrive recycle bin, where it can be restored.
        val response = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request(itemUrl(link)) {
                    method = HttpMethod.Delete
                    bearer(token)
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (response.status == HttpStatusCode.NotFound) return
        if (!response.status.isSuccess()) response.failure(PROVIDER, "delete")
    }

    suspend fun listScreenFiles(secret: String, folderLocation: String? = null): List<RemoteFileChoice> {
        val results = linkedMapOf<String, RemoteFileChoice>()
        val folder = folderLocation?.trim()?.trim('/')?.ifBlank { null }
        if (folder != null) {
            val encoded = folder.split('/').joinToString("/") { it.encodeURLPathPart() }
            val folderResponse = authorized(tokens, accountLink(), secret, { token ->
                executeNetwork {
                    httpClient.request("${graphBaseUrl.trimEnd('/')}/me/drive/root:/$encoded:/children") {
                        bearer(token)
                        parameter("\$select", "id,name,size,file,parentReference,remoteItem")
                        parameter("\$top", "100")
                    }
                }
            }, { it.status == HttpStatusCode.Unauthorized })
            if (folderResponse.status.isSuccess()) {
                parseDriveItems(folderResponse.bodyAsText()).forEach { results[it.location] = it }
            }
        }
        val response = authorized(tokens, accountLink(), secret, { token ->
            executeNetwork {
                httpClient.request("${graphBaseUrl.trimEnd('/')}/me/drive/root/search(q='obz')") {
                    bearer(token)
                    parameter("\$select", "id,name,size,file,parentReference,remoteItem")
                    parameter("\$top", "100")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!response.status.isSuccess()) {
            if (results.isNotEmpty()) return results.values.toList()
            response.failure(PROVIDER, "listing")
        }
        parseDriveItems(response.bodyAsText()).forEach { choice ->
            if ((folder == null || choice.ownedByMe == false) && choice.location !in results) {
                results[choice.location] = choice
            }
        }
        return results.values.toList()
    }

    private fun parseDriveItems(body: String): List<RemoteFileChoice> =
        cloudJson.decodeFromString(DriveItemList.serializer(), body).value
            .filter { it.file != null && it.name.endsWith(".obz", ignoreCase = true) }
            .mapNotNull { item ->
                item.parentReference?.driveId?.let { driveId ->
                    val isOwned = item.remoteItem == null
                    val owner = item.remoteItem?.shared?.owner?.user?.displayName?.trim()?.takeIf(String::isNotEmpty)
                    RemoteFileChoice(
                        location = "$driveId/${item.id}",
                        name = item.name,
                        ownedByMe = isOwned,
                        ownerName = if (isOwned) null else owner,
                    )
                }
            }
            .filter { LOCATION.matches(it.location) }

    suspend fun createScreenFile(secret: String, fileName: String, folderLocation: String? = null): RemoteFileChoice {
        val folder = folderLocation?.trim()?.trim('/')?.ifBlank { null } ?: SyncConnection.WINGMATE_SYNC_FOLDER_NAME
        val path = "$folder/$fileName".split('/').joinToString("/") { it.encodeURLPathPart() }
        val response = authorized(tokens, accountLink(), secret, { token ->
            executeNetwork {
                httpClient.request("${graphBaseUrl.trimEnd('/')}/me/drive/root:/$path:/content") {
                    method = HttpMethod.Put
                    bearer(token)
                    parameter("@microsoft.graph.conflictBehavior", "rename")
                    contentType(ContentType.Application.OctetStream)
                    setBody(ByteArray(0))
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!response.status.isSuccess()) response.failure(PROVIDER, "file creation")
        val created = cloudJson.decodeFromString(DriveItem.serializer(), response.bodyAsText())
        val driveId = created.parentReference?.driveId
            ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "OneDrive did not return the new file's drive")
        return RemoteFileChoice(location = "$driveId/${created.id}", name = created.name.ifBlank { fileName }, ownedByMe = true)
    }

    suspend fun accountLabel(secret: String): String? {
        val response = authorized(tokens, accountLink(), secret, { token ->
            executeNetwork {
                httpClient.request("${graphBaseUrl.trimEnd('/')}/me/drive") {
                    bearer(token)
                    parameter("\$select", "owner")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (!response.status.isSuccess()) return null
        return runCatching { cloudJson.decodeFromString(Drive.serializer(), response.bodyAsText()).owner?.user?.displayName }.getOrNull()
    }

    private suspend fun item(link: RemoteScreenLink, secret: String?): DriveItem? {
        val response = authorized(tokens, link, secret, { token ->
            executeNetwork {
                httpClient.request(itemUrl(link)) {
                    bearer(token)
                    parameter("\$select", "id,name,size,eTag,file,parentReference")
                }
            }
        }, { it.status == HttpStatusCode.Unauthorized })
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) response.failure(PROVIDER, "check")
        return cloudJson.decodeFromString(DriveItem.serializer(), response.bodyAsText())
    }

    private fun itemUrl(link: RemoteScreenLink): String {
        val location = link.location.trim()
        if (!LOCATION.matches(location)) {
            throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "The OneDrive file reference is not valid")
        }
        val (driveId, itemId) = location.split('/')
        return "${graphBaseUrl.trimEnd('/')}/drives/${driveId.encodeURLPathPart()}/items/${itemId.encodeURLPathPart()}"
    }

    private fun accountLink() = RemoteScreenLink(kind = RemoteStorageKind.ONEDRIVE, location = "")

    companion object {
        const val DEFAULT_GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"
        private const val PROVIDER = "OneDrive"
        private val LOCATION = Regex("^[A-Za-z0-9!._-]{1,200}/[A-Za-z0-9!._-]{1,200}$")
    }
}

// endregion

/** Pre-link file operations for both cloud drives. */
class CloudDriveFileOperations(
    private val googleDrive: GoogleDriveRemoteScreenProvider,
    private val oneDrive: OneDriveRemoteScreenProvider,
) : CloudDriveFiles {
    override suspend fun ensureWingmateFolder(kind: RemoteStorageKind, secret: String): SyncFolderHandle = when (kind) {
        RemoteStorageKind.GOOGLE_DRIVE -> googleDrive.ensureWingmateFolder(secret)
        RemoteStorageKind.ONEDRIVE -> oneDrive.ensureWingmateFolder(secret)
        else -> throw notCloudDrive()
    }

    override suspend fun listScreenFiles(kind: RemoteStorageKind, secret: String): List<RemoteFileChoice> =
        listScreenFiles(kind, secret, null)

    override suspend fun listScreenFiles(
        kind: RemoteStorageKind,
        secret: String,
        folderLocation: String?,
    ): List<RemoteFileChoice> = when (kind) {
        RemoteStorageKind.GOOGLE_DRIVE -> googleDrive.listScreenFiles(secret, folderLocation)
        RemoteStorageKind.ONEDRIVE -> oneDrive.listScreenFiles(secret, folderLocation)
        else -> throw notCloudDrive()
    }

    override suspend fun createScreenFile(kind: RemoteStorageKind, secret: String, fileName: String): RemoteFileChoice =
        createScreenFile(kind, secret, fileName, null)

    override suspend fun createScreenFile(
        kind: RemoteStorageKind,
        secret: String,
        fileName: String,
        folderLocation: String?,
    ): RemoteFileChoice {
        val safeName = sanitizeFileName(fileName)
        return when (kind) {
            RemoteStorageKind.GOOGLE_DRIVE -> googleDrive.createScreenFile(secret, safeName, folderLocation)
            RemoteStorageKind.ONEDRIVE -> oneDrive.createScreenFile(secret, safeName, folderLocation)
            else -> throw notCloudDrive()
        }
    }

    override suspend fun accountLabel(kind: RemoteStorageKind, secret: String): String? = when (kind) {
        RemoteStorageKind.GOOGLE_DRIVE -> googleDrive.accountLabel(secret)
        RemoteStorageKind.ONEDRIVE -> oneDrive.accountLabel(secret)
        else -> null
    }

    private fun notCloudDrive() =
        RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Choose Google Drive or OneDrive")

    companion object {
        fun sanitizeFileName(name: String): String {
            val base = name.removeSuffix(".obz").replace(Regex("[\\\\/:*?\"<>|#%]"), " ").trim().ifEmpty { "Screen" }.take(100)
            return "$base.obz"
        }
    }
}
