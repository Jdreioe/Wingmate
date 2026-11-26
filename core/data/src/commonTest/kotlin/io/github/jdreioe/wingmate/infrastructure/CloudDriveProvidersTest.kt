package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.CloudDriveAccessTokens
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenLink
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudDriveProvidersTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { request -> handler(request) }) { followRedirects = false }

    private fun HttpRequestData.bodyBytes(): ByteArray = when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes()
        else -> ByteArray(0)
    }

    private object FixedToken : CloudDriveAccessTokens {
        override suspend fun accessToken(link: RemoteScreenLink, secret: String?, forceRefresh: Boolean) = "token"
    }

    @Test
    fun googleDriveCreatesFillsAndDetectsConcurrentChanges() = runBlocking {
        var content = ByteArray(0)
        var revision = 0
        val http = client { request ->
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path.endsWith("/drive/v3/files") ->
                    respond("""{"id":"file_1234567890","name":"Home.obz"}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Patch && path.startsWith("/upload/drive/v3/files/") -> {
                    content = request.bodyBytes()
                    revision++
                    respond(fileJson(content, revision), HttpStatusCode.OK, json)
                }
                request.url.parameters["alt"] == "media" -> respond(content, HttpStatusCode.OK)
                path.endsWith("/files/file_1234567890") -> respond(fileJson(content, revision), HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val provider = GoogleDriveRemoteScreenProvider(http, FixedToken)
        val created = provider.createScreenFile("secret", "Home.obz")
        val link = RemoteScreenLink(RemoteStorageKind.GOOGLE_DRIVE, created.location)

        // The new, empty file counts as missing so the first push fills it.
        assertFalse(provider.head(link, "secret").exists)
        val first = provider.upload(link, "secret", "v1".encodeToByteArray(), null)
        assertEquals("rev1", first.revision)
        assertEquals("rev1", provider.head(link, "secret").revision)
        assertEquals("v1", provider.download(link, "secret").bytes.decodeToString())

        // Someone else saved meanwhile: the stale revision is rejected without writing.
        provider.upload(link, "secret", "teacher".encodeToByteArray(), "rev1")
        val conflict = assertFailsWith<RemoteStorageException> {
            provider.upload(link, "secret", "mine".encodeToByteArray(), "rev1")
        }
        assertEquals(RemoteStorageErrorCode.CONFLICT, conflict.code)
        assertEquals("teacher", content.decodeToString())
    }

    private fun fileJson(content: ByteArray, revision: Int): String {
        val md5 = content.toByteString().md5().hex()
        return """{"id":"file_1234567890","name":"Home.obz","size":"${content.size}","headRevisionId":"rev$revision","md5Checksum":"$md5","trashed":false}"""
    }

    @Test
    fun oneDriveUsesIfMatchAndFollowsDownloadRedirectWithoutToken() = runBlocking {
        var content = ByteArray(0)
        var etag = 1
        val ifMatchHeaders = mutableListOf<String?>()
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.url.host == "download.example" -> {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    respond(content, HttpStatusCode.OK)
                }
                request.method == HttpMethod.Put && path.endsWith(":/content") ->
                    respond(itemJson(0, etag), HttpStatusCode.Created, json)
                request.method == HttpMethod.Put && path.endsWith("/content") -> {
                    val ifMatch = request.headers[HttpHeaders.IfMatch]
                    ifMatchHeaders += ifMatch
                    if (ifMatch != "\"e$etag\"") {
                        respond("", HttpStatusCode.PreconditionFailed)
                    } else {
                        content = request.bodyBytes()
                        etag++
                        respond(itemJson(content.size, etag), HttpStatusCode.OK, json)
                    }
                }
                path.endsWith("/content") ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://download.example/file"))
                path.contains("/drives/") -> respond(itemJson(content.size, etag), HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val provider = OneDriveRemoteScreenProvider(http, FixedToken)
        val created = provider.createScreenFile("secret", "Home.obz")
        assertEquals("drive1/item1", created.location)
        val link = RemoteScreenLink(RemoteStorageKind.ONEDRIVE, created.location)

        assertFalse(provider.head(link, "secret").exists)
        val first = provider.upload(link, "secret", "v1".encodeToByteArray(), null)
        assertEquals("\"e2\"", first.revision)
        assertEquals("v1", provider.download(link, "secret").bytes.decodeToString())

        val conflict = assertFailsWith<RemoteStorageException> {
            provider.upload(link, "secret", "v2".encodeToByteArray(), "\"e1\"")
        }
        assertEquals(RemoteStorageErrorCode.CONFLICT, conflict.code)
        assertEquals(listOf<String?>("\"e1\"", "\"e1\""), ifMatchHeaders)
    }

    private fun itemJson(size: Int, etag: Int) =
        """{"id":"item1","name":"Home.obz","size":$size,"eTag":"\"e$etag\"","file":{},"parentReference":{"driveId":"drive1"}}"""

    @Test
    fun pkceSignInExchangesCodeAndTokenManagerRefreshesAndPersists() = runBlocking {
        val forms = mutableListOf<String>()
        var issued = 0
        val http = client { request ->
            forms += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            issued++
            respond(
                """{"access_token":"access$issued","refresh_token":"refresh$issued","expires_in":3600}""",
                HttpStatusCode.OK,
                json,
            )
        }
        var authorizeUrl = ""
        val browser = object : OAuthBrowser {
            override suspend fun authorize(redirectUriTemplate: String, authorizeUrlFor: (String) -> String): OAuthRedirect {
                val redirectUri = redirectUriTemplate.replace("{port}", "4711")
                authorizeUrl = authorizeUrlFor(redirectUri)
                val state = OAuthPkceSignIn.parseCallbackQuery(authorizeUrl)["state"]
                return OAuthRedirect(redirectUri, "$redirectUri?code=abc&state=$state")
            }
        }
        var clock = 1_000L
        val config = CloudDriveOAuth.microsoft("client-id", "http://localhost:{port}")
        val signIn = OAuthPkceSignIn(http, listOf(config), browser) { clock }
        assertTrue(signIn.isSupported(RemoteStorageKind.ONEDRIVE))
        assertFalse(signIn.isSupported(RemoteStorageKind.GOOGLE_DRIVE))

        val bundle = assertNotNull(signIn.signIn(RemoteStorageKind.ONEDRIVE))
        val query = OAuthPkceSignIn.parseCallbackQuery(authorizeUrl)
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("http://localhost:4711", query["redirect_uri"])
        assertTrue(forms.single().contains("code_verifier="))
        assertTrue(forms.single().contains("redirect_uri=http%3A%2F%2Flocalhost%3A4711"))

        val store = MemoryCredentials()
        store.write("cred", bundle)
        val tokens = CloudDriveTokenManager(http, store) { clock }
        val link = RemoteScreenLink(RemoteStorageKind.ONEDRIVE, "drive1/item1", credentialId = "cred")
        assertEquals("access1", tokens.accessToken(link, null))

        // After expiry the refresh token is used and the rotated tokens are saved.
        clock += 3_600_000L
        assertEquals("access2", tokens.accessToken(link, null))
        assertTrue(forms.last().contains("grant_type=refresh_token"))
        assertTrue(forms.last().contains("refresh_token=refresh1"))
        assertEquals("refresh2", OAuthTokenBundle.decode(store.values.getValue("cred")).refreshToken)
    }

    @Test
    fun cancelledOrMismatchedSignInsAreRejected() = runBlocking {
        val http = client { respond("", HttpStatusCode.InternalServerError) }
        val config = CloudDriveOAuth.google("id.apps.googleusercontent.com", CloudDriveOAuth.googleIosRedirectUri("id.apps.googleusercontent.com"))
        assertEquals("com.googleusercontent.apps.id:/oauth2redirect", config.redirectUri)
        val cancelled = OAuthPkceSignIn(http, listOf(config), object : OAuthBrowser {
            override suspend fun authorize(redirectUriTemplate: String, authorizeUrlFor: (String) -> String): OAuthRedirect? = null
        })
        assertNull(cancelled.signIn(RemoteStorageKind.GOOGLE_DRIVE))

        val forged = OAuthPkceSignIn(http, listOf(config), object : OAuthBrowser {
            override suspend fun authorize(redirectUriTemplate: String, authorizeUrlFor: (String) -> String) =
                OAuthRedirect(redirectUriTemplate, "$redirectUriTemplate?code=abc&state=wrong")
        })
        val error = assertFailsWith<RemoteStorageException> { forged.signIn(RemoteStorageKind.GOOGLE_DRIVE) }
        assertEquals(RemoteStorageErrorCode.UNAUTHORIZED, error.code)
    }

    @Test
    fun iosOAuthRedirectsMatchTheRegisteredUrlSchemes() {
        assertEquals(
            "com.googleusercontent.apps.1234567890-abc123:/oauth2redirect",
            CloudDriveOAuth.googleIosRedirectUri("1234567890-abc123.apps.googleusercontent.com"),
        )
        assertEquals(
            "com.googleusercontent.apps.1234567890-abc123",
            CloudDriveOAuth.googleIosCallbackScheme("1234567890-abc123.apps.googleusercontent.com"),
        )
        assertEquals("wingmate-auth://oauth", CloudDriveOAuth.microsoft("client-id").redirectUri)
        assertEquals("msauth.com.hojmoseit.wingmate://auth", CloudDriveOAuth.MICROSOFT_IOS_REDIRECT_URI)
        val ios = CloudDriveOAuth.microsoft("11111111-2222-3333-4444-555555555555", CloudDriveOAuth.MICROSOFT_IOS_REDIRECT_URI)
        assertEquals(CloudDriveOAuth.MICROSOFT_IOS_REDIRECT_URI, ios.redirectUri)
        assertEquals(listOf("Files.ReadWrite", "offline_access"), ios.scopes)
    }

    private class MemoryCredentials : RemoteScreenCredentialStorage {
        val values = mutableMapOf<String, String>()
        override val isSupported = true
        override suspend fun read(credentialId: String) = values[credentialId]
        override suspend fun write(credentialId: String, secret: String) {
            values[credentialId] = secret
        }
        override suspend fun delete(credentialId: String) {
            values.remove(credentialId)
        }
    }
}
