package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.system.Os
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.FileStorageWriteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class AndroidFileStorage(private val context: Context) : FileStorage {
    override suspend fun save(fileName: String, content: String) =
        saveBytes(fileName, content.encodeToByteArray())

    override suspend fun load(fileName: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(fileName)
        if (file.exists()) file.readText() else null
    }

    override suspend fun saveBytes(fileName: String, content: ByteArray) = withContext(Dispatchers.IO) {
        writeAtomically(resolve(fileName)) { it.write(content) }
    }

    override suspend fun saveStream(
        fileName: String,
        producer: suspend (suspend (ByteArray) -> Unit) -> Unit
    ) = withContext(Dispatchers.IO) {
        writeAtomically(resolve(fileName)) { output ->
            producer { chunk -> output.write(chunk) }
        }
    }

    override suspend fun loadBytes(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = resolve(fileName)
        if (file.exists()) file.readBytes() else null
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        resolve(fileName).exists()
    }

    override suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        val file = resolve(fileName)
        if (file.exists() && !file.delete()) error("Could not delete $fileName")
    }

    private fun resolve(fileName: String): File = File(context.filesDir, fileName)

    private suspend fun writeAtomically(file: File, write: suspend (OutputStream) -> Unit) {
        val parent = file.absoluteFile.parentFile ?: throw FileStorageWriteException()
        var temporary: File? = null
        try {
            check(parent.mkdirs() || parent.isDirectory)
            val temp = File.createTempFile(".${file.name}.", ".tmp", parent)
            temporary = temp
            FileOutputStream(temp).use { raw ->
                val output = raw.buffered()
                write(output)
                output.flush()
                raw.fd.sync()
            }
            Os.rename(temp.path, file.path)
        } catch (error: CancellationException) {
            throw error
        } catch (error: FileStorageWriteException) {
            throw error
        } catch (_: Exception) {
            throw FileStorageWriteException()
        } finally {
            temporary?.delete()
        }
    }
}
