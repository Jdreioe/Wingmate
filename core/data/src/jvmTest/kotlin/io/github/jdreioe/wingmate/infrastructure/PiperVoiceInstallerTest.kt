package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PiperDownloadFailure
import io.github.jdreioe.wingmate.domain.PiperFile
import io.github.jdreioe.wingmate.domain.PiperVoicePack
import io.github.jdreioe.wingmate.domain.PiperVoiceState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiperVoiceInstallerTest {
    private val root: Path = Files.createTempDirectory("piper-test").toOkioPath()
    private val fs = FileSystem.SYSTEM

    private val model = ByteArray(200_000) { (it % 251).toByte() }
    private val tokens = "a 1\nb 2\n".encodeToByteArray()
    private val phonemizerZip = zipOf("espeak-ng-data/phontab" to "table", "espeak-ng-data/en_dict" to "dict")

    private val phonemizer = pinned("espeak-ng-data.zip", "https://example.test/espeak.zip", phonemizerZip)
    private val pack = PiperVoicePack(
        key = "da_DK-test-medium",
        languageTag = "da-DK",
        speaker = "test",
        quality = "medium",
        speakerCount = 1,
        licence = "CC0",
        files = listOf(
            pinned("da_DK-test-medium.onnx", "https://example.test/model.onnx", model),
            pinned("tokens.txt", "https://example.test/tokens.txt", tokens),
        ),
    )
    private val served = mapOf(
        phonemizer.url to phonemizerZip,
        pack.files[0].url to model,
        pack.files[1].url to tokens,
    )
    private val rangeRequests = mutableListOf<String>()
    private val downloads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun cleanUp() {
        downloads.cancel()
        fs.deleteRecursively(root)
    }

    @Test
    fun `download verifies and installs the voice with the shared phonemizer data`() = runBlocking {
        val installer = installer()

        installer.download(pack.key)
        assertEquals(PiperVoiceState.Installed, finalState(installer))

        val files = installer.verifiedFiles(pack.key)
        assertTrue(fs.exists(files.phonemizerData / "phontab"))
        assertEquals(model.toByteString(), fs.read(files.model) { readByteString() })
        assertFalse(fs.exists(root / phonemizer.name), "the phonemizer archive is removed after extraction")
    }

    @Test
    fun `an interrupted download resumes from the kept bytes`() = runBlocking {
        val modelPart = root / "voices" / pack.key / "${pack.files[0].name}.part"
        fs.createDirectories(modelPart.parent!!)
        fs.write(modelPart) { write(model, 0, 50_000) }
        val installer = installer()

        installer.refresh()
        assertEquals(PiperVoiceState.Incomplete(50_000, pack.sizeBytes + phonemizer.sizeBytes, failure = null), installer.states.value[pack.key])

        installer.download(pack.key)

        assertEquals(PiperVoiceState.Installed, finalState(installer))
        assertEquals(listOf("bytes=50000-"), rangeRequests)
        assertEquals(model.toByteString(), fs.read(installer.verifiedFiles(pack.key).model) { readByteString() })
    }

    @Test
    fun `a file that fails its checksum is rejected and removed`() = runBlocking {
        val installer = installer(serve = served + (pack.files[1].url to "x 9\ny 8\n".encodeToByteArray()))

        installer.download(pack.key)

        val state = finalState(installer)
        assertEquals(PiperDownloadFailure.Damaged, (state as PiperVoiceState.Incomplete).failure)
        val voiceDir = root / "voices" / pack.key
        assertFalse(fs.exists(voiceDir / "tokens.txt"))
        assertFalse(fs.exists(voiceDir / "tokens.txt.part"))
    }

    @Test
    fun `a server error keeps downloaded files for resuming`() = runBlocking {
        val installer = installer(serve = served - pack.files[1].url)

        installer.download(pack.key)

        val state = finalState(installer) as PiperVoiceState.Incomplete
        assertEquals(PiperDownloadFailure.Network, state.failure)
        assertEquals(model.size.toLong(), state.downloadedBytes)
    }

    @Test
    fun `an installed voice that was damaged on disk is rejected before loading`() = runBlocking {
        val installer = installer()
        installer.download(pack.key)
        finalState(installer)
        val modelPath = root / "voices" / pack.key / pack.files[0].name
        fs.write(modelPath) { write(ByteArray(model.size)) }

        assertFailsWith<PiperVoiceUnavailableException> { installer.verifiedFiles(pack.key) }

        assertEquals(PiperDownloadFailure.Damaged, (installer.states.value[pack.key] as PiperVoiceState.Incomplete).failure)
        assertFalse(fs.exists(modelPath))
    }

    @Test
    fun `deleting the last voice also removes the shared phonemizer data`() = runBlocking {
        val installer = installer()
        installer.download(pack.key)
        finalState(installer)

        installer.delete(pack.key)

        assertEquals(PiperVoiceState.NotInstalled, installer.states.value[pack.key])
        assertFalse(fs.exists(root / "espeak-ng-data"))
        assertFalse(fs.exists(root / "voices" / pack.key))
    }

    private fun installer(serve: Map<String, ByteArray> = served) = PiperVoiceInstaller(
        root = root,
        fileSystem = fs,
        httpClient = HttpClient(MockEngine { request ->
            val body = serve[request.url.toString()]
                ?: return@MockEngine respond("missing", HttpStatusCode.InternalServerError)
            val range = request.headers[HttpHeaders.Range]
            if (range != null) {
                rangeRequests += range
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                respond(ByteReadChannel(body.copyOfRange(from, body.size)), HttpStatusCode.PartialContent, headersOf())
            } else {
                respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf())
            }
        }),
        catalog = listOf(pack),
        phonemizer = phonemizer,
        scope = downloads,
    )

    /** Waits until the download installs the voice or stops with a failure. */
    private suspend fun finalState(installer: PiperVoiceInstaller): PiperVoiceState = withTimeout(10_000) {
        installer.states.first { states ->
            when (val state = states[pack.key]) {
                PiperVoiceState.Installed -> true
                is PiperVoiceState.Incomplete -> state.failure != null
                else -> false
            }
        }.getValue(pack.key)
    }

    private fun pinned(name: String, url: String, bytes: ByteArray) =
        PiperFile(name, url, bytes.size.toLong(), bytes.toByteString().sha256().hex())

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("espeak-ng-data/"))
            zip.closeEntry()
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
