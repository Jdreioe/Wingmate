package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.CloudShareRecord
import io.github.jdreioe.wingmate.domain.obf.CloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.LinkedFileHandle
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenLink
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteScreenProvidersTest {
    @Test
    fun webDavCreatesUpdatesWithStrongAndWeakEtagsAndFallsBackToHeadAfterPut() = runBlocking {
        val putPreconditions = mutableListOf<Pair<String?, String?>>()
        var currentEtag = "\"v1\""
        var putReturnsEtag = false
        val client = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, currentEtag))
                HttpMethod.Put -> {
                    val ifNone = request.headers[HttpHeaders.IfNoneMatch]
                    val ifMatch = request.headers[HttpHeaders.IfMatch]
                    putPreconditions += ifNone to ifMatch
                    if (ifMatch == "\"stale\"") {
                        respond("", HttpStatusCode.PreconditionFailed)
                    } else {
                        currentEtag = if (currentEtag == "\"v1\"") "\"v2\"" else "W/\"v3\""
                        if (putReturnsEtag) {
                            respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.ETag, currentEtag))
                        } else {
                            respond("", HttpStatusCode.Created)
                        }
                    }
                }
                else -> respond("", HttpStatusCode.MethodNotAllowed)
            }
        })
        try {
            val storage = WebDavRemoteScreenProvider(client)
            val link = RemoteScreenLink(RemoteStorageKind.WEBDAV, "https://example.test/home.obz")

            // Create sends If-None-Match: * and fetches ETag via follow-up HEAD when PUT omits ETag.
            val created = storage.upload(link, null, byteArrayOf(1), null)
            assertEquals("\"v2\"", created.revision)

            // Stale strong ETag triggers 412 -> CONFLICT.
            val conflict = assertFailsWith<RemoteStorageException> {
                storage.upload(link, null, byteArrayOf(2), "\"stale\"")
            }
            assertEquals(RemoteStorageErrorCode.CONFLICT, conflict.code)

            // Strong ETag sends If-Match and updates cleanly to a weak ETag.
            putReturnsEtag = true
            val updated = storage.upload(link, null, byteArrayOf(3), "\"v2\"")
            assertEquals("W/\"v3\"", updated.revision)

            // Weak ETag verifies via HEAD instead of sending invalid If-Match, and succeeds when unchanged.
            val afterWeak = storage.upload(link, null, byteArrayOf(4), "W/\"v3\"")
            assertEquals("W/\"v3\"", afterWeak.revision)

            // Mismatched weak ETag detects conflict via HEAD without overwriting.
            val weakConflict = assertFailsWith<RemoteStorageException> {
                storage.upload(link, null, byteArrayOf(5), "W/\"old-weak\"")
            }
            assertEquals(RemoteStorageErrorCode.CONFLICT, weakConflict.code)

            assertEquals(
                listOf("*" to null, null to "\"stale\"", null to "\"v2\"", null to null),
                putPreconditions,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun githubVerifiesThatRawContentMatchesTheReportedBlob() = runBlocking {
        val expected = "archive content"
        val sha = Buffer().writeUtf8("blob ${expected.length}\u0000$expected").sha1().hex()
        var raw = expected
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/repos/owner/repo/contents/screens/home.obz", request.url.encodedPath)
            assertEquals("topic", request.url.parameters["ref"])
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            when (request.headers[HttpHeaders.Accept]) {
                "application/vnd.github.object+json" ->
                    respond("""{"type":"file","sha":"$sha","encoding":"none","size":${expected.length}}""")
                "application/vnd.github.raw+json" -> respond(raw)
                else -> error("Unexpected GitHub media type")
            }
        })
        try {
            val storage = GitHubRemoteScreenProvider(client)
            val link = RemoteScreenLink(RemoteStorageKind.GITHUB, "owner/repo", branch = "topic", filePath = "screens/home.obz")
            val downloaded = storage.download(link, "test-token")
            assertEquals(sha, downloaded.revision)
            assertEquals(expected, downloaded.bytes.decodeToString())
            raw = "another version"
            val conflict = assertFailsWith<RemoteStorageException> { storage.download(link, "test-token") }
            assertEquals(RemoteStorageErrorCode.CONFLICT, conflict.code)
        } finally {
            client.close()
        }
    }

    @Test
    fun httpsSubscriptionAllowsShareQueryStringsRejectsCredentialsAndCapsSize() = runBlocking {
        var count = 0
        val client = HttpClient(MockEngine { request ->
            count++
            if (request.url.parameters["dl"] == "1") respond("ok") else respond("123456")
        })
        try {
            val storage = HttpsUrlRemoteScreenProvider(client, maxDownloadBytes = 5)
            for (url in listOf(
                "https://user:token@example.test/home.obz",
                "https://example.test/home.obz#section",
                "http://example.test/home.obz",
            )) {
                assertFailsWith<RemoteStorageException> {
                    storage.download(RemoteScreenLink(RemoteStorageKind.HTTPS_URL, url), null)
                }
            }
            assertEquals(0, count)

            // Share links with query parameters (Dropbox ?dl=1, Drive ?export=download) work.
            val shareDownload = storage.download(
                RemoteScreenLink(RemoteStorageKind.HTTPS_URL, "https://example.test/home.obz?dl=1"),
                null,
            )
            assertEquals("ok", shareDownload.bytes.decodeToString())
            assertEquals(1, count)

            // Oversized streaming responses without Content-Length are capped.
            val failure = assertFailsWith<RemoteStorageException> {
                storage.download(RemoteScreenLink(RemoteStorageKind.HTTPS_URL, "https://example.test/home.obz"), null)
            }
            assertEquals(RemoteStorageErrorCode.IO_ERROR, failure.code)
            assertEquals(2, count)
        } finally {
            client.close()
        }
    }

    @Test
    fun linkedFileProviderHandlesEmptyPlaceholderUploadsAndDetectsConflicts() = runBlocking {
        val files = mutableMapOf<String, ByteArray>("/sync/home.obz" to ByteArray(0))
        val fileStorage = object : LinkedRemoteFileStorage {
            override val isSupported: Boolean = true
            override suspend fun pickExistingFile(): LinkedFileHandle? = null
            override suspend fun createLinkedFile(suggestedFileName: String): LinkedFileHandle? = null
            override suspend fun readBytes(location: String): ByteArray =
                files[location] ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Missing")
            override suspend fun writeBytes(location: String, bytes: ByteArray) {
                files[location] = bytes
            }
        }
        val provider = LinkedFileRemoteScreenProvider(fileStorage)
        val link = RemoteScreenLink(RemoteStorageKind.LINKED_FILE, "/sync/home.obz", displayName = "home.obz")

        // 0-byte SAF/FileExporter placeholder is treated as not yet populated.
        assertFalse(provider.head(link, null).exists)

        val first = provider.upload(link, null, "v1".encodeToByteArray(), null)
        assertTrue(provider.head(link, null).exists)
        assertEquals(first.revision, provider.head(link, null).revision)

        val second = provider.upload(link, null, "v2".encodeToByteArray(), first.revision)
        assertEquals("v2", provider.download(link, null).bytes.decodeToString())

        // Stale revision raises CONFLICT without overwriting.
        val conflict = assertFailsWith<RemoteStorageException> {
            provider.upload(link, null, "v3".encodeToByteArray(), first.revision)
        }
        assertEquals(RemoteStorageErrorCode.CONFLICT, conflict.code)
        assertEquals(second.revision, provider.head(link, null).revision)
    }

    @Test
    fun cloudShareProviderUsesRecordRevisionsAndRejectsStaleOrViewOnlyWrites() = runBlocking {
        val location = "icloud://shared/_owner/WingmateScreens/abc"
        var record = CloudShareRecord(revision = null, bytes = null)
        var tag = 0
        val cloud = object : CloudShareScreenStorage {
            override val isSupported = true
            override suspend fun fetch(location: String, includePackage: Boolean) =
                CloudShareRecord(record.revision, record.bytes.takeIf { includePackage })
            override suspend fun save(location: String, bytes: ByteArray, expectedRevision: String?): String {
                if (record.revision != expectedRevision) {
                    throw RemoteStorageException(RemoteStorageErrorCode.CONFLICT, "changed")
                }
                record = CloudShareRecord("tag-${++tag}", bytes)
                return record.revision!!
            }
        }
        val provider = CloudShareRemoteScreenProvider(cloud)
        val link = RemoteScreenLink(RemoteStorageKind.ICLOUD_SHARE, location)

        // A freshly shared record without a package counts as empty so the first push succeeds.
        assertFalse(provider.head(link, null).exists)
        val first = provider.upload(link, null, "v1".encodeToByteArray(), null)
        assertEquals("tag-1", first.revision)
        assertEquals("tag-1", provider.head(link, null).revision)
        assertEquals("v1", provider.download(link, null).bytes.decodeToString())

        val stale = assertFailsWith<RemoteStorageException> {
            provider.upload(link, null, "v2".encodeToByteArray(), null)
        }
        assertEquals(RemoteStorageErrorCode.CONFLICT, stale.code)

        val viewOnly = assertFailsWith<RemoteStorageException> {
            provider.upload(link.copy(readOnly = true), null, "v2".encodeToByteArray(), "tag-1")
        }
        assertEquals(RemoteStorageErrorCode.READ_ONLY, viewOnly.code)

        val invalid = assertFailsWith<RemoteStorageException> {
            provider.head(link.copy(location = "https://example.test/home.obz"), null)
        }
        assertEquals(RemoteStorageErrorCode.INVALID_CONFIGURATION, invalid.code)

        val unsupported = assertFailsWith<RemoteStorageException> {
            CompositeRemoteScreenStorage(HttpClient(MockEngine { respond("") })).head(link, null)
        }
        assertEquals(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, unsupported.code)
    }
}
