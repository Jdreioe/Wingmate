package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.JvmZipArchiveReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads paths selected by the native Rust UI for the shared OBF/OBZ importer. */
class LinuxFilePicker : FilePicker {
    override suspend fun pickFile(title: String, extensions: List<String>): String? = null

    override suspend fun readFileAsText(path: String): String? = withContext(Dispatchers.IO) {
        runCatching { File(path).readText() }.getOrNull()
    }

    override suspend fun openArchive(path: String): ArchiveReader? = withContext(Dispatchers.IO) {
        runCatching { JvmZipArchiveReader(File(path)) }.getOrNull()
    }

    override suspend fun openArchiveBytes(content: ByteArray): ArchiveReader? = withContext(Dispatchers.IO) {
        val temporary = kotlin.io.path.createTempFile("wingmate-preset-", ".obz").toFile()
        try {
            temporary.writeBytes(content)
            val delegate = JvmZipArchiveReader(temporary)
            object : ArchiveReader by delegate {
                override suspend fun close() {
                    try {
                        delegate.close()
                    } finally {
                        temporary.delete()
                    }
                }
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }
}
