package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.ArchiveEntry
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.FilePicker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every board in `samples/` is handed out as an example, so each must import as it is. */
class SampleBoardsImportTest {
    private class JvmZip(path: String) : ArchiveReader {
        private val zip = ZipFile(path)
        override suspend fun entries() =
            zip.entries().toList().map { ArchiveEntry(it.name, it.size, it.compressedSize, it.isDirectory) }
        override suspend fun readEntry(name: String, maxBytes: Long, onChunk: suspend (ByteArray) -> Unit) {
            onChunk(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
        }
        override suspend fun close() = zip.close()
    }

    private object SamplesPicker : FilePicker {
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override suspend fun readFileAsText(path: String) = File(path).readText()
        override suspend fun openArchive(path: String): ArchiveReader = JvmZip(path)
    }

    @Test
    fun everySampleBoardImports() = runBlocking {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "samples").isDirectory }
        val samples = File(root, "samples").listFiles().orEmpty().filter { it.extension in setOf("obf", "obz") }
        assertTrue(samples.any { it.extension == "obz" }, "expected sample archives in ${root.path}/samples")
        samples.sorted().forEach { sample ->
            val result = BoardImportService(ObfParser(), InMemoryBoardRepository(), InMemoryBoardSetRepository(), SamplesPicker)
                .importBoardSetFromPathResult(sample.path)
            assertIs<BoardImportResult.Success>(result, "${sample.name}: $result")
        }
    }
}
