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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteScreenProvidersTest {
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
