package io.github.jdreioe.wingmate.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopCoreTest {
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
            val value = core.updateSettingsJson("""{"theme":"dark","voice":"Ada","speechRate":9,"holdToSelectMillis":9999,"dwellToSelectMillis":9999}""")
            assertTrue("\"speechRate\":2.0" in value)
            assertTrue("\"holdToSelectMillis\":2000" in value)
            assertTrue("\"dwellToSelectMillis\":5000" in value)
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
                assertEquals("{\"ok\":true}", exportBackupJson(backup.toString()))
            }

            val restored = DesktopCore(target.toString())
            assertEquals("{\"ok\":true}", restored.restoreBackupJson(backup.toString()))
            val library = Json.decodeFromString<List<DesktopBoardSet>>(restored.libraryJson())
            assertEquals(listOf("Hello"), library.map { it.name })
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

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
