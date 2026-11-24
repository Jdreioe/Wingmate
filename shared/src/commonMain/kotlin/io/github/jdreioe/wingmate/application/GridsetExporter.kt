package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfMediaSource
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.ResolvedBoardSettings
import io.github.jdreioe.wingmate.domain.obf.ZipBuilder
import io.github.jdreioe.wingmate.domain.obf.backgroundImage
import io.github.jdreioe.wingmate.domain.obf.fieldItems
import io.github.jdreioe.wingmate.domain.obf.obfImageSources
import io.github.jdreioe.wingmate.domain.obf.obfSoundSources
import io.github.jdreioe.wingmate.domain.obf.pageElements
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.obf.resolvedBackgroundColor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.random.Random

sealed interface GridsetExportResult {
    data class Success(
        val bytes: ByteArray,
        val pageCount: Int,
        val warnings: Map<String, Int> = emptyMap(),
    ) : GridsetExportResult

    data class Failure(
        val code: GridsetExportErrorCode,
        val context: String,
    ) : GridsetExportResult
}

enum class GridsetExportErrorCode {
    ROOT_NOT_FOUND,
    DUPLICATE_ID,
    INVALID_GRID,
    ZIP_FAILED,
}

/**
 * Packages a Wingmate Screen directly into a Smartbox Grid 3 `.gridset` archive.
 *
 * Keeps conversion local-first: embedded and stored media are always included, and
 * remote symbol URLs are fetched only when a [loadUrl] callback is supplied. Media
 * that is unreachable or uses a format Grid 3 does not accept is skipped with a
 * warning rather than failing the whole Screen export.
 */
