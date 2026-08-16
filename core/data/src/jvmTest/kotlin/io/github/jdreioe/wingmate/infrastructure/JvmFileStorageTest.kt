package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorageWriteException
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmFileStorageTest {
    @Test
    fun failedStreamWriteLeavesPreviousFileIntact() = runBlocking {
        val root = Files.createTempDirectory("wingmate-file-storage-").toFile()
        val storage = JvmFileStorage(root)
        storage.save("user-data.json", "previous")

        assertFailsWith<FileStorageWriteException> {
            storage.saveStream("user-data.json") { emit ->
                emit("partial".encodeToByteArray())
                throw IOException("simulated interrupted write")
            }
        }

        assertEquals("previous", storage.load("user-data.json"))
    }
}
