import io.github.jdreioe.wingmate.application.GridsetExportErrorCode
import io.github.jdreioe.wingmate.application.GridsetExportResult
import io.github.jdreioe.wingmate.application.GridsetExporter
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.WordType
import io.github.jdreioe.wingmate.domain.obf.withPageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.withWordType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridsetExporterTest {

    private val exporter = GridsetExporter(guidGenerator = { "11111111-2222-4333-8444-555555555555" })

    // 1x1 PNG bytes (base64 matches tools/test_obz_to_gridset.py)
    private val pngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVQI12NguPABAAKUAcEDPWywAAAAAElFTkSuQmCC"
    private val pngHeaderBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
    )
    private val wavBytes = "RIFF\u0024\u0000\u0000\u0000WAVEfmt ".encodeToByteArray()

    @Test
    fun exportsLayoutCommandsLinksColorsAndMediaForGrid3() = runBlocking {
        val root = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            name = "Mål & ord",
            locale = "da-DK",
            strings = mapOf("da" to mapOf("label-key" to "Hej & velkommen")),
            grid = ObfGrid(
                rows = 2,
                columns = 3,
                order = listOf(
                    listOf("1", "1", "2"),
                    listOf("3", null, "4"),
                ),
            ),
            buttons = listOf(
                ObfButton(
                    id = "1",
                    label = "label-key",
                    vocalization = "Goddag",
                    imageId = "picture",
                    soundId = "voice",
                    backgroundColor = "#6de",
                ),
                ObfButton(
                    id = "2",
                    label = "Flere ord",
                    loadBoard = ObfLoadBoard(id = "more"),
                ),
                ObfButton(id = "3", label = "Hidden", hidden = true),
                ObfButton(id = "4", label = "Ryd", actions = listOf(":clear", ":speak")),
            ),
            images = listOf(
                ObfImage(id = "picture", dataUrl = "data:image/png;base64,$pngBase64"),
            ),
            sounds = listOf(
                ObfSound(id = "voice", path = "sounds/voice.wav", contentType = "audio/wav"),
            ),
        )

        val more = ObfBoard(
            format = "open-board-0.1",
            id = "more",
            name = "mål & ord",
            grid = ObfGrid(rows = 1, columns = 3, order = listOf(listOf("home", "missing", "speak"))),
            buttons = listOf(
                ObfButton(id = "home", label = "Hjem", action = ":home"),
                ObfButton(id = "missing", label = "Missing", imageId = "remote"),
                ObfButton(id = "speak", label = "Say this", vocalization = "Whole phrase"),
            ),
            images = listOf(
                ObfImage(id = "remote", url = "https://invalid.example/image.png"),
            ),
        ).withPageSettingsOverrides(
            BoardSettingsOverrides(
                showMessageBar = false,
                activationBehavior = BoardActivationBehavior.SpeakOnly,
                returnBehavior = BoardReturnBehavior.StartPage,
            )
        )

        val result = assertIs<GridsetExportResult.Success>(
            exporter.exportResult(
                boards = listOf(root, more),
                rootBoardId = "home",
                screenSettings = BoardSettingsOverrides(
                    returnBehavior = BoardReturnBehavior.Stay,
                    showMessageBar = true,
                ),
                loadMedia = { path -> if (path == "sounds/voice.wav") wavBytes else null },
            )
        )

        assertEquals(2, result.pageCount)
        assertEquals(mapOf("unavailable image" to 1), result.warnings)

        val settingsXml = assertNotNull(extractEntry(result.bytes, "Settings0/settings.xml")).decodeToString()
        assertTrue(settingsXml.contains("<PrimaryLanguage>da-DK</PrimaryLanguage>"))
        assertTrue(settingsXml.contains("<StartGrid>Mål &amp; ord</StartGrid>"))
        assertTrue(settingsXml.contains("<GridSetFileFormatVersion>1</GridSetFileFormatVersion>"))

        val rootXml = assertNotNull(extractEntry(result.bytes, "Grids/Mål & ord/grid.xml")).decodeToString()
        assertTrue(rootXml.contains("<ContentType>Workspace</ContentType><ContentSubType>Chat</ContentSubType>"))
        assertTrue(rootXml.contains("<Cell X=\"0\" Y=\"1\" RowSpan=\"1\" ColumnSpan=\"2\">"))
        assertTrue(rootXml.contains("<Caption>Hej &amp; velkommen</Caption>"))
        assertTrue(rootXml.contains("<BackColour>#66DDEEFF</BackColour>"))
        assertTrue(rootXml.contains("<Command ID=\"SpeechPlaySound\"><Parameter Key=\"filedata\"><data>.wav</data></Parameter></Command>"))
        assertTrue(rootXml.contains("<Command ID=\"Action.InsertText\"><Parameter Key=\"text\"><p><s><r xml:space=\"preserve\">Goddag</r></s><s><r xml:space=\"preserve\"> </r></s></p></Parameter></Command>"))
        assertTrue(rootXml.contains("<Image>.png</Image>"))
        assertTrue(rootXml.contains("<Command ID=\"Jump.To\"><Parameter Key=\"grid\">mål &amp; ord (2)</Parameter></Command>"))
        assertTrue(rootXml.contains("<Command ID=\"Action.Clear\" /><Command ID=\"Action.Speak\" />"))
        assertFalse(rootXml.contains("Hidden"))

        val soundAsset = assertNotNull(extractEntry(result.bytes, "Grids/Mål & ord/0-1-0-filedata.wav"))
        assertTrue(soundAsset.contentEquals(wavBytes))
        assertNotNull(extractEntry(result.bytes, "Grids/Mål & ord/0-1.png"))

        val moreXml = assertNotNull(extractEntry(result.bytes, "Grids/mål & ord (2)/grid.xml")).decodeToString()
        assertFalse(moreXml.contains("<ContentSubType>Chat</ContentSubType>"))
        assertTrue(moreXml.contains("<Command ID=\"Jump.Home\" />"))
        assertTrue(moreXml.contains("<Command ID=\"Speech.SpeakNow\"><Parameter Key=\"text\" xml:space=\"preserve\">Whole phrase</Parameter></Command>"))
        assertFalse(moreXml.contains("Action.InsertText"))

        val fileMapXml = assertNotNull(extractEntry(result.bytes, "FileMap.xml")).decodeToString()
        assertTrue(fileMapXml.contains("StaticFile=\"Grids\\Mål &amp; ord\\grid.xml\""))
        assertTrue(fileMapXml.contains("<File>Grids\\Mål &amp; ord\\0-1-0-filedata.wav</File>"))
        assertTrue(fileMapXml.contains("<File>Grids\\Mål &amp; ord\\0-1.png</File>"))
    }

    @Test
    fun loadsRemoteSymbolImageAndAppliesFitzgeraldWordTypeColors() = runBlocking {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "b1",
            name = "CON",
            grid = ObfGrid(rows = 1, columns = 2, order = listOf(listOf("word", "pred"))),
            buttons = listOf(
                ObfButton(id = "word", label = "play", imageId = "sym1").withWordType(WordType.Verb),
                ObfButton(id = "pred", label = "guess").withType(ObfButtonType.NGramPrediction),
            ),
            images = listOf(
                ObfImage(id = "sym1", url = "https://symbols.example/asset/123"),
            ),
        )

        val result = assertIs<GridsetExportResult.Success>(
            exporter.exportResult(
                boards = listOf(board),
                rootBoardId = "b1",
                appSettings = Settings(wordTypeColorScheme = WordTypeColorScheme.Fitzgerald),
                loadUrl = { url -> if (url == "https://symbols.example/asset/123") pngHeaderBytes else null },
            )
        )

        assertEquals(mapOf("Wingmate prediction button" to 1), result.warnings)
        val gridXml = assertNotNull(extractEntry(result.bytes, "Grids/_CON/grid.xml")).decodeToString()
        assertTrue(gridXml.contains("<Image>.png</Image>"))
        assertTrue(gridXml.contains("<BackColour>#F48FB1FF</BackColour>"))
        assertTrue(assertNotNull(extractEntry(result.bytes, "Grids/_CON/0-1.png")).contentEquals(pngHeaderBytes))
    }

    @Test
    fun spellingModeUsesDeleteLetterAndOmitsTrailingSpace() = runBlocking {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "keys",
            name = "Keys",
            grid = ObfGrid(rows = 1, columns = 2, order = listOf(listOf("a", "del"))),
            buttons = listOf(
                ObfButton(id = "a", label = "a", action = "+a"),
                ObfButton(id = "del", label = "Del", action = ":backspace"),
            ),
        ).withSpellingMode(true)

        val result = assertIs<GridsetExportResult.Success>(
            exporter.exportResult(boards = listOf(board), rootBoardId = "keys")
        )
        val gridXml = assertNotNull(extractEntry(result.bytes, "Grids/Keys/grid.xml")).decodeToString()
        assertTrue(gridXml.contains("<p><s><r xml:space=\"preserve\">a</r></s></p>"))
        assertTrue(gridXml.contains("<Command ID=\"Action.DeleteLetter\" />"))
    }

    @Test
    fun rejectsMissingRootDuplicateIdsAndMissingGrid() = runBlocking {
        val valid = ObfBoard(
            format = "open-board-0.1",
            id = "b1",
            name = "Home",
            grid = ObfGrid(rows = 1, columns = 1, order = listOf(listOf(null))),
        )

        val missingRoot = assertIs<GridsetExportResult.Failure>(
            exporter.exportResult(listOf(valid), "other")
        )
        assertEquals(GridsetExportErrorCode.ROOT_NOT_FOUND, missingRoot.code)

        val duplicate = assertIs<GridsetExportResult.Failure>(
            exporter.exportResult(listOf(valid, valid), "b1")
        )
        assertEquals(GridsetExportErrorCode.DUPLICATE_ID, duplicate.code)

        val noGrid = assertIs<GridsetExportResult.Failure>(
            exporter.exportResult(listOf(valid.copy(grid = null)), "b1")
        )
        assertEquals(GridsetExportErrorCode.INVALID_GRID, noGrid.code)
    }

    @Test
    fun parsesColorsAndSanitizesFilenames() {
        assertEquals("#66DDEEFF", GridsetExporter.parseGridColor("#6de"))
        assertEquals("#11223344", GridsetExporter.parseGridColor("#1234"))
        assertEquals("#AABBCCFF", GridsetExporter.parseGridColor("#aabbcc"))
        assertEquals("#FF008080", GridsetExporter.parseGridColor("rgba(255, 0, 128, 0.5)"))
        assertNull(GridsetExporter.parseGridColor("rgb(300, 0, 0)"))
        assertNull(GridsetExporter.parseGridColor("not-a-color"))

        assertEquals("Mål & ord.gridset", GridsetExporter.sanitizeExportFileName("Mål & ord", "gridset"))
        assertEquals("Food _ Drinks.gridset", GridsetExporter.sanitizeExportFileName("Food / Drinks", "gridset"))
        assertEquals("_CON.gridset", GridsetExporter.sanitizeExportFileName("CON", "gridset"))
    }

    private fun extractEntry(zip: ByteArray, entryName: String): ByteArray? {
        var pos = 0
        while (pos < zip.size - 4) {
            if (zip[pos] == 0x50.toByte() && zip[pos + 1] == 0x4B.toByte() &&
                zip[pos + 2] == 0x03.toByte() && zip[pos + 3] == 0x04.toByte()
            ) {
                val nameLen = ((zip[pos + 27].toInt() and 0xFF) shl 8) or (zip[pos + 26].toInt() and 0xFF)
                val extraLen = ((zip[pos + 29].toInt() and 0xFF) shl 8) or (zip[pos + 28].toInt() and 0xFF)
                val compSize = ((zip[pos + 21].toInt() and 0xFF) shl 24) or
                    ((zip[pos + 20].toInt() and 0xFF) shl 16) or
                    ((zip[pos + 19].toInt() and 0xFF) shl 8) or
                    (zip[pos + 18].toInt() and 0xFF)
                val headerSize = 30 + nameLen + extraLen
                val extractedName = zip.copyOfRange(pos + 30, pos + 30 + nameLen).decodeToString()
                if (extractedName == entryName) {
                    return zip.copyOfRange(pos + headerSize, pos + headerSize + compSize)
                }
                pos += headerSize + compSize
            } else {
                pos++
            }
        }
        return null
    }
}
