import io.github.jdreioe.wingmate.application.BackupMediaAccess
import io.github.jdreioe.wingmate.application.BackupFailureKind
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.TypingScreenUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfig
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.pageElements
import io.github.jdreioe.wingmate.domain.obf.withPageElements
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryConfigRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryPronunciationDictionaryRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryVoiceRepository
import io.github.jdreioe.wingmate.platform.ArchiveEntry
import io.github.jdreioe.wingmate.platform.ArchiveReadError
import io.github.jdreioe.wingmate.platform.ArchiveReadException
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.FilePicker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CompleteBackupManagerTest {
    @Test
    fun exportContainsVersionedManifestUserDataAndReferencedMedia() = runBlocking {
        val boards = InMemoryBoardRepository()
        val sets = InMemoryBoardSetRepository()
        val phrases = InMemoryPhraseRepository()
        val categories = InMemoryCategoryRepository()
        val settings = InMemorySettingsRepository()
        val voices = InMemoryVoiceRepository()
        val history = InMemorySaidTextRepository()
        val dictionary = InMemoryPronunciationDictionaryRepository()
        val config = InMemoryConfigRepository()
        config.saveSpeechConfig(SpeechServiceConfig("northeurope", "azure-secret"))
        config.saveGoogleSpeechConfig(GoogleSpeechConfig("google-secret"))
        boards.saveBoard(
            ObfBoard(
                format = "open-board-0.1",
                id = "board",
                name = "My private board",
                sounds = listOf(ObfSound(id = "sound", path = "/recordings/hello.m4a"))
            )
        )
        sets.saveBoardSet(ObfBoardSet("set", "My screen", "board", listOf("board"), createdAt = 1, updatedAt = 1))
        phrases.add(Phrase("phrase", "Need water", createdAt = 1, recordingPath = "/recordings/hello.m4a"))
        val media = object : BackupMediaAccess {
            override suspend fun read(path: String): ByteArray? = if (path == "/recordings/hello.m4a") "audio-content".encodeToByteArray() else null
            override suspend fun restore(archiveName: String, bytes: ByteArray): String = archiveName
            override suspend fun deleteRestored(path: String) = Unit
        }
        val manager = CompleteBackupManager(
            boards, sets, phrases, categories, settings, voices, history, dictionary, config,
            filePicker = null,
            mediaAccess = media
        )

        val archiveText = manager.exportBackup().decodeToString(throwOnInvalidSequence = false)
        assertContains(archiveText, "wingmate-backup")
        assertContains(archiveText, "My private board")
        assertContains(archiveText, "Need water")
        assertContains(archiveText, "audio-content")
        assertFalse(archiveText.contains("azure-secret"))
        assertFalse(archiveText.contains("google-secret"))
        assertFalse(archiveText.contains("editingAccessCredential"))
    }

    @Test
    fun backupRoundTripRestoresDataMediaDictionaryAndBoardBackground() = runBlocking {
        val source = Repositories()
        source.boards.saveBoard(
            ObfBoard(
                format = "open-board-0.1",
                id = "board",
                name = "Portable board",
                backgroundColor = "#123456",
                sounds = listOf(ObfSound(id = "sound", path = "/recordings/hello.m4a"))
            )
        )
        source.sets.saveBoardSet(
            ObfBoardSet("set", "Screen", "board", listOf("board"), createdAt = 1, updatedAt = 1)
        )
        source.phrases.add(Phrase("phrase", "Need water", createdAt = 1, recordingPath = "/recordings/hello.m4a"))
        source.dictionary.add(PronunciationEntry("AAC", "A A C"))
        source.config.saveSpeechConfig(SpeechServiceConfig("westeurope", "restored-key"))
        val exportingMedia = TestMediaAccess(mapOf("/recordings/hello.m4a" to "audio-content".encodeToByteArray()))
        val exported = source.manager(filePicker = null, media = exportingMedia).exportBackup()

        val target = Repositories()
        target.boards.saveBoard(ObfBoard(format = "open-board-0.1", id = "old", name = "Old board"))
        val restoringMedia = TestMediaAccess()
        val restoringManager = target.manager(MapArchivePicker(readStoredZip(exported)), restoringMedia)
        assertEquals(0L, restoringManager.restoreRevision.value)
        val result = restoringManager.restoreBackup("backup.wingmate-backup")

        assertIs<BackupRestoreResult.Success>(result)
        assertEquals(1L, restoringManager.restoreRevision.value)
        assertEquals(listOf("board"), target.boards.listBoards().map { it.id })
        assertEquals("#123456", target.boards.getBoard("board")?.backgroundColor)
        assertEquals(listOf("Need water"), target.phrases.getAll().map { it.text })
        assertEquals(listOf("AAC"), target.dictionary.getAll().map { it.word })
        assertEquals("audio-content", restoringMedia.restored.single().second.decodeToString())
        assertContains(target.phrases.getAll().single().recordingPath.orEmpty(), "restored/")
        assertEquals(null, target.config.getSpeechConfig())
    }

    @Test
    fun backupRoundTripIncludesTheTypingScreenTemplateWithoutGeneratedVocabulary() = runBlocking {
        val source = Repositories()
        val seeded = TypingScreenUseCase(source.sets, source.boards).getOrCreate(columns = 4)
        val customized = seeded.rootBoard!!.let { board ->
            board.withPageElements(
                board.pageElements().mapIndexed { index, element -> element.copy(row = index + 10) }
            )
        }
        source.boards.saveBoard(customized)
        val exported = source.manager(filePicker = null, media = TestMediaAccess()).exportBackup()

        val target = Repositories()
        val result = target.manager(
            MapArchivePicker(readStoredZip(exported)),
            TestMediaAccess(),
        ).restoreBackup("typing.wingmate-backup")

        assertIs<BackupRestoreResult.Success>(result)
        val restoredSet = target.sets.listBoardSets().single()
        assertEquals(ScreenKind.Typing, restoredSet.kind)
        assertEquals(customized.pageElements(), target.boards.getBoard(restoredSet.rootBoardId)?.pageElements())
        assertEquals(customized.buttons.map { it.id }, target.boards.getBoard(restoredSet.rootBoardId)?.buttons?.map { it.id })
    }

    @Test
    fun corruptBackupLeavesExistingDataUntouched() = runBlocking {
        val repositories = Repositories()
        repositories.boards.saveBoard(ObfBoard(format = "open-board-0.1", id = "existing", name = "Existing"))
        val entries = mapOf(
            "manifest.json" to """{"format":"wingmate-backup","version":1,"createdAt":1,"payload":{"path":"data/user-data.json","size":2,"sha256":"wrong"},"media":[]}""".encodeToByteArray(),
            "data/user-data.json" to "{}".encodeToByteArray()
        )

        val result = repositories.manager(MapArchivePicker(entries), TestMediaAccess())
            .restoreBackup("corrupt.wingmate-backup")

        assertIs<BackupRestoreResult.Failure>(result)
        assertEquals(BackupFailureKind.Validation, result.kind)
        assertFalse(result.isRetryable)
        assertEquals(listOf("existing"), repositories.boards.listBoards().map { it.id })
    }

    private class Repositories {
        val boards = InMemoryBoardRepository()
        val sets = InMemoryBoardSetRepository()
        val phrases = InMemoryPhraseRepository()
        val categories = InMemoryCategoryRepository()
        val settings = InMemorySettingsRepository()
        val voices = InMemoryVoiceRepository()
        val history = InMemorySaidTextRepository()
        val dictionary = InMemoryPronunciationDictionaryRepository()
        val config = InMemoryConfigRepository()

        fun manager(filePicker: FilePicker?, media: BackupMediaAccess) = CompleteBackupManager(
            boards, sets, phrases, categories, settings, voices, history, dictionary, config, filePicker, media
        )
    }

    private class TestMediaAccess(
        private val source: Map<String, ByteArray> = emptyMap()
    ) : BackupMediaAccess {
        val restored = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun read(path: String): ByteArray? = source[path]
        override suspend fun restore(archiveName: String, bytes: ByteArray): String {
            restored += archiveName to bytes
            return "/restored/${archiveName.substringAfterLast('/')}"
        }
        override suspend fun deleteRestored(path: String) = Unit
    }

    private class MapArchivePicker(private val files: Map<String, ByteArray>) : FilePicker {
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override suspend fun readFileAsText(path: String): String? = null
        override suspend fun openArchive(path: String): ArchiveReader = object : ArchiveReader {
            override suspend fun entries() = files.map { (name, bytes) ->
                ArchiveEntry(name, bytes.size.toLong(), bytes.size.toLong())
            }
            override suspend fun readEntry(name: String, maxBytes: Long, onChunk: suspend (ByteArray) -> Unit) {
                val bytes = files[name] ?: throw ArchiveReadException(ArchiveReadError.ENTRY_NOT_FOUND, name)
                if (bytes.size > maxBytes) throw ArchiveReadException(ArchiveReadError.ENTRY_TOO_LARGE, name)
                onChunk(bytes)
            }
            override suspend fun close() = Unit
        }
    }

    private fun readStoredZip(zip: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var position = 0
        while (position + 30 <= zip.size && littleEndianInt(zip, position) == 0x04034b50) {
            val compressedSize = littleEndianInt(zip, position + 18)
            val nameLength = littleEndianShort(zip, position + 26)
            val extraLength = littleEndianShort(zip, position + 28)
            val dataStart = position + 30 + nameLength + extraLength
            val dataEnd = dataStart + compressedSize
            require(dataEnd <= zip.size)
            val name = zip.copyOfRange(position + 30, position + 30 + nameLength).decodeToString()
            files[name] = zip.copyOfRange(dataStart, dataEnd)
            position = dataEnd
        }
        return files
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or (littleEndianShort(bytes, offset + 2) shl 16)
}
