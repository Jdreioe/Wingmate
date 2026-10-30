package io.github.jdreioe.wingmate.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.decodeFromString
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCoreTest {
    @Test
    fun speechImportPreviewsWithoutSavingAndReopensWithWholeParagraphSpeechAndSilentNavigation() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-speech-${Random.nextLong()}"
        try {
            kotlinx.coroutines.runBlocking {
                DesktopStore(root.toString()).update(io.github.jdreioe.wingmate.domain.Settings(
                    speechPolicy = io.github.jdreioe.wingmate.domain.SpeechPolicy.SentenceOnly,
                ))
            }
            val core = DesktopCore(root.toString())
            val request = """{"name":"Min tale","text":"Hej. Jeg hedder Jonas.\n\n## Afslutning\nTak fordi I lyttede.","paragraphsPerPage":1,"previousLabel":"Forrige","nextLabel":"Næste"}"""
            val preview = Json.decodeFromString<List<io.github.jdreioe.wingmate.domain.obf.SpeechImportPage>>(core.previewSpeechJson(request))
            assertEquals(listOf("Min tale", "Afslutning"), preview.map { it.name })
            assertEquals("[]", core.libraryJson())
            val imported = Json.decodeFromString<DesktopActivation>(core.importSpeechJson(request))
            val paragraph = imported.view.cells.first { it.vocalization.startsWith("Hej.") }
            val spoken = Json.decodeFromString<DesktopActivation>(core.activateJson(paragraph.id))
            assertEquals("Hej. Jeg hedder Jonas.", spoken.speech)
            assertEquals("", spoken.view.message)
            assertEquals(imported.view.boardId, spoken.view.boardId)
            val next = spoken.view.cells.first { it.label == "Næste" }
            val navigated = Json.decodeFromString<DesktopActivation>(core.activateJson(next.id))
            assertEquals(null, navigated.speech)
            assertEquals("Afslutning", navigated.view.title)
            val reopened = DesktopCore(root.toString())
            val start = Json.decodeFromString<DesktopActivation>(reopened.openJson(imported.view.boardSetId))
            assertEquals(paragraph.vocalization, start.view.cells.first { it.id == paragraph.id }.vocalization)
            kotlinx.coroutines.runBlocking {
                assertEquals(io.github.jdreioe.wingmate.domain.SpeechPolicy.SentenceOnly, DesktopStore(root.toString()).get().speechPolicy)
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun importedButtonUsesSharedCompositionAndImmediateSpeechRules() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-desktop-${Random.nextLong()}"
        val board = root / "hello.obf"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            FileSystem.SYSTEM.write(board) { writeUtf8(OBF) }
            val core = DesktopCore(root.toString())

            val imported = Json.decodeFromString<DesktopActivation>(core.importFileJson(board.toString()))
            assertEquals("Hello", imported.view.title)

            val activated = Json.decodeFromString<DesktopActivation>(core.activateJson("hello"))
            assertEquals("Hello", activated.view.message)
            assertEquals("Hello", activated.speech)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun settingsAreClampedBeforeTheyArePersisted() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-settings-${Random.nextLong()}"
        try {
            val core = DesktopCore(root.toString())
            val value = core.updateSettingsJson("""{"theme":"dark","voice":"Ada","speechRate":9,"holdToSelectMillis":9999,"dwellToSelectMillis":9999,"selectionHighlightMillis":9999}""")
            assertTrue("\"speechRate\":2.0" in value)
            assertTrue("\"holdToSelectMillis\":2000" in value)
            assertTrue("\"dwellToSelectMillis\":5000" in value)
            assertEquals(2000, Json.decodeFromString<DesktopSettings>(DesktopCore(root.toString()).settingsJson()).selectionHighlightMillis)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun spokenTextUsesThePronunciationDictionary() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-dictionary-${Random.nextLong()}"
        val board = root / "hello.obf"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            FileSystem.SYSTEM.write(board) { writeUtf8(OBF) }
            val core = DesktopCore(root.toString())
            core.importFileJson(board.toString())
            core.addPronunciationJson("""{"word":"Hello","phoneme":"heh loh","alphabet":"text"}""")

            val activated = Json.decodeFromString<DesktopActivation>(core.activateJson("hello"))
            assertEquals("Hello", activated.view.message)
            assertEquals("heh loh", activated.speech)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun anyIcedThemeNameSurvivesAReopenAndCarriesDarkness() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-theme-${Random.nextLong()}"
        try {
            val stored = DesktopCore(root.toString()).updateSettingsJson(
                """{"theme":"Catppuccin Mocha","prefersDark":true,"voice":"Ada","speechRate":1.0,"holdToSelectMillis":0,"dwellToSelectMillis":0}"""
            )
            assertTrue("\"theme\":\"Catppuccin Mocha\"" in stored, stored)

            val reopened = DesktopCore(root.toString()).settingsJson()
            assertTrue("\"theme\":\"Catppuccin Mocha\"" in reopened, reopened)
            assertTrue("\"prefersDark\":true" in reopened, reopened)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun holdSwapsTheActiveAndHeldMessages() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-hold-${Random.nextLong()}"
        val board = root / "hello.obf"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            FileSystem.SYSTEM.write(board) { writeUtf8(OBF) }
            val core = DesktopCore(root.toString())
            core.importFileJson(board.toString())
            core.activateJson("hello")

            assertEquals("", Json.decodeFromString<DesktopActivation>(core.holdJson()).view.message)
            assertEquals("Hello", Json.decodeFromString<DesktopActivation>(core.holdJson()).view.message)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun versionOneBackupRestoresImportedScreens() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-backup-${Random.nextLong()}"
        val source = root / "source"
        val target = root / "target"
        val board = root / "hello.obf"
        val backup = root / "backup.wingmate-backup"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            FileSystem.SYSTEM.write(board) { writeUtf8(OBF) }
            DesktopCore(source.toString()).apply {
                importFileJson(board.toString())
                assertEquals("null", exportBackupJson(backup.toString()))
            }

            val restored = DesktopCore(target.toString())
            assertEquals("null", restored.restoreBackupJson(backup.toString()))
            val library = Json.decodeFromString<List<DesktopBoardSet>>(restored.libraryJson())
            assertEquals(listOf("Hello"), library.map { it.name })
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun categoriesLeftInStoredStateBecomeFolderPhrases() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-categories-${Random.nextLong()}"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            FileSystem.SYSTEM.write(root / "state.json") {
                writeUtf8("""{"phrases":[{"id":"water","text":"Water","createdAt":1}],"categories":[{"id":"food","name":"Food"}]}""")
            }

            val phrases = DesktopStore(root.toString()).snapshot().phrases
            assertEquals(listOf("water", "food"), phrases.map { it.id })
            assertEquals("food", phrases.last().linkedBoardId)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun missingFilesAndScreensReplyNotFoundWithoutNamingThem() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-missing-${Random.nextLong()}"
        try {
            val core = DesktopCore(root.toString())
            val missing = (root / "private-name.obf").toString()
            val imported = failureOf { core.importFileJson(missing) }
            assertEquals(DesktopFailureKind.NotFound, imported.kind)
            assertFalse("private-name" in imported.message)
            assertEquals(DesktopFailureKind.NotFound, failureOf { core.openJson("gone") }.kind)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun unreadableBackupIsAValidationFailure() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-bad-backup-${Random.nextLong()}"
        val backup = root / "bad.wingmate-backup"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            FileSystem.SYSTEM.write(backup) { writeUtf8("not a zip") }
            val failure = failureOf { DesktopCore((root / "data").toString()).restoreBackupJson(backup.toString()) }
            assertEquals(DesktopError(DesktopFailureKind.Validation, "Could not open backup", retryable = false), failure)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun corruptEntryInsideAValidBackupZipIsAValidationFailure() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-corrupt-backup-${Random.nextLong()}"
        val board = root / "hello.obf"
        val backup = root / "backup.wingmate-backup"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            FileSystem.SYSTEM.write(board) { writeUtf8(OBF) }
            DesktopCore((root / "source").toString()).apply {
                importFileJson(board.toString())
                exportBackupJson(backup.toString())
            }
            // Break the payload entry's local header; the central directory still opens.
            val bytes = FileSystem.SYSTEM.read(backup) { readByteArray() }
            val signature = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
            val headers = (0..bytes.size - signature.size).filter { start ->
                signature.indices.all { bytes[start + it] == signature[it] }
            }
            bytes[headers[1]] = 0
            FileSystem.SYSTEM.write(backup) { write(bytes) }

            val target = DesktopCore((root / "target").toString())
            assertEquals(
                DesktopError(DesktopFailureKind.Validation, "This backup is not valid", retryable = false),
                failureOf { target.restoreBackupJson(backup.toString()) },
            )
            assertEquals("[]", target.libraryJson())
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun replyEnvelopeKeepsCancellationAndHidesUnknownMessages() {
        assertEquals("{\"ok\":[1]}", desktopReply { "[1]" })
        assertEquals(DesktopFailureKind.Cancelled, failureOf { throw CancellationException("stop") }.kind)
        val unknown = failureOf { throw IllegalStateException("I need my medication") }
        assertEquals(DesktopFailureKind.Unexpected, unknown.kind)
        assertFalse("medication" in unknown.message)
    }

    /** Runs [operation] through the C ABI envelope and returns its typed error. */
    private fun failureOf(operation: () -> String): DesktopError =
        Json.decodeFromJsonElement<DesktopError>(Json.parseToJsonElement(desktopReply(operation)).jsonObject.getValue("error"))

    private companion object {
        const val OBF = """{
          "format":"open-board-0.1",
          "id":"root",
          "name":"Hello",
          "buttons":[{"id":"hello","label":"Hello","vocalization":"Hello"}],
          "grid":{"rows":1,"columns":1,"order":[["hello"]]}
        }"""
    }
}