class GridsetExporter(
    private val guidGenerator: () -> String = ::randomUuidV4,
) {
    suspend fun exportResult(
        boards: List<ObfBoard>,
        rootBoardId: String,
        screenSettings: BoardSettingsOverrides = BoardSettingsOverrides(),
        appSettings: Settings? = null,
        loadMedia: suspend (path: String) -> ByteArray? = { null },
        loadUrl: (suspend (url: String) -> ByteArray?)? = null,
    ): GridsetExportResult {
        val rootBoard = boards.firstOrNull { it.id == rootBoardId }
            ?: return GridsetExportResult.Failure(
                GridsetExportErrorCode.ROOT_NOT_FOUND,
                "Root board '$rootBoardId' was not found",
            )

        if (boards.map { it.id }.distinct().size != boards.size) {
            return GridsetExportResult.Failure(
                GridsetExportErrorCode.DUPLICATE_ID,
                "Screen contains duplicate page IDs",
            )
        }

        val orderedBoards = buildList {
            add(rootBoard)
            boards.forEach { if (it.id != rootBoardId) add(it) }
        }
        val defaultLocale = appSettings?.primaryLanguage?.takeIf { it.isNotBlank() }
        val gridNames = assignGridNames(orderedBoards, defaultLocale)
        val boardIds = orderedBoards.map { it.id }.toSet()

        val entries = mutableListOf<Pair<String, ByteArray>>()
        val fileMapEntries = mutableListOf<Pair<String, List<String>>>()
        val warnings = mutableMapOf<String, Int>()

        val primaryLanguage = rootBoard.locale?.takeIf { it.isNotBlank() } ?: defaultLocale
        val settingsXml = buildSettingsXml(
            primaryLanguage = primaryLanguage,
            startGrid = gridNames.getValue(rootBoard.id),
        )
        entries += SETTINGS_PATH to settingsXml.encodeToByteArray()

        for (board in orderedBoards) {
            val grid = board.grid
            if (grid == null || grid.rows < 1 || grid.columns < 1 || grid.rows * grid.columns > MAX_GRID_CELLS) {
                return GridsetExportResult.Failure(
                    GridsetExportErrorCode.INVALID_GRID,
                    "Page '${board.id}' requires a valid grid layout",
                )
            }
            val buttonMap = mutableMapOf<String, ObfButton>()
            for (button in board.buttons) {
                if (buttonMap.put(button.id, button) != null) {
                    return GridsetExportResult.Failure(
                        GridsetExportErrorCode.DUPLICATE_ID,
                        "Page '${board.id}' contains duplicate button IDs",
                    )
                }
            }

            val resolved = resolveEffectiveSettings(appSettings, screenSettings, board.pageSettingsOverrides())
            val gridName = gridNames.getValue(board.id)
            val folder = "Grids/$gridName"
            val assets = mutableListOf<String>()
            val wordTypeScheme = appSettings?.wordTypeColorScheme ?: WordTypeColorScheme.None

            val gridXml = renderGridXml(
                board = board,
                gridName = gridName,
                folder = folder,
                buttons = buttonMap,
                settings = resolved,
                defaultLocale = defaultLocale,
                wordTypeScheme = wordTypeScheme,
                resolveLinkTarget = { link -> resolveTargetGridName(link, boardIds, gridNames) },
                loadMedia = loadMedia,
                loadUrl = loadUrl,
                entries = entries,
                assets = assets,
                warnings = warnings,
            )

            val gridPath = "$folder/grid.xml"
            entries += gridPath to gridXml.encodeToByteArray()
            if (assets.isNotEmpty()) {
                fileMapEntries += gridPath to assets
            }
        }

        val fileMapXml = buildFileMapXml(fileMapEntries)
        entries += "FileMap.xml" to fileMapXml.encodeToByteArray()

        return ZipBuilder.build(entries).fold(
            onSuccess = { bytes ->
                GridsetExportResult.Success(
                    bytes = bytes,
                    pageCount = orderedBoards.size,
                    warnings = warnings.toMap(),
                )
            },
            onFailure = { error ->
                GridsetExportResult.Failure(
                    GridsetExportErrorCode.ZIP_FAILED,
                    error.message ?: "Could not build Grid Set archive",
                )
            },
        )
    }

    private suspend fun renderGridXml(
        board: ObfBoard,
        gridName: String,
        folder: String,
        buttons: Map<String, ObfButton>,
        settings: ResolvedBoardSettings,
        defaultLocale: String?,
        wordTypeScheme: WordTypeColorScheme,
        resolveLinkTarget: (ObfLoadBoard?) -> String?,
        loadMedia: suspend (String) -> ByteArray?,
        loadUrl: (suspend (String) -> ByteArray?)?,
        entries: MutableList<Pair<String, ByteArray>>,
        assets: MutableList<String>,
        warnings: MutableMap<String, Int>,
    ): String {
        val grid = requireNotNull(board.grid)
        val imagesById = board.images.associateBy { it.id }
        val soundsById = board.sounds.associateBy { it.id }
        val locale = board.locale?.takeIf { it.isNotBlank() } ?: defaultLocale
        val showChat = settings.showMessageBar
        val rowOffset = if (showChat) 1 else 0

        val sb = StringBuilder(2048)
        sb.append(XML_DECLARATION)
        sb.append("<Grid xmlns:xsi=\"").append(XSI_NS).append("\">")

        val background = parseGridColor(board.backgroundColor)
        if (background != null) {
            sb.append("<BackgroundColour>").append(background).append("</BackgroundColour>")
        } else if (!board.backgroundColor.isNullOrBlank()) {
            warnings.increment("unsupported Page color")
        }

        sb.append("<GridGuid>").append(guidGenerator()).append("</GridGuid>")

        sb.append("<ColumnDefinitions>")
        repeat(grid.columns) { sb.append("<ColumnDefinition />") }
        sb.append("</ColumnDefinitions>")

        sb.append("<RowDefinitions>")
        repeat(grid.rows + rowOffset) { sb.append("<RowDefinition />") }
        sb.append("</RowDefinitions>")

        sb.append("<Cells>")
        if (showChat) {
            sb.append("<Cell ColumnSpan=\"").append(grid.columns).append("\">")
            sb.append("<Content>")
            sb.append("<ContentType>Workspace</ContentType>")
            sb.append("<ContentSubType>Chat</ContentSubType>")
            sb.append("<Style><BasedOnStyle>Workspace</BasedOnStyle></Style>")
            sb.append("</Content>")
            sb.append("</Cell>")
        }

        val fields = grid.fieldItems()
            .filter { it.buttonId != null }
            .sortedWith(compareBy({ it.row }, { it.column }))

        for (field in fields) {
            val buttonId = field.buttonId ?: continue
            val button = buttons[buttonId] ?: continue
            if (button.hidden) continue

            val x = field.column
            val y = field.row + rowOffset
            val targetName = resolveLinkTarget(button.loadBoard)
            val actions = button.resolvedActions()

            val sound = if (button.loadBoard == null && actions.isEmpty()) {
                loadSoundAsset(button.soundId?.let(soundsById::get), loadMedia, loadUrl, warnings)
            } else {
                null
            }

            val commandsXml = StringBuilder()
            val playedSound = appendButtonCommands(
                sb = commandsXml,
                button = button,
                board = board,
                locale = locale,
                settings = settings,
                targetName = targetName,
                sound = sound,
                warnings = warnings,
            )
            if (playedSound && sound != null) {
                val assetPath = "$folder/$x-$y-0-filedata.${sound.extension}"
                entries += assetPath to sound.bytes
                assets += assetPath
            }

            val image = if (settings.showSymbols) {
                loadImageAsset(button.imageId?.let(imagesById::get), loadMedia, loadUrl, warnings)
            } else {
                null
            }

            sb.append("<Cell X=\"").append(x)
                .append("\" Y=\"").append(y)
                .append("\" RowSpan=\"").append(field.rowSpan)
                .append("\" ColumnSpan=\"").append(field.columnSpan)
                .append("\">")
            sb.append("<Content>")

            if (commandsXml.isNotEmpty()) {
                sb.append("<Commands>").append(commandsXml).append("</Commands>")
            }

            sb.append("<CaptionAndImage>")
            val label = localizedText(board, locale, button.label)
            if (settings.showLabels || image == null) {
                val fallback = targetName ?: localizedText(board, locale, button.vocalization)
                val caption = label.ifEmpty { if (image == null) fallback else "" }
                sb.append("<Caption>").append(escapeXml(caption)).append("</Caption>")
            }
            if (image != null) {
                sb.append("<Image>.").append(image.extension).append("</Image>")
                val assetPath = "$folder/$x-$y.${image.extension}"
                entries += assetPath to image.bytes
                assets += assetPath
            }
            sb.append("</CaptionAndImage>")

            sb.append("<Style><BasedOnStyle>Default</BasedOnStyle>")
            val rawBg = button.resolvedBackgroundColor(wordTypeScheme, locale, label.ifEmpty { null })
            val backColor = parseGridColor(rawBg)
            if (backColor != null) {
                sb.append("<BackColour>").append(backColor).append("</BackColour>")
            } else if (!button.backgroundColor.isNullOrBlank()) {
                warnings.increment("unsupported button color")
            }
            val borderColor = parseGridColor(button.borderColor)
            if (borderColor != null) {
                sb.append("<BorderColour>").append(borderColor).append("</BorderColour>")
            } else if (!button.borderColor.isNullOrBlank()) {
                warnings.increment("unsupported button color")
            }
            sb.append("</Style>")

            sb.append("</Content>")
            sb.append("</Cell>")
        }

        if (board.pageElements().isNotEmpty() || board.backgroundImage != null) {
            warnings.increment("Page with Wingmate-specific layout elements or background image")
        }

        sb.append("</Cells>")
        sb.append("</Grid>")
        return sb.toString()
    }

    private fun appendButtonCommands(
        sb: StringBuilder,
        button: ObfButton,
        board: ObfBoard,
        locale: String?,
        settings: ResolvedBoardSettings,
        targetName: String?,
        sound: MediaAsset?,
        warnings: MutableMap<String, Int>,
    ): Boolean {
        val spelling = board.spellingMode
        val actions = button.resolvedActions()
        if (actions.isNotEmpty()) {
            for (action in actions) {
                val key = action.trim().lowercase()
                val mapped = ACTION_COMMANDS[key]
                when {
                    mapped != null -> sb.append("<Command ID=\"").append(mapped).append("\" />")
                    key == ":backspace" -> {
                        val command = if (spelling) "Action.DeleteLetter" else "Action.DeleteWord"
                        sb.append("<Command ID=\"").append(command).append("\" />")
                    }
                    action.startsWith("+") && action.length > 1 -> {
                        appendInsertText(sb, action.substring(1), spelling)
                    }
                    else -> warnings.increment("unsupported button action")
                }
            }
            return false
        }

        if (targetName != null) {
            sb.append("<Command ID=\"Jump.To\"><Parameter Key=\"grid\">")
                .append(escapeXml(targetName))
                .append("</Parameter></Command>")
            return false
        }

        if (button.loadBoard != null) {
            warnings.increment("unresolved page link")
            return false
        }

        if (button.type == ObfButtonType.NGramPrediction) {
            warnings.increment("Wingmate prediction button")
            return false
        }

        val rawText = if (button.vocalization != null) button.vocalization else button.label
        val text = localizedText(board, locale, rawText)
        val speak = settings.activationBehavior != BoardActivationBehavior.AddOnly &&
            settings.speechPolicy != SpeechPolicy.SentenceOnly
        val add = settings.activationBehavior != BoardActivationBehavior.SpeakOnly
        val playRecording = speak && sound != null

        if (playRecording) {
            sb.append("<Command ID=\"SpeechPlaySound\"><Parameter Key=\"filedata\"><data>.")
                .append(sound.extension)
                .append("</data></Parameter></Command>")
        } else if (speak && text.isNotEmpty()) {
            sb.append("<Command ID=\"Speech.SpeakNow\"><Parameter Key=\"text\" xml:space=\"preserve\">")
                .append(escapeXml(text))
                .append("</Parameter></Command>")
        }

        if (add && text.isNotEmpty()) {
            appendInsertText(sb, text, spelling)
        }

        if (sound != null && !playRecording) {
            warnings.increment("recording skipped by the Page's speech settings")
        }

        if ((text.isNotEmpty() || sound != null) &&
            (settings.returnBehavior == BoardReturnBehavior.Previous ||
                settings.returnBehavior == BoardReturnBehavior.StartPage)
        ) {
            val returnCommand = if (settings.returnBehavior == BoardReturnBehavior.Previous) "Jump.Back" else "Jump.Home"
            sb.append("<Command ID=\"").append(returnCommand).append("\" />")
        }

        return playRecording
    }

    private fun appendInsertText(sb: StringBuilder, text: String, spelling: Boolean) {
        if (text.isEmpty()) return
        sb.append("<Command ID=\"Action.InsertText\"><Parameter Key=\"text\"><p>")
        sb.append("<s><r xml:space=\"preserve\">").append(escapeXml(text)).append("</r></s>")
        if (!spelling && !text.last().isWhitespace()) {
            sb.append("<s><r xml:space=\"preserve\"> </r></s>")
        }
        sb.append("</p></Parameter></Command>")
    }

    private suspend fun loadImageAsset(
        image: ObfImage?,
        loadMedia: suspend (String) -> ByteArray?,
        loadUrl: (suspend (String) -> ByteArray?)?,
        warnings: MutableMap<String, Int>,
    ): MediaAsset? {
        if (image == null) return null
        val loaded = loadFirstMediaBytes(
            sources = obfImageSources(image),
            declaredMime = image.contentType,
            loadMedia = loadMedia,
            loadUrl = loadUrl,
        )
        if (loaded == null) {
            warnings.increment("unavailable image")
            return null
        }
        val ext = resolveImageExtension(loaded.mime, loaded.pathOrUrl, loaded.bytes)
        if (ext == null || loaded.bytes.isEmpty() || loaded.bytes.size > MAX_MEDIA_BYTES) {
            warnings.increment("unsupported or unreadable image")
            return null
        }
        return MediaAsset(ext, loaded.bytes)
    }

    private suspend fun loadSoundAsset(
        sound: ObfSound?,
        loadMedia: suspend (String) -> ByteArray?,
        loadUrl: (suspend (String) -> ByteArray?)?,
        warnings: MutableMap<String, Int>,
    ): MediaAsset? {
        if (sound == null) return null
        val loaded = loadFirstMediaBytes(
            sources = obfSoundSources(sound),
            declaredMime = sound.contentType,
            loadMedia = loadMedia,
            loadUrl = loadUrl,
        )
        if (loaded == null) {
            warnings.increment("unavailable sound")
            return null
        }
        val ext = resolveSoundExtension(loaded.mime, loaded.pathOrUrl, loaded.bytes)
        if (ext == null || loaded.bytes.isEmpty() || loaded.bytes.size > MAX_MEDIA_BYTES) {
            warnings.increment("unsupported or unreadable sound")
            return null
        }
        return MediaAsset(ext, loaded.bytes)
    }

    private suspend fun loadFirstMediaBytes(
        sources: List<ObfMediaSource>,
        declaredMime: String?,
        loadMedia: suspend (String) -> ByteArray?,
        loadUrl: (suspend (String) -> ByteArray?)?,
    ): LoadedMedia? {
        for (source in sources) {
            try {
                val loaded = when (source) {
                    is ObfMediaSource.Data -> decodeInlineMedia(source.value, declaredMime)
                    is ObfMediaSource.Path -> loadMedia(source.value)?.takeIf { it.isNotEmpty() }?.let {
                        LoadedMedia(it, declaredMime, source.value)
                    }
                    is ObfMediaSource.Url -> {
                        if (source.value.startsWith("data:", ignoreCase = true)) {
                            decodeInlineMedia(source.value, declaredMime)
                        } else {
                            loadUrl?.invoke(source.value)?.takeIf { it.isNotEmpty() }?.let {
                                LoadedMedia(it, declaredMime, source.value)
                            }
                        }
                    }
                    is ObfMediaSource.Symbol -> null
                }
                if (loaded != null && loaded.bytes.isNotEmpty()) return loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Try the next source; missing media falls back cleanly.
            }
        }
        return null
    }

    private fun decodeInlineMedia(raw: String, fallbackMime: String?): LoadedMedia? {
        val trimmed = raw.trim()
        val (mime, base64Payload) = if (trimmed.startsWith("data:", ignoreCase = true)) {
            val comma = trimmed.indexOf(',')
            if (comma < 0) return null
            val header = trimmed.substring(5, comma)
            if (!header.endsWith(";base64", ignoreCase = true)) return null
            val extractedMime = header.substringBefore(';').trim().takeIf { it.isNotEmpty() } ?: fallbackMime
            extractedMime to trimmed.substring(comma + 1)
        } else {
            fallbackMime to trimmed
        }
        val bytes = Base64Decoder.decodeOrNull(base64Payload) ?: return null
        return if (bytes.isNotEmpty()) LoadedMedia(bytes, mime, null) else null
    }

    private fun resolveEffectiveSettings(
        app: Settings?,
        screen: BoardSettingsOverrides,
        page: BoardSettingsOverrides,
    ): ResolvedBoardSettings = resolveBoardSettings(
        appShowLabels = app?.showLabels ?: true,
        appShowSymbols = app?.showSymbols ?: true,
        appLabelAtTop = app?.labelAtTop ?: false,
        appShowMessageBar = app?.boardShowMessageBar ?: true,
        appShowSpeakButton = app?.boardShowSpeakButton ?: true,
        appMessageBarEditable = app?.boardMessageBarEditable ?: true,
        appActivationBehavior = app?.boardActivationBehavior ?: BoardActivationBehavior.SpeakAndAdd,
        appReturnBehavior = app?.boardReturnBehavior ?: BoardReturnBehavior.Stay,
        screen = screen,
        page = page,
    )

    private fun resolveTargetGridName(
        link: ObfLoadBoard?,
        boardIds: Set<String>,
        gridNames: Map<String, String>,
    ): String? {
        if (link == null) return null
        val id = link.id?.takeIf { it in boardIds }
        if (id != null) return gridNames[id]
        val fromPath = link.path?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it in boardIds }
        return fromPath?.let(gridNames::get)
    }

    private fun buildSettingsXml(primaryLanguage: String?, startGrid: String): String = buildString {
        append(XML_DECLARATION)
        append("<GridSetSettings xmlns:xsi=\"").append(XSI_NS).append("\">")
        if (!primaryLanguage.isNullOrBlank()) {
            append("<PrimaryLanguage>").append(escapeXml(primaryLanguage)).append("</PrimaryLanguage>")
        }
        append("<StartGrid>").append(escapeXml(startGrid)).append("</StartGrid>")
        append("<GridSetFileFormatVersion>1</GridSetFileFormatVersion>")
        append("</GridSetSettings>")
    }

    private fun buildFileMapXml(entries: List<Pair<String, List<String>>>): String = buildString {
        append(XML_DECLARATION)
        append("<FileMap xmlns:xsi=\"").append(XSI_NS).append("\">")
        append("<Entries>")
        for ((gridPath, assets) in entries) {
            append("<Entry StaticFile=\"").append(escapeXml(gridPath.replace('/', '\\'))).append("\">")
            append("<DynamicFiles>")
            for (asset in assets) {
                append("<File>").append(escapeXml(asset.replace('/', '\\'))).append("</File>")
            }
            append("</DynamicFiles>")
            append("</Entry>")
        }
        append("</Entries>")
        append("</FileMap>")
    }

    private fun assignGridNames(boards: List<ObfBoard>, defaultLocale: String?): Map<String, String> {
        val names = mutableMapOf<String, String>()
        val used = mutableSetOf<String>()
        boards.forEachIndexed { idx, board ->
            val locale = board.locale?.takeIf { it.isNotBlank() } ?: defaultLocale
            var name = localizedText(board, locale, board.name)
            name = sanitizeGridFolderName(name).ifEmpty { "Page ${idx + 1}" }
            val stem = name.substringBefore('.').trim()
            if (WINDOWS_RESERVED_NAME.matches(stem)) {
                name = "_$name"
            }
            var candidate = name
            var suffix = 2
            while (!used.add(candidate.lowercase())) {
                candidate = "$name ($suffix)"
                suffix++
            }
            names[board.id] = candidate
        }
        return names
    }

    private fun localizedText(board: ObfBoard, locale: String?, rawValue: String?): String {
        if (rawValue.isNullOrEmpty()) return ""
        return resolveObfLocalizedString(board.strings, locale, rawValue) ?: rawValue
    }

    private data class LoadedMedia(
        val bytes: ByteArray,
        val mime: String?,
        val pathOrUrl: String?,
    )

    private data class MediaAsset(
        val extension: String,
        val bytes: ByteArray,
    )

    companion object {
        private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        private const val XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"
        private const val SETTINGS_PATH = "Settings0/settings.xml"
        private const val MAX_GRID_CELLS = 10_000
        private const val MAX_MEDIA_BYTES = 25 * 1024 * 1024

        private val WINDOWS_RESERVED_NAME = Regex(
            "^(?:CON|PRN|AUX|NUL|(?:COM|LPT)[1-9¹²³])$",
            RegexOption.IGNORE_CASE,
        )
        private val INVALID_PATH_CHARS = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")
        private val HEX_COLOR = Regex("^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
        private val RGB_COLOR = Regex(
            "^rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})(?:\\s*,\\s*(0?\\.\\d+|[01](?:\\.0+)?))?\\s*\\)$",
            RegexOption.IGNORE_CASE,
        )

        private val IMAGE_MIME_TYPES = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpeg",
            "image/jpg" to "jpeg",
            "image/gif" to "gif",
            "image/bmp" to "bmp",
            "image/webp" to "webp",
        )
        private val IMAGE_EXTENSIONS = setOf("png", "jpeg", "gif", "bmp", "webp")

        private val SOUND_MIME_TYPES = mapOf(
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/wave" to "wav",
            "audio/mp4" to "m4a",
            "audio/m4a" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/x-ms-wma" to "wma",
        )
        private val SOUND_EXTENSIONS = setOf("mp3", "wav", "m4a", "wma")

        private val ACTION_COMMANDS = mapOf(
            ":space" to "Action.Space",
            ":clear" to "Action.Clear",
            ":speak" to "Action.Speak",
            ":home" to "Jump.Home",
            ":back" to "Jump.Back",
            ":stop" to "Speech.Stop",
        )

        fun sanitizeExportFileName(screenName: String, extension: String): String {
            val cleaned = sanitizeGridFolderName(screenName).ifEmpty { "Screen" }
            val safe = if (WINDOWS_RESERVED_NAME.matches(cleaned.substringBefore('.').trim())) {
                "_$cleaned"
            } else {
                cleaned
            }
            return "$safe.$extension"
        }

        internal fun parseGridColor(value: String?): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (HEX_COLOR.matches(trimmed)) {
                var digits = trimmed.substring(1)
                if (digits.length == 3 || digits.length == 4) {
                    digits = buildString(digits.length * 2) {
                        for (c in digits) {
                            append(c)
                            append(c)
                        }
                    }
                }
                val withAlpha = if (digits.length == 6) "${digits}FF" else digits
                return "#${withAlpha.uppercase()}"
            }
            val match = RGB_COLOR.matchEntire(trimmed) ?: return null
            val r = match.groupValues[1].toIntOrNull() ?: return null
            val g = match.groupValues[2].toIntOrNull() ?: return null
            val b = match.groupValues[3].toIntOrNull() ?: return null
            if (r > 255 || g > 255 || b > 255) return null
            val alphaFloat = match.groupValues[4].takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: 1f
            val a = (alphaFloat.coerceIn(0f, 1f) * 255f).roundToInt()
            return "#${hexByte(r)}${hexByte(g)}${hexByte(b)}${hexByte(a)}"
        }

        private fun sanitizeGridFolderName(raw: String): String =
            INVALID_PATH_CHARS.replace(raw, "_")
                .trim { it == ' ' || it == '.' }
                .take(100)
                .trimEnd { it == ' ' || it == '.' }

        private fun resolveImageExtension(mime: String?, pathOrUrl: String?, bytes: ByteArray): String? {
            IMAGE_MIME_TYPES[mime?.trim()?.lowercase()]?.let { return it }
            sniffImageExtension(bytes)?.let { return it }
            val ext = extractExtension(pathOrUrl)?.let { if (it == "jpg") "jpeg" else it }
            return ext?.takeIf { it in IMAGE_EXTENSIONS }
        }

        private fun resolveSoundExtension(mime: String?, pathOrUrl: String?, bytes: ByteArray): String? {
            SOUND_MIME_TYPES[mime?.trim()?.lowercase()]?.let { return it }
            sniffSoundExtension(bytes)?.let { return it }
            val ext = extractExtension(pathOrUrl)
            return ext?.takeIf { it in SOUND_EXTENSIONS }
        }

        private fun extractExtension(pathOrUrl: String?): String? {
            if (pathOrUrl.isNullOrBlank()) return null
            val cleaned = pathOrUrl.substringBefore('?').substringBefore('#')
            val lastSegment = cleaned.substringAfterLast('/')
            if (!lastSegment.contains('.')) return null
            return lastSegment.substringAfterLast('.').lowercase()
        }

        private fun sniffImageExtension(bytes: ByteArray): String? {
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) return "png"
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            ) return "jpeg"
            if (bytes.size >= 6 &&
                bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
            ) return "gif"
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) return "webp"
            if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
                return "bmp"
            }
            return null
        }

        private fun sniffSoundExtension(bytes: ByteArray): String? {
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
            ) return "wav"
            if (bytes.size >= 3 &&
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
            ) return "mp3"
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
                return "mp3"
            }
            if (bytes.size >= 8 &&
                bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
            ) return "m4a"
            return null
        }

        private fun escapeXml(value: String): String = buildString(value.length) {
            for (c in value) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> {
                        val code = c.code
                        val validXml10 = code == 0x09 || code == 0x0A || code == 0x0D ||
                            (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
                        if (validXml10) append(c)
                    }
                }
            }
        }

        private fun hexByte(value: Int): String =
            value.coerceIn(0, 255).toString(16).uppercase().padStart(2, '0')

        private fun randomUuidV4(): String {
            val bytes = Random.nextBytes(16)
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            val hex = buildString(32) {
                for (b in bytes) {
                    val v = b.toInt() and 0xFF
                    append(HEX_DIGITS[v ushr 4])
                    append(HEX_DIGITS[v and 0x0F])
                }
            }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }

        private const val HEX_DIGITS = "0123456789abcdef"

        private fun MutableMap<String, Int>.increment(key: String) {
            this[key] = (this[key] ?: 0) + 1
        }
    }
}
