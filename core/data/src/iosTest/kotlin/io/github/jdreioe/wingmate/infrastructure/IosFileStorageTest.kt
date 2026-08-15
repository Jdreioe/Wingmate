package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorageWriteException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosFileStorageTest {
    private val root = "${NSTemporaryDirectory()}Wingmate-IosFileStorage-${NSUUID.UUID().UUIDString}"
    private val storage = IosFileStorage(root)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun textRoundTripsUnicodeEmptyContentAndOverwrite() = runBlocking {
        val fileName = "nested/dictionary.txt"
        val unicode = "Hej 👋 — 漢字"

        storage.save(fileName, unicode)
        assertEquals(unicode, storage.load(fileName))

        storage.save(fileName, "")
        assertEquals("", storage.load(fileName))
    }

    @Test
    fun bytesRoundTripEmptyContentAndDelete() = runBlocking {
        val fileName = "nested/media.bin"
        val bytes = byteArrayOf(0, 1, 127, -1)

        storage.saveBytes(fileName, bytes)
        assertContentEquals(bytes, storage.loadBytes(fileName))
        assertTrue(storage.exists(fileName))

        storage.saveBytes(fileName, byteArrayOf())
        assertContentEquals(byteArrayOf(), storage.loadBytes(fileName))

        storage.delete(fileName)
        assertFalse(storage.exists(fileName))
    }

    @Test
    fun unwritableDestinationThrowsTypedFailure() = runBlocking {
        val unwritableStorage = IosFileStorage("/dev")

        assertFailsWith<FileStorageWriteException> {
            unwritableStorage.save("wingmate-unwritable.txt", "content")
        }

        assertFailsWith<FileStorageWriteException> {
            unwritableStorage.saveBytes("wingmate-unwritable.bin", byteArrayOf(1))
        }
    }
}
