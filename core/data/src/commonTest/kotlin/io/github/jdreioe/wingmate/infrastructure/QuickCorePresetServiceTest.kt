package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuickCorePresetServiceTest {

    private val preset = QuickCorePreset.Core24
    private fun archive() = ByteArray(preset.archiveBytes.toInt()) { 7 }

    private fun clientFor(
        bytes: ByteArray,
        onRequest: () -> Unit = {}
    ) = HttpClient(MockEngine { _ ->
        onRequest()
        respond(
            content = bytes,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentLength, bytes.size.toString())
        )
    })

    @Test
    fun cachedArchive_skipsNetworkDownload() {
        runBlocking {
            val cache = InMemoryFileStorage()
            cache.saveBytes("quick-core/${preset.slug}.obz", archive())
            var networkCalls = 0
            val service = QuickCorePresetService(clientFor(archive()) { networkCalls++ }, failingImporter(), cache)

            val result = service.importPreset(preset.slug)

            assertEquals(0, networkCalls)
            assertIs<BoardImportResult.Failure>(result)
        }
    }

    @Test
    fun freshDownload_isWrittenToCache() = runBlocking {
        val cache = InMemoryFileStorage()
        val bytes = archive()
        val service = QuickCorePresetService(clientFor(bytes), failingImporter(), cache)

        service.importPreset(preset.slug)

        assertEquals(bytes.toList(), cache.loadBytes("quick-core/${preset.slug}.obz")?.toList())
    }

    @Test
    fun staleCache_triggersReplacementDownload() = runBlocking {
        val cache = InMemoryFileStorage()
        cache.saveBytes("quick-core/${preset.slug}.obz", ByteArray(1) { 1 })
        var networkCalls = 0
        val bytes = archive()
        val service = QuickCorePresetService(clientFor(bytes) { networkCalls++ }, failingImporter(), cache)

        service.importPreset(preset.slug)

        assertEquals(1, networkCalls)
        assertEquals(bytes.toList(), cache.loadBytes("quick-core/${preset.slug}.obz")?.toList())
    }

    @Test
    fun cacheWriteFailure_doesNotFailTheImport() {
        runBlocking {
            val cache = object : FileStorage by InMemoryFileStorage() {
                override suspend fun saveBytes(fileName: String, content: ByteArray) {
                    throw RuntimeException("cache is full")
                }
            }
            val service = QuickCorePresetService(clientFor(archive()), failingImporter(), cache)

            // The cache write error must be swallowed; the result is the (unrelated) import failure.
            val result = service.importPreset(preset.slug)
            assertIs<BoardImportResult.Failure>(result)
        }
    }

    @Test
    fun unknownPreset_failsWithoutAnyRequest() {
        runBlocking {
            var networkCalls = 0
            val service = QuickCorePresetService(clientFor(archive()) { networkCalls++ }, failingImporter(), null)

            val result = service.importPreset("quick-core-999")

            assertEquals(0, networkCalls)
            assertIs<BoardImportResult.Failure>(result)
        }
    }

    private fun failingImporter() = BoardImportService(
        obfParser = ObfParser(),
        boardRepository = InMemoryBoardRepository(),
        boardSetRepository = InMemoryBoardSetRepository(),
        filePicker = object : io.github.jdreioe.wingmate.platform.FilePicker {
            override suspend fun pickFile(title: String, extensions: List<String>): String? = null
            override suspend fun readFileAsText(path: String): String? = null
            override suspend fun openArchive(path: String): io.github.jdreioe.wingmate.platform.ArchiveReader? = null
        }
    )
}
