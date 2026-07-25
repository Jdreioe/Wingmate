package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardSettingsTest {

    @Test
    fun pageOverridesScreenWhichOverridesAppDefaults() {
        val resolved = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            screen = BoardSettingsOverrides(
                showLabels = false,
                labelAtTop = true,
                activationBehavior = BoardActivationBehavior.AddOnly
            ),
            page = BoardSettingsOverrides(
                showLabels = true,
                showMessageBar = false,
                returnBehavior = BoardReturnBehavior.Previous
            )
        )

        assertTrue(resolved.showLabels)
        assertTrue(resolved.showSymbols)
        assertTrue(resolved.labelAtTop)
        assertFalse(resolved.showMessageBar)
        assertEquals(BoardActivationBehavior.AddOnly, resolved.activationBehavior)
        assertEquals(BoardReturnBehavior.Previous, resolved.returnBehavior)
    }

    @Test
    fun emptyOverridesKeepCurrentDefaults() {
        val resolved = resolveBoardSettings(
            appShowLabels = false,
            appShowSymbols = true,
            appLabelAtTop = true
        )

        assertFalse(resolved.showLabels)
        assertTrue(resolved.showSymbols)
        assertTrue(resolved.labelAtTop)
        assertTrue(resolved.showMessageBar)
        assertEquals(BoardActivationBehavior.SpeakAndAdd, resolved.activationBehavior)
        assertEquals(BoardReturnBehavior.Stay, resolved.returnBehavior)
    }

    @Test
    fun screenAndPageInheritConfiguredGlobalCommunicationDefaults() {
        val resolved = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            appShowMessageBar = false,
            appActivationBehavior = BoardActivationBehavior.SpeakOnly,
            appReturnBehavior = BoardReturnBehavior.StartPage
        )

        assertFalse(resolved.showMessageBar)
        assertEquals(BoardActivationBehavior.SpeakOnly, resolved.activationBehavior)
        assertEquals(BoardReturnBehavior.StartPage, resolved.returnBehavior)
    }

    @Test
    fun unusableImportedPresentationStillShowsALabel() {
        val resolved = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            page = BoardSettingsOverrides(showLabels = false, showSymbols = false)
        )

        assertTrue(resolved.showLabels)
        assertFalse(resolved.showSymbols)
    }

    @Test
    fun pageExtensionRoundTripsAndResetPreservesOtherExtensions() {
        val original = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            extensions = mapOf("ext_other" to JsonPrimitive("keep"))
        )
        val overrides = BoardSettingsOverrides(
            showSymbols = false,
            activationBehavior = BoardActivationBehavior.SpeakOnly
        )

        val updated = original.withPageSettingsOverrides(overrides)
        assertEquals(overrides, updated.pageSettingsOverrides())
        assertEquals("keep", (updated.extensions["ext_other"] as JsonPrimitive).content)

        val reset = updated.withPageSettingsOverrides(BoardSettingsOverrides())
        assertNull(reset.extensions[OBF_PAGE_SETTINGS_EXTENSION])
        assertEquals("keep", (reset.extensions["ext_other"] as JsonPrimitive).content)
    }

    @Test
    fun malformedPageExtensionFallsBackWithoutRemovingRawData() {
        val malformed = JsonObject(
            mapOf(
                "showLabels" to JsonPrimitive(false),
                "activationBehavior" to JsonPrimitive("future-mode")
            )
        )
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            extensions = mapOf(OBF_PAGE_SETTINGS_EXTENSION to malformed)
        )

        assertEquals(false, board.pageSettingsOverrides().showLabels)
        assertNull(board.pageSettingsOverrides().activationBehavior)
        assertEquals(malformed, board.extensions[OBF_PAGE_SETTINGS_EXTENSION])
    }

    @Test
    fun olderBoardSetJsonGetsEmptyScreenSettings() {
        val json = Json { ignoreUnknownKeys = true }
        val oldJson = """
            {
              "id": "set",
              "name": "Daily",
              "rootBoardId": "home",
              "boardIds": ["home"],
              "createdAt": 1,
              "updatedAt": 2
            }
        """.trimIndent()

        val boardSet = json.decodeFromString(ObfBoardSet.serializer(), oldJson)

        assertTrue(boardSet.screenSettings.isEmpty)
    }
}
