package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfManifest
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.OBF_SCREEN_SETTINGS_EXTENSION
import io.github.jdreioe.wingmate.domain.obf.OBF_SCREEN_NAME_EXTENSION
import io.github.jdreioe.wingmate.domain.obf.decodeBoardSettings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import io.github.jdreioe.wingmate.platform.ArchiveEntry
import io.github.jdreioe.wingmate.platform.ArchiveReadError
import io.github.jdreioe.wingmate.platform.ArchiveReadException
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.readEntryBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

/** One validated import pipeline shared by every OBF/OBZ UI entry point. */
class BoardImportService(
    private val obfParser: ObfParser,
    private val boardRepository: BoardRepository,
    private val boardSetRepository: BoardSetRepository,
    private val filePicker: FilePicker,
    private val fileStorage: FileStorage? = null,
    private val validator: ObfValidator = ObfValidator(),
    private val limits: ObzImportLimits = ObzImportLimits(),
    private val urlLoader: ObfMediaUrlLoader = ObfMediaUrlLoader { null }
) {
    suspend fun importBoardSetResult(): BoardImportResult {
        val filePath = try {
            filePicker.pickFile("Select Board File", listOf("obf", "obz", "json"))
        } catch (_: CancellationException) {
            return BoardImportResult.Cancelled
        } catch (error: Throwable) {
            return failure(BoardImportErrorCode.FILE_UNREADABLE, error.message ?: "Could not open the file picker")
        } ?: return BoardImportResult.Cancelled
        return importBoardSetFromPathResult(filePath)
    }

    suspend fun importBoardSetFromPathResult(filePath: String): BoardImportResult {
        val warnings = mutableListOf<BoardImportWarning>()
        val storedPaths = mutableListOf<String>()
        val savedBoardIds = mutableListOf<String>()
        var savedSetId: String? = null
        return try {
            val graph = if (filePath.lowercase().endsWith(".obz")) {
                importObzGraph(filePath, warnings, storedPaths)
            } else {
                importSingleObfGraph(filePath, warnings, storedPaths)
            }.canonicalizeBoardLinks()

            savedBoardIds += graph.boards.map { it.id }
            boardRepository.saveBoards(graph.boards)
            savedSetId = graph.boardSet.id
            boardSetRepository.saveBoardSet(graph.boardSet)
            BoardImportResult.Success(graph.boardSet, warnings)
        } catch (_: CancellationException) {
            rollback(savedSetId, savedBoardIds, storedPaths)
            BoardImportResult.Cancelled
        } catch (error: ImportFailure) {
            rollback(savedSetId, savedBoardIds, storedPaths)
            failure(error.code, error.message ?: error.code.name, warnings)
        } catch (error: ArchiveReadException) {
            rollback(savedSetId, savedBoardIds, storedPaths)
            val code = if (error.error == ArchiveReadError.ENTRY_TOO_LARGE) {
                BoardImportErrorCode.MEDIA_ENTRY_TOO_LARGE
            } else {
                BoardImportErrorCode.MALFORMED_ARCHIVE
            }
            failure(code, error.message ?: code.name, warnings)
        } catch (error: Throwable) {
            rollback(savedSetId, savedBoardIds, storedPaths)
            failure(BoardImportErrorCode.PERSISTENCE_FAILED, error.message ?: "Import could not be saved", warnings)
        }
    }

    /** Imports an OBZ downloaded by an in-app preset provider or remote Screen link. */
    suspend fun importBoardSetFromBytesResult(
        content: ByteArray,
        preserveBoardIds: Boolean = false,
        remotePackage: Boolean = false,
    ): BoardImportResult {
        val warnings = mutableListOf<BoardImportWarning>()
        val storedPaths = mutableListOf<String>()
        val savedBoardIds = mutableListOf<String>()
        var savedSetId: String? = null
        return try {
            val archive = filePicker.openArchiveBytes(content)
                ?: throw ImportFailure(BoardImportErrorCode.FILE_UNREADABLE, "Could not open downloaded board archive")
            val reserved = if (preserveBoardIds) boardRepository.listBoards().map { it.id }.toSet() else null
            val graph = importObzGraph(
                archive, warnings, storedPaths, reservedBoardIds = reserved, remotePackage = remotePackage,
            ).canonicalizeBoardLinks()
            savedBoardIds += graph.boards.map { it.id }
            boardRepository.saveBoards(graph.boards)
            savedSetId = graph.boardSet.id
            boardSetRepository.saveBoardSet(graph.boardSet)
            BoardImportResult.Success(graph.boardSet, warnings)
        } catch (_: CancellationException) {
            rollback(savedSetId, savedBoardIds, storedPaths)
            BoardImportResult.Cancelled
        } catch (error: ImportFailure) {
            rollback(savedSetId, savedBoardIds, storedPaths)
            failure(error.code, error.message ?: error.code.name, warnings)
        } catch (error: ArchiveReadException) {
            rollback(savedSetId, savedBoardIds, storedPaths)
            val code = if (error.error == ArchiveReadError.ENTRY_TOO_LARGE) {
                BoardImportErrorCode.MEDIA_ENTRY_TOO_LARGE
            } else {
                BoardImportErrorCode.MALFORMED_ARCHIVE
            }
            failure(code, error.message ?: code.name, warnings)
        } catch (error: Throwable) {
            rollback(savedSetId, savedBoardIds, storedPaths)
            failure(BoardImportErrorCode.PERSISTENCE_FAILED, error.message ?: "Import could not be saved", warnings)
        }
    }

    /**
     * Replaces Pages from an OBZ while preserving device-local Screen settings.
     * The caller rechecks whether the Screen can be replaced before the repository writes.
     */
    suspend fun replaceBoardSetFromBytesResult(
        targetBoardSetId: String,
        content: ByteArray,
        allowLocked: Boolean = false,
        canReplace: suspend () -> Boolean,
    ): BoardImportResult {
        val existingSet = boardSetRepository.getBoardSet(targetBoardSetId)
            ?: return failure(BoardImportErrorCode.INVALID_GRAPH, "Screen '$targetBoardSetId' was not found")
        val previousBoards = existingSet.boardIds.mapNotNull { boardRepository.getBoard(it) }
        val warnings = mutableListOf<BoardImportWarning>()
        val storedPaths = mutableListOf<String>()
        val newBoardIds = mutableListOf<String>()
        return try {
            val archive = filePicker.openArchiveBytes(content)
                ?: throw ImportFailure(BoardImportErrorCode.FILE_UNREADABLE, "Could not open remote board archive")
            // Keep the package's Page IDs when they do not collide with another Screen, so
            // repeated round trips between devices keep stable Page identities.
            val ownIds = existingSet.boardIds.toSet()
            val foreignIds = boardRepository.listBoards().map { it.id }.filterNot { it in ownIds }.toSet()
            val importedGraph = importObzGraph(
                archive = archive,
                warnings = warnings,
                storedPaths = storedPaths,
                mediaNamespace = "${targetBoardSetId}/${newId("rev")}",
                reservedBoardIds = foreignIds,
                fallbackName = existingSet.name,
                remotePackage = true,
            ).canonicalizeBoardLinks()

            if (!canReplace()) {
                rollback(null, emptyList(), storedPaths)
                return failure(BoardImportErrorCode.SCREEN_CHANGED, "Screen changed while importing", warnings)
            }
            val latest = boardSetRepository.getBoardSet(targetBoardSetId)
            if (latest == null || (!allowLocked && latest.isLocked)) {
                rollback(null, emptyList(), storedPaths)
                return failure(BoardImportErrorCode.SCREEN_CHANGED, "Screen is unavailable or locked", warnings)
            }
            val mergedSet = latest.copy(
                name = importedGraph.boardSet.name.ifBlank { existingSet.name },
                rootBoardId = importedGraph.boardSet.rootBoardId,
                boardIds = importedGraph.boardSet.boardIds,
                screenSettings = importedGraph.boardSet.screenSettings,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            )
            newBoardIds += importedGraph.boards.map { it.id }
            boardRepository.saveBoards(importedGraph.boards)
            boardSetRepository.saveBoardSet(mergedSet)

            val retainedIds = newBoardIds.toSet()
            previousBoards
                .filterNot { it.id in retainedIds }
                .forEach { oldBoard -> runCatching { boardRepository.deleteBoard(oldBoard.id) } }

            // Duplicated Screens can share media with the original.
            fileStorage?.let { storage ->
                runCatching {
                    val usedPaths = boardRepository.listBoards().flatMap(::mediaPaths).toSet()
                    previousBoards.flatMap(::mediaPaths).distinct()
                        .filter { path ->
                            path.startsWith("boardsets/$targetBoardSetId/") &&
                                ".." !in path.split('/') && path !in usedPaths
                        }
                        .forEach { storage.delete(it) }
                }
            }

            BoardImportResult.Success(mergedSet, warnings)
        } catch (_: CancellationException) {
            rollbackReplace(existingSet, previousBoards, newBoardIds, storedPaths)
            BoardImportResult.Cancelled
        } catch (error: ImportFailure) {
            rollbackReplace(existingSet, previousBoards, newBoardIds, storedPaths)
            failure(error.code, error.message ?: error.code.name, warnings)
        } catch (error: ArchiveReadException) {
            rollbackReplace(existingSet, previousBoards, newBoardIds, storedPaths)
            val code = if (error.error == ArchiveReadError.ENTRY_TOO_LARGE) {
                BoardImportErrorCode.MEDIA_ENTRY_TOO_LARGE
            } else {
                BoardImportErrorCode.MALFORMED_ARCHIVE
            }
            failure(code, error.message ?: code.name, warnings)
        } catch (error: Throwable) {
            rollbackReplace(existingSet, previousBoards, newBoardIds, storedPaths)
            failure(BoardImportErrorCode.PERSISTENCE_FAILED, error.message ?: "Remote update could not be saved", warnings)
        }
    }

    private suspend fun rollbackReplace(
        previousSet: ObfBoardSet,
        previousBoards: List<ObfBoard>,
        newBoardIds: List<String>,
        storedPaths: List<String>,
    ) = withContext(NonCancellable) {
        val previousIds = previousBoards.map { it.id }.toSet()
        if (newBoardIds.isNotEmpty()) {
            runCatching { boardRepository.saveBoards(previousBoards) }
            runCatching { boardSetRepository.saveBoardSet(previousSet) }
        }
        newBoardIds.filterNot { it in previousIds }.asReversed().forEach {
            runCatching { boardRepository.deleteBoard(it) }
        }
        val storage = fileStorage
        if (storage != null) storedPaths.asReversed().forEach { runCatching { storage.delete(it) } }
    }

    private suspend fun rollback(setId: String?, boardIds: List<String>, paths: List<String>) =
        withContext(NonCancellable) {
            setId?.let { runCatching { boardSetRepository.deleteBoardSet(it) } }
            boardIds.asReversed().forEach { runCatching { boardRepository.deleteBoard(it) } }
            val storage = fileStorage
            if (storage != null) paths.asReversed().forEach { runCatching { storage.delete(it) } }
        }

    private suspend fun importSingleObfGraph(
        filePath: String,
        warnings: MutableList<BoardImportWarning>,
        storedPaths: MutableList<String>
    ): BoardSetGraph {
        val content = filePicker.readFileAsText(filePath)
            ?: throw ImportFailure(BoardImportErrorCode.FILE_UNREADABLE, "Could not read '$filePath'")
        val board = obfParser.parseBoard(content).getOrElse {
            throw ImportFailure(BoardImportErrorCode.MALFORMED_JSON, it.message ?: "Invalid OBF JSON")
        }
        validate(listOf(ParsedObfBoard(null, board)), board.id)
        return buildGraph(
            boards = listOf(ParsedObfBoard(null, board)),
            rootOriginalId = board.id,
            archive = null,
            archiveNames = emptySet(),
            screenSettings = BoardSettingsOverrides(),
            warnings = warnings,
            storedPaths = storedPaths
        )
    }

    private suspend fun importObzGraph(
        filePath: String,
        warnings: MutableList<BoardImportWarning>,
        storedPaths: MutableList<String>
    ): BoardSetGraph {
        val archive = filePicker.openArchive(filePath)
            ?: throw ImportFailure(BoardImportErrorCode.FILE_UNREADABLE, "Could not open '$filePath'")
        return importObzGraph(archive, warnings, storedPaths)
    }

    private suspend fun importObzGraph(
        archive: ArchiveReader,
        warnings: MutableList<BoardImportWarning>,
        storedPaths: MutableList<String>,
        mediaNamespace: String? = null,
        reservedBoardIds: Set<String>? = null,
        fallbackName: String? = null,
        remotePackage: Boolean = false,
    ): BoardSetGraph {
        try {
            val entries = archive.entries()
            validateArchive(entries)
            val names = entries.filterNot { it.isDirectory }.map { it.name }.toSet()
            if ("manifest.json" !in names) {
                throw ImportFailure(BoardImportErrorCode.INVALID_MANIFEST, "manifest.json is missing")
            }
            val manifestBytes = readJsonEntry(archive, "manifest.json")
            val manifest = obfParser.parseManifest(manifestBytes.decodeToString()).getOrElse {
                throw ImportFailure(BoardImportErrorCode.INVALID_MANIFEST, it.message ?: "Invalid manifest.json")
            }
            val boardPaths = (listOf(manifest.root) + manifest.paths.boards.values).distinct()
            val parsedBoards = boardPaths.map { path ->
                if (path !in names) {
                    throw ImportFailure(BoardImportErrorCode.INVALID_MANIFEST, "Board entry '$path' is missing")
                }
                val board = obfParser.parseBoard(readJsonEntry(archive, path).decodeToString()).getOrElse {
                    throw ImportFailure(BoardImportErrorCode.MALFORMED_JSON, "Invalid board '$path': ${it.message}")
                }
                ParsedObfBoard(path, board)
            }.map { parsed -> normalizeDuplicateMedia(parsed, warnings) }
            val rootId = parsedBoards.firstOrNull { it.path == manifest.root }?.board?.id
                ?: throw ImportFailure(BoardImportErrorCode.INVALID_MANIFEST, "Manifest root is not a board")
            validate(parsedBoards, rootId, manifest, names)
            return buildGraph(
                boards = parsedBoards,
                rootOriginalId = rootId,
                archive = archive,
                archiveNames = names,
                screenSettings = decodeBoardSettings(manifest.extensions[OBF_SCREEN_SETTINGS_EXTENSION])
                    ?: BoardSettingsOverrides(),
                warnings = warnings,
                storedPaths = storedPaths,
                mediaNamespace = mediaNamespace,
                reservedBoardIds = reservedBoardIds,
                screenName = (manifest.extensions[OBF_SCREEN_NAME_EXTENSION] as? JsonPrimitive)
                    ?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(200)
                    ?: fallbackName,
                remotePackage = remotePackage,
            )
        } finally {
            archive.close()
        }
    }

    /**
     * Some established exporters repeat an image entry when multiple buttons use
     * the same symbol. OBF button references are ID-based, so retaining the first
     * occurrence is deterministic and avoids rejecting otherwise valid board sets.
     */
    private fun normalizeDuplicateMedia(
        parsed: ParsedObfBoard,
        warnings: MutableList<BoardImportWarning>
    ): ParsedObfBoard {
        val board = parsed.board
        val images = board.images.distinctBy { it.id }
        val sounds = board.sounds.distinctBy { it.id }
        if (images.size != board.images.size) {
            warnings += BoardImportWarning(
                "duplicate_image_id",
                "Board '${board.id}' contained repeated image IDs; first entries were retained"
            )
        }
        if (sounds.size != board.sounds.size) {
            warnings += BoardImportWarning(
                "duplicate_sound_id",
                "Board '${board.id}' contained repeated sound IDs; first entries were retained"
            )
        }
        return parsed.copy(board = board.copy(images = images, sounds = sounds))
    }

    private suspend fun readJsonEntry(archive: ArchiveReader, path: String): ByteArray = try {
        archive.readEntryBytes(path, limits.maxJsonEntryBytes)
    } catch (error: ArchiveReadException) {
        if (error.error == ArchiveReadError.ENTRY_TOO_LARGE) {
            throw ImportFailure(BoardImportErrorCode.JSON_ENTRY_TOO_LARGE, "JSON entry '$path' exceeds ${limits.maxJsonEntryBytes} bytes")
        }
        throw error
    }

    private fun validateArchive(entries: List<ArchiveEntry>) {
        if (entries.size > limits.maxEntries) {
            throw ImportFailure(BoardImportErrorCode.TOO_MANY_ENTRIES, "Archive contains ${entries.size} entries; maximum is ${limits.maxEntries}")
        }
        val exact = mutableSetOf<String>()
        val folded = mutableSetOf<String>()
        var total = 0L
        entries.forEach { entry ->
            if (!isSafeArchivePath(entry.name)) {
                throw ImportFailure(BoardImportErrorCode.UNSAFE_ENTRY_NAME, "Unsafe archive entry '${entry.name}'")
            }
            if (!exact.add(entry.name) || !folded.add(entry.name.lowercase())) {
                throw ImportFailure(BoardImportErrorCode.DUPLICATE_ENTRY, "Duplicate or case-colliding entry '${entry.name}'")
            }
            if (entry.isEncrypted) {
                throw ImportFailure(BoardImportErrorCode.ENCRYPTED_ENTRY, "Encrypted entry '${entry.name}' is not supported")
            }
            if (entry.uncompressedSize >= 0) {
                val entryLimit = if (entry.isJson()) limits.maxJsonEntryBytes else limits.maxMediaEntryBytes
                if (!entry.isDirectory && entry.uncompressedSize > entryLimit) {
                    val code = if (entry.isJson()) BoardImportErrorCode.JSON_ENTRY_TOO_LARGE else BoardImportErrorCode.MEDIA_ENTRY_TOO_LARGE
                    throw ImportFailure(code, "Entry '${entry.name}' exceeds $entryLimit bytes")
                }
                if (Long.MAX_VALUE - total < entry.uncompressedSize) {
                    throw ImportFailure(BoardImportErrorCode.ARCHIVE_TOO_LARGE, "Archive size overflows the supported limit")
                }
                total += entry.uncompressedSize
            }
            if (entry.uncompressedSize > 0 && entry.compressedSize == 0L) {
                throw ImportFailure(BoardImportErrorCode.COMPRESSION_RATIO_EXCEEDED, "Entry '${entry.name}' has an invalid compression ratio")
            }
            if (entry.uncompressedSize > 0 && entry.compressedSize > 0) {
                val ratio = entry.uncompressedSize.toDouble() / entry.compressedSize.toDouble()
                if (ratio > limits.maxCompressionRatio) {
                    throw ImportFailure(BoardImportErrorCode.COMPRESSION_RATIO_EXCEEDED, "Entry '${entry.name}' exceeds ${limits.maxCompressionRatio}:1")
                }
            }
        }
        if (total > limits.maxTotalUncompressedBytes) {
            throw ImportFailure(BoardImportErrorCode.ARCHIVE_TOO_LARGE, "Archive exceeds ${limits.maxTotalUncompressedBytes} uncompressed bytes")
        }
    }

    private fun ArchiveEntry.isJson(): Boolean =
        name.equals("manifest.json", true) || name.endsWith(".json", true) || name.endsWith(".obf", true)

    private fun isSafeArchivePath(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || name.startsWith('\\') || '\u0000' in name || '\uFFFD' in name) return false
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return false
        return name.replace('\\', '/').split('/').none { it == ".." }
    }

    private fun validate(
        boards: List<ParsedObfBoard>,
        rootId: String,
        manifest: ObfManifest? = null,
        names: Set<String> = emptySet()
    ) {
        val issues = validator.validate(boards, rootId, manifest, names)
        if (issues.isNotEmpty()) {
            val first = issues.first()
            throw ImportFailure(BoardImportErrorCode.INVALID_GRAPH, "${first.field}: ${first.message}")
        }
    }

    private suspend fun buildGraph(
        boards: List<ParsedObfBoard>,
        rootOriginalId: String,
        archive: ArchiveReader?,
        archiveNames: Set<String>,
        screenSettings: BoardSettingsOverrides,
        warnings: MutableList<BoardImportWarning>,
        storedPaths: MutableList<String>,
        mediaNamespace: String? = null,
        reservedBoardIds: Set<String>? = null,
        screenName: String? = null,
        remotePackage: Boolean = false,
    ): BoardSetGraph {
        val now = Clock.System.now().toEpochMilliseconds()
        val setId = newId("set")
        val mediaRoot = mediaNamespace ?: setId
        val usedIds = mutableSetOf<String>()
        val boardIdMap = boards.associate { parsed ->
            val original = parsed.board.id
            val keep = reservedBoardIds != null &&
                SAFE_BOARD_ID.matches(original) &&
                original != "." && original != ".." &&
                original !in reservedBoardIds &&
                original !in usedIds
            val id = if (keep) original else newId("board")
            usedIds += id
            original to id
        }
        val pathIdMap = boards.mapNotNull { parsed -> parsed.path?.let { it to parsed.board.id } }.toMap()
        val rootNewId = boardIdMap[rootOriginalId]
            ?: throw ImportFailure(BoardImportErrorCode.INVALID_GRAPH, "Root board ID is missing")

        val rewrittenBoards = boards.map { parsed ->
            val board = parsed.board
            val images = board.images.map { image ->
                val source = if (remotePackage) {
                    image.copy(
                        url = remoteMediaUrl(image.url, warnings),
                        dataUrl = remoteMediaUrl(image.dataUrl, warnings),
                    )
                } else image
                persistImage(source, mediaRoot, archive, archiveNames, warnings, storedPaths, remotePackage)
            }
            val sounds = board.sounds.map { sound ->
                val source = if (remotePackage) {
                    sound.copy(
                        url = remoteMediaUrl(sound.url, warnings),
                        dataUrl = remoteMediaUrl(sound.dataUrl, warnings),
                    )
                } else sound
                persistSound(source, mediaRoot, archive, archiveNames, warnings, storedPaths, remotePackage)
            }
            board.copy(
                id = boardIdMap.getValue(board.id),
                buttons = board.buttons.map { button -> rewriteButton(button, boardIdMap, pathIdMap) },
                images = images,
                sounds = sounds
            )
        }
        val root = rewrittenBoards.first { it.id == rootNewId }
        val boardSet = ObfBoardSet(
            id = setId,
            name = screenName ?: root.name ?: "Imported board set",
            rootBoardId = rootNewId,
            boardIds = rewrittenBoards.map { it.id },
            isLocked = false,
            screenSettings = screenSettings,
            createdAt = now,
            updatedAt = now
        )
        return BoardSetGraph(boardSet, rewrittenBoards)
    }

    private fun rewriteButton(
        button: ObfButton,
        boardIdMap: Map<String, String>,
        pathIdMap: Map<String, String>
    ): ObfButton {
        val link = button.loadBoard ?: return button
        val originalTarget = link.id ?: link.path?.let(pathIdMap::get)
        val mapped = originalTarget?.let(boardIdMap::get)
        return button.copy(
            loadBoard = ObfLoadBoard(
                id = mapped ?: link.id,
                name = link.name,
                url = link.url,
                path = if (mapped == null) link.path else null,
                dataUrl = link.dataUrl,
                extensions = link.extensions
            )
        )
    }

    private suspend fun persistImage(
        image: ObfImage,
        setId: String,
        archive: ArchiveReader?,
        archiveNames: Set<String>,
        warnings: MutableList<BoardImportWarning>,
        storedPaths: MutableList<String>,
        remotePackage: Boolean,
    ): ObfImage {
        val storage = fileStorage
        val storedPath = "boardsets/$setId/images/${safeId(image.id)}.${extensionFor(image.contentType, image.path, "bin")}"
        val stored = storage != null && persistResolvedMedia(
            image.id, image.data, image.dataUrl, image.path, image.url,
            archive, archiveNames, warnings, storage, storedPath, storedPaths, remotePackage
        )
        if (!stored) {
            if (!image.path.isNullOrBlank() && image.data.isNullOrBlank() && image.dataUrl.isNullOrBlank() && image.url.isNullOrBlank()) {
                throw ImportFailure(BoardImportErrorCode.MEDIA_UNRESOLVED, "Image '${image.id}' path '${image.path}' could not be read")
            }
            return if (image.path != null && image.path !in archiveNames) image.copy(path = null) else image
        }
        return image.copy(path = storedPath, data = null, dataUrl = null, url = null)
    }

    private suspend fun persistSound(
        sound: ObfSound,
        setId: String,
        archive: ArchiveReader?,
        archiveNames: Set<String>,
        warnings: MutableList<BoardImportWarning>,
        storedPaths: MutableList<String>,
        remotePackage: Boolean,
    ): ObfSound {
        val storage = fileStorage
        val storedPath = "boardsets/$setId/sounds/${safeId(sound.id)}.${extensionFor(sound.contentType, sound.path, "mp3")}"
        val stored = storage != null && persistResolvedMedia(
            sound.id, sound.data, sound.dataUrl, sound.path, sound.url,
            archive, archiveNames, warnings, storage, storedPath, storedPaths, remotePackage
        )
        if (!stored) {
            if (!sound.path.isNullOrBlank() && sound.data.isNullOrBlank() && sound.dataUrl.isNullOrBlank() && sound.url.isNullOrBlank()) {
                throw ImportFailure(BoardImportErrorCode.MEDIA_UNRESOLVED, "Sound '${sound.id}' path '${sound.path}' could not be read")
            }
            return if (sound.path != null && sound.path !in archiveNames) sound.copy(path = null) else sound
        }
        return sound.copy(path = storedPath, data = null, dataUrl = null, url = null)
    }

    /** Resolves data → local path → data URL → URL, warning and continuing after each unavailable source. */
    private suspend fun persistResolvedMedia(
        id: String,
        data: String?,
        dataUrl: String?,
        path: String?,
        url: String?,
        archive: ArchiveReader?,
        archiveNames: Set<String>,
        warnings: MutableList<BoardImportWarning>,
        storage: FileStorage,
        destination: String,
        storedPaths: MutableList<String>,
        remotePackage: Boolean,
    ): Boolean {
        if (!data.isNullOrBlank()) {
            val payload = data.substringAfter("base64,", data)
            Base64Decoder.decodeOrNull(payload)?.takeIf { it.isNotEmpty() }?.let {
                if (destination !in storedPaths) storedPaths += destination
                storage.saveBytes(destination, it)
                return true
            }
            warnings += BoardImportWarning("malformed_data", "Media '$id' has malformed inline data")
        }
        // Prefer the archive's local copy over remote references: Quick Core and other
        // exported board sets ship every image as a `path` inside the OBZ alongside a remote
        // `data_url`, and fetching those one-by-one makes imports needlessly slow and
        // network-dependent. The remote sources remain the fallback when no local copy exists.
        if (!path.isNullOrBlank()) {
            if (archive != null && path in archiveNames) {
                try {
                    if (destination !in storedPaths) storedPaths += destination
                    storage.saveStream(destination) { emit ->
                        archive.readEntry(path, limits.maxMediaEntryBytes, emit)
                    }
                    return true
                } catch (error: ArchiveReadException) {
                    runCatching { storage.delete(destination) }
                    if (error.error == ArchiveReadError.ENTRY_TOO_LARGE) throw error
                    warnings += BoardImportWarning("unreadable_path", "Media '$id' path '$path' could not be read")
                }
            } else if (archive != null) {
                warnings += BoardImportWarning("missing_path", "Media '$id' path '$path' is missing")
            } else {
                storage.loadBytes(path)?.let {
                    if (destination !in storedPaths) storedPaths += destination
                    storage.saveBytes(destination, it)
                    return true
                }
            }
        }
        // Remote packages may refer to external media, but syncing them only fetches the OBZ.
        if (remotePackage) return false
        if (!dataUrl.isNullOrBlank()) {
            runCatching { urlLoader.load(dataUrl) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                if (destination !in storedPaths) storedPaths += destination
                storage.saveBytes(destination, it)
                return true
            }
            warnings += BoardImportWarning("unavailable_data_url", "Media '$id' data URL was unavailable")
        }
        if (!url.isNullOrBlank()) {
            runCatching { urlLoader.load(url) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                if (destination !in storedPaths) storedPaths += destination
                storage.saveBytes(destination, it)
                return true
            }
            // Keeping a remote reference is a valid fallback; runtime may load it later.
            warnings += BoardImportWarning("deferred_url", "Media '$id' will use its remote URL")
        }
        return false
    }

    private fun mediaPaths(board: ObfBoard): List<String> =
        board.images.mapNotNull { it.path } + board.sounds.mapNotNull { it.path }

    private fun remoteMediaUrl(url: String?, warnings: MutableList<BoardImportWarning>): String? {
        if (url.isNullOrBlank()) return null
        val parsed = runCatching { io.ktor.http.Url(url.trim()) }.getOrNull()
        if (parsed?.protocol == io.ktor.http.URLProtocol.HTTPS && parsed.host.isNotBlank() &&
            parsed.user == null && parsed.password == null
        ) return url.trim()
        warnings += BoardImportWarning("unsafe_media_url", "Remote Screen media requires an HTTPS URL")
        return null
    }

    private fun extensionFor(contentType: String?, path: String?, default: String): String = when (contentType?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/svg+xml" -> "svg"
        "image/webp" -> "webp"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        else -> path?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: default
    }

    private fun safeId(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "media" }

    private fun newId(prefix: String): String {
        val suffix = buildString(12) { repeat(12) { append("0123456789abcdef"[Random.nextInt(16)]) } }
        return "${prefix}_$suffix"
    }

    private fun failure(
        code: BoardImportErrorCode,
        context: String,
        warnings: List<BoardImportWarning> = emptyList()
    ) = BoardImportResult.Failure(code, context, warnings.toList())

    private class ImportFailure(val code: BoardImportErrorCode, message: String) : Exception(message)

    private companion object {
        val SAFE_BOARD_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
