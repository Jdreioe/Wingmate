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
    fun messageBarButtonsFallBackToTheSpeakSettingUntilAScreenChoosesThem() {
        assertEquals(listOf(MessageBarAction.Speak), resolveBoardSettings(true, true, false).messageBarActions)
        val noSpeak = resolveBoardSettings(true, true, false, appShowSpeakButton = false)
        assertEquals(emptyList(), noSpeak.messageBarActions)

        val screen = BoardSettingsOverrides(
            messageBarActions = listOf(MessageBarAction.Hold, MessageBarAction.Clear, MessageBarAction.Hold),
        )
        val chosen = resolveBoardSettings(true, true, false, screen = screen)
        assertEquals(listOf(MessageBarAction.Hold, MessageBarAction.Clear), chosen.messageBarActions)
        // Speak was left out, so clients that only know the speak toggle hide it too.
        assertFalse(chosen.showSpeakButton)
        assertEquals(screen, decodeBoardSettings(encodeBoardSettings(screen)))
    }

    @Test
    fun speechPreloadingDefaultsOffAndPersistsAnExplicitPageOptOut() {
        val defaults = resolveBoardSettings(true, true, false)
        assertFalse(defaults.cacheSpeech)
        val screen = BoardSettingsOverrides(cacheSpeech = true)
        assertTrue(resolveBoardSettings(true, true, false, screen = screen).cacheSpeech)
        val page = BoardSettingsOverrides(cacheSpeech = false)
        assertFalse(page.isEmpty)
        val restored = decodeBoardSettings(encodeBoardSettings(page))
        assertEquals(page, restored)
        assertFalse(resolveBoardSettings(true, true, false, screen = screen, page = restored!!).cacheSpeech)
    }

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
    fun messageBarEditableDefaultsToTrue() {
        val resolved = resolveBoardSettings(appShowLabels = true, appShowSymbols = true, appLabelAtTop = false)
        assertTrue(resolved.messageBarEditable)
    }

    @Test
    fun messageBarEditablePageOverridesScreenOverridesApp() {
        val fromApp = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            appMessageBarEditable = false
        )
        assertFalse(fromApp.messageBarEditable)

        val fromScreen = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            appMessageBarEditable = false,
            screen = BoardSettingsOverrides(messageBarEditable = true)
        )
        assertTrue(fromScreen.messageBarEditable)

        val fromPage = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            screen = BoardSettingsOverrides(messageBarEditable = false),
            page = BoardSettingsOverrides(messageBarEditable = true)
        )
        assertTrue(fromPage.messageBarEditable)
    }

    @Test
    fun speakButtonOverridesPageScreenApp() {
        val screenOnly = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            appShowSpeakButton = true,
            screen = BoardSettingsOverrides(showSpeakButton = false)
        )
        assertFalse(screenOnly.showSpeakButton)

        val pageWins = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            appShowSpeakButton = true,
            screen = BoardSettingsOverrides(showSpeakButton = false),
            page = BoardSettingsOverrides(showSpeakButton = true)
        )
        assertTrue(pageWins.showSpeakButton)

        val appDefault = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = true,
            appLabelAtTop = false,
            appShowSpeakButton = false
        )
        assertFalse(appDefault.showSpeakButton)
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

    @Test
    fun keyguardSpacingInheritsPageOverScreenClampsBoundsAndPreservesZero() {
        val defaults = resolveBoardSettings(appShowLabels = true, appShowSymbols = true, appLabelAtTop = false)
        assertNull(defaults.cellGapDp)
        assertNull(defaults.outerMarginDp)

        val screen = BoardSettingsOverrides(cellGapDp = 12, outerMarginDp = 24)
        assertFalse(screen.isEmpty)
        val fromScreen = resolveBoardSettings(true, true, false, screen = screen)
        assertEquals(12, fromScreen.cellGapDp)
        assertEquals(24, fromScreen.outerMarginDp)

        // Explicit 0 dp on a Page overrides a non-zero Screen value.
        val pageZero = BoardSettingsOverrides(cellGapDp = 0, outerMarginDp = 0)
        assertFalse(pageZero.isEmpty)
        val fromPage = resolveBoardSettings(true, true, false, screen = screen, page = pageZero)
        assertEquals(0, fromPage.cellGapDp)
        assertEquals(0, fromPage.outerMarginDp)

        // Out-of-range imported values clamp cleanly.
        val outOfRange = BoardSettingsOverrides(cellGapDp = 999, outerMarginDp = -10)
        val clamped = resolveBoardSettings(true, true, false, page = outOfRange)
        assertEquals(MAX_KEYGUARD_CELL_GAP_DP, clamped.cellGapDp)
        assertEquals(0, clamped.outerMarginDp)

        assertEquals(screen, decodeBoardSettings(encodeBoardSettings(screen)))
        assertEquals(pageZero, decodeBoardSettings(encodeBoardSettings(pageZero)))
    }
}
