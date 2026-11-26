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

    @Test
    fun effectiveKeyguardSpacingPreservesTabletSpacingAndPreventsZeroWidthCellsOnNarrowViewports() {
        // Wide tablet viewport keeps the exact configured spacing.
        val tablet = effectiveKeyguardSpacing(
            viewportWidthDp = 1024.0,
            viewportHeightDp = 768.0,
            columns = 4,
            rows = 3,
            desiredHorizontalGapDp = 24.0,
            desiredVerticalGapDp = 24.0,
            desiredOuterMarginDp = 32.0,
        )
        assertEquals(24.0, tablet.horizontalGapDp)
        assertEquals(24.0, tablet.verticalGapDp)
        assertEquals(32.0, tablet.outerMarginDp)

        // Narrow phone viewport with max keyguard spacing and 7 columns clamps so cells remain >= 24dp.
        val phone = effectiveKeyguardSpacing(
            viewportWidthDp = 390.0,
            viewportHeightDp = 600.0,
            columns = 7,
            rows = 4,
            desiredHorizontalGapDp = MAX_KEYGUARD_CELL_GAP_DP.toDouble(),
            desiredVerticalGapDp = MAX_KEYGUARD_CELL_GAP_DP.toDouble(),
            desiredOuterMarginDp = MAX_KEYGUARD_OUTER_MARGIN_DP.toDouble(),
        )
        val cellWidth = (390.0 - phone.outerMarginDp * 2.0 - phone.horizontalGapDp * 6.0) / 7.0
        val cellHeight = (600.0 - phone.outerMarginDp * 2.0 - phone.verticalGapDp * 3.0) / 4.0
        assertTrue(cellWidth >= MIN_KEYGUARD_CELL_SIZE_DP - 0.001, "Expected cellWidth >= 24, got $cellWidth")
        assertTrue(cellHeight >= MIN_KEYGUARD_CELL_SIZE_DP - 0.001, "Expected cellHeight >= 24, got $cellHeight")

        // Extremely tight viewport falls back to zero spacing instead of negative values.
        val tiny = effectiveKeyguardSpacing(
            viewportWidthDp = 120.0,
            viewportHeightDp = 100.0,
            columns = 8,
            rows = 6,
            desiredHorizontalGapDp = 48.0,
            desiredVerticalGapDp = 48.0,
            desiredOuterMarginDp = 64.0,
        )
        assertEquals(0.0, tiny.outerMarginDp)
        assertEquals(0.0, tiny.horizontalGapDp)
        assertEquals(0.0, tiny.verticalGapDp)

        // Saved gridHeightFraction (50% of 400dp with 6 rows and 48dp gap) clamps vertical gap against final height.
        val halfHeight = effectiveKeyguardSpacing(
            viewportWidthDp = 400.0,
            viewportHeightDp = 400.0,
            columns = 4,
            rows = 6,
            desiredHorizontalGapDp = 48.0,
            desiredVerticalGapDp = 48.0,
            desiredOuterMarginDp = 8.0,
            heightFraction = 0.5,
        )
        val halfContentHeight = (400.0 - halfHeight.outerMarginDp * 2.0) * 0.5
        val halfCellHeight = (halfContentHeight - halfHeight.verticalGapDp * 5.0) / 6.0
        assertTrue(halfCellHeight >= MIN_KEYGUARD_CELL_SIZE_DP - 0.001, "Expected cellHeight >= 24, got $halfCellHeight")

        // Scrollable Phrase trays (constrainHeight = false) preserve default margin and vertical gaps with many rows.
        val scrollable = effectiveKeyguardSpacing(
            viewportWidthDp = 390.0,
            viewportHeightDp = 260.0,
            columns = 3,
            rows = 12,
            desiredHorizontalGapDp = 4.0,
            desiredVerticalGapDp = 8.0,
            desiredOuterMarginDp = 8.0,
            constrainHeight = false,
        )
        assertEquals(8.0, scrollable.outerMarginDp)
        assertEquals(8.0, scrollable.verticalGapDp)
        assertEquals(4.0, scrollable.horizontalGapDp)

        // Small Phrase trays fit without scrolling; large Phrase libraries stay scrollable.
        assertTrue(rowsFitWithoutScrolling(availableHeightDp = 200.0, rows = 3, verticalGapDp = 8.0, minimumRowHeightDp = 44.0))
        assertFalse(rowsFitWithoutScrolling(availableHeightDp = 200.0, rows = 30, verticalGapDp = 8.0, minimumRowHeightDp = 44.0))
    }
}
