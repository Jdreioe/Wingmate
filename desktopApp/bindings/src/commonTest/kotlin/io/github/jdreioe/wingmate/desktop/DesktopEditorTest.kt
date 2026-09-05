package io.github.jdreioe.wingmate.desktop

import io.github.jdreioe.wingmate.domain.obf.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.*

class DesktopEditorTest {
    private fun fixture(block: (DesktopCore, String) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "wingmate-editor-${Random.nextLong()}"
        try { block(DesktopCore(root.toString()), root.toString()) }
        finally { FileSystem.SYSTEM.deleteRecursively(root, mustExist = false) }
    }
    private fun DesktopCore.command(value: String) = Json.parseToJsonElement(editorJson(value)).jsonObject
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test fun savePersistsLinkedPagesAndDiscardDoesNotChangeVocabulary() = fixture { core, path ->
        val start = core.command("""{"operation":"new","name":"My Screen","rows":2,"columns":2}""")
        val rootId = start.string("pageId")
        val page = core.command("""{"operation":"addPage","name":"Food"}""").string("pageId")
        core.command("""{"operation":"page","id":"$rootId"}""")
        core.command("""{"operation":"button","label":"Food","linkedPage":"$page"}""")
        val setId = core.command("""{"operation":"save"}""").string("id")
        val reopened = DesktopCore(path)
        val view = reopened.command("""{"operation":"begin","id":"$setId"}""")
        assertEquals("My Screen", view.string("screenName"))
        assertEquals(2, view.getValue("pages").jsonArray.size)
        assertEquals(page, view.getValue("cells").jsonArray.first().jsonObject.string("linkedPage"))
        reopened.command("""{"operation":"renameScreen","name":"Discard me"}""")
        reopened.command("""{"operation":"discard"}""")
        assertTrue("My Screen" in DesktopCore(path).libraryJson())
        assertFalse("Discard me" in DesktopCore(path).libraryJson())
    }

    @Test fun invalidMovesLinksAndSpansLeaveDraftIntact() = fixture { core, _ ->
        core.command("""{"operation":"new","rows":2,"columns":2}""")
        core.command("""{"operation":"button","label":"Hello"}""")
        assertFailsWith<IllegalArgumentException> { core.command("""{"operation":"button","linkedPage":"missing"}""") }
        assertFailsWith<IllegalArgumentException> { core.command("""{"operation":"span","rowSpan":3}""") }
        assertFailsWith<IllegalArgumentException> { core.command("""{"operation":"move","toRow":99}""") }
        val moved = core.command("""{"operation":"move","toRow":1,"toColumn":1}""")
        val button = moved.getValue("cells").jsonArray.map { it.jsonObject }.single { it.string("label") == "Hello" }
        assertEquals("1", button.string("row"))
        assertEquals("1", button.string("column"))
        val cleared = core.command("""{"operation":"clear","row":1,"column":1}""")
        assertTrue(cleared.getValue("cells").jsonArray.all { !it.jsonObject.getValue("occupied").jsonPrimitive.boolean })
    }

    @Test fun editingLabelPreservesImportedMediaActionsAndExtensions() = fixture { core, path ->
        val state = DesktopStore(path)
        val page = ObfBoard("open-board-0.1", "page", buttons = listOf(ObfButton(id = "button", label = "Before", imageId = "image", soundId = "sound", action = ":speak")),
            images = listOf(ObfImage(id = "image", data = "aGVsbG8=")),
            sounds = listOf(ObfSound(id = "sound", data = "aGVsbG8=")),
            grid = ObfGrid(1, 1, listOf(listOf("button"))), extensions = mapOf("ext_custom" to JsonPrimitive("keep")))
        state.restore(state.snapshot().copy(boards = listOf(page), boardSets = listOf(ObfBoardSet("screen", "Screen", "page", listOf("page"), createdAt = 1, updatedAt = 1))))
        val editor = DesktopCore(path)
        editor.command("""{"operation":"begin","id":"screen"}""")
        editor.command("""{"operation":"button","label":"After"}""")
        editor.command("""{"operation":"save"}""")
        val stored = DesktopStore(path).snapshot().boards.single()
        assertEquals(page.images, stored.images)
        assertEquals(page.sounds, stored.sounds)
        assertEquals(page.extensions, stored.extensions)
        assertEquals(page.buttons.single().copy(label = "After"), stored.buttons.single())
    }

    @Test fun lockedAndSystemScreensCannotBeEdited() = fixture { _, path ->
        for (set in listOf(
            ObfBoardSet("screen", "Locked", "page", listOf("page"), isLocked = true, createdAt = 1, updatedAt = 1),
            ObfBoardSet("screen", "Typing", "page", listOf("page"), kind = ScreenKind.Typing, createdAt = 1, updatedAt = 1),
        )) {
            val store = DesktopStore(path)
            store.restore(store.snapshot().copy(boardSets = listOf(set)))
            assertFailsWith<IllegalArgumentException> { DesktopCore(path).command("""{"operation":"begin","id":"screen"}""") }
        }
    }

    @Test fun abandoningNewScreenDoesNotPersistIt() = fixture { core, path ->
        core.command("""{"operation":"new"}""")
        assertFailsWith<IllegalStateException> { core.command("""{"operation":"new"}""") }
        core.command("""{"operation":"discard"}""")
        assertEquals("[]", DesktopCore(path).libraryJson())
    }
}
