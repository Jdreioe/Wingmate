package io.github.jdreioe.wingmate.desktop

import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.ZipBuilder
import io.github.jdreioe.wingmate.domain.obf.requireValid
import io.github.jdreioe.wingmate.platform.readEntryBytes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock

@Serializable
private data class BackupPayload(
    val boards: List<ObfBoard>,
    val boardSets: List<ObfBoardSet>,
    val phrases: List<Phrase>,
    val categories: List<CategoryItem> = emptyList(),
    val settings: Settings,
    val voices: List<Voice>,
    val selectedVoice: Voice?,
    val history: List<SaidText>,
    val dictionary: List<PronunciationEntry>,
    val communicationSession: CommunicationSessionSnapshot = CommunicationSessionSnapshot(),
)

@Serializable
private data class BackupFile(val path: String, val size: Long, val sha256: String)

@Serializable
private data class BackupManifest(
    val format: String = "wingmate-backup",
    val version: Int = 1,
    val createdAt: Long,
    val payload: BackupFile,
    val media: List<BackupFile>,
)

/** Reads and writes the same version-1 archive contract as CompleteBackupManager. */
internal class DesktopBackup(
    private val store: DesktopStore,
    private val media: DesktopMediaStorage,
    private val fileAccess: DesktopFileAccess = DesktopFileAccess(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    fun export(path: String) = runBlocking {
        val snapshot = store.snapshot()
        val sources = linkedMapOf<String, String>()
        fun archived(source: String?): String? {
            val value = source?.takeIf(String::isNotBlank) ?: return null
            sources.entries.firstOrNull { it.value == value }?.let { return it.key }
            val safeName = value.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "asset" }
            val name = "media/${value.encodeToByteArray().toByteString().sha256().hex().take(16)}-$safeName"
            sources[name] = value
            return name
        }
        fun localUrl(url: String?) = url?.let { if (it.startsWith("file://") || it.startsWith('/')) archived(it.removePrefix("file://")) else it }
        fun Message.portable() = copy(
            parts = parts.map { it.copy(recordingPath = archived(it.recordingPath)) },
            editProvenance = editProvenance.map { it.copy(originalPart = it.originalPart.copy(recordingPath = archived(it.originalPart.recordingPath))) },
        )
        val payload = BackupPayload(
            boards = snapshot.boards.map { board -> board.copy(
                images = board.images.map { it.copy(path = archived(it.path), url = localUrl(it.url)) },
                sounds = board.sounds.map { it.copy(path = archived(it.path), url = localUrl(it.url)) },
            ) },
            boardSets = snapshot.boardSets,
            phrases = snapshot.phrases.map { it.copy(recordingPath = archived(it.recordingPath), imageUrl = localUrl(it.imageUrl)) },
            categories = snapshot.categories, settings = snapshot.settings, voices = snapshot.voices,
            selectedVoice = snapshot.selectedVoice,
            history = snapshot.history.map { it.copy(audioFilePath = archived(it.audioFilePath)) },
            dictionary = snapshot.pronunciations,
            communicationSession = snapshot.communicationSession.copy(
                activeMessage = snapshot.communicationSession.activeMessage.portable(),
                heldMessage = snapshot.communicationSession.heldMessage?.portable(),
            ),
        )
        val payloadBytes = json.encodeToString(payload).encodeToByteArray()
        val mediaEntries = sources.map { (archivePath, sourcePath) ->
            val source = sourcePath.removePrefix("file://")
            val bytes = media.loadBytes(source) ?: runCatching { fileSystem.read(source.toPath()) { readByteArray() } }.getOrNull()
                ?: error("Backup media is missing")
            archivePath to bytes
        }
        val manifest = BackupManifest(
            createdAt = Clock.System.now().toEpochMilliseconds(),
            payload = payloadBytes.description(PAYLOAD_PATH),
            media = mediaEntries.map { (name, bytes) -> bytes.description(name) },
        )
        val bytes = ZipBuilder.build(listOf(
            MANIFEST_PATH to json.encodeToString(manifest).encodeToByteArray(),
            PAYLOAD_PATH to payloadBytes,
        ) + mediaEntries).getOrThrow()
        fileSystem.write(path.toPath()) { write(bytes) }
    }

    fun restore(path: String) = runBlocking {
        val archive = fileAccess.openArchive(path) ?: error("Could not open backup")
        try {
            val entries = archive.entries()
            require(entries.size <= 4_096 && entries.map { it.name }.distinct().size == entries.size) { "Invalid backup entries" }
            require(entries.all { entry ->
                entry.name.isNotBlank() && !entry.name.startsWith('/') && '\\' !in entry.name && '\u0000' !in entry.name &&
                    entry.name.split('/').none { it == ".." } && !entry.isEncrypted && entry.uncompressedSize in 0..MAX_MEDIA
            }) { "Unsafe or oversized backup entry" }
            require(entries.sumOf { it.uncompressedSize } <= 2L * 1024 * 1024 * 1024) { "Backup is too large" }
            val manifestBytes = archive.readEntryBytes(MANIFEST_PATH, MAX_JSON)
            val manifest = json.decodeFromString<BackupManifest>(manifestBytes.decodeToString())
            require(manifest.format == "wingmate-backup" && manifest.version == 1) { "Unsupported backup" }
            val available = entries.mapTo(mutableSetOf()) { it.name }
            val declared = listOf(manifest.payload.path) + manifest.media.map { it.path }
            require(declared.distinct().size == declared.size && declared.all { it in available }) { "Backup references a missing file" }
            val payloadBytes = archive.readEntryBytes(manifest.payload.path, MAX_JSON)
            require(payloadBytes.description(manifest.payload.path) == manifest.payload) { "Backup data checksum does not match" }
            var payload = json.decodeFromString<BackupPayload>(payloadBytes.decodeToString())
            validatePayload(payload)
            val restored = mutableMapOf<String, String>()
            manifest.media.forEach { file ->
                val bytes = archive.readEntryBytes(file.path, MAX_MEDIA)
                require(bytes.description(file.path) == file) { "Backup media checksum does not match" }
                val destination = "restored/${file.path.removePrefix("media/")}"
                media.saveBytes(destination, bytes)
                restored[file.path] = destination
            }
            fun local(pathValue: String?): String? = pathValue?.let { restored[it] ?: it.takeUnless { value -> value.startsWith("media/") } }
            fun localUrl(url: String?): String? = url?.let { restored[it]?.let { path -> "file://${media.resolve(path)}" } ?: it }
            fun Message.local() = copy(
                parts = parts.map { it.copy(recordingPath = local(it.recordingPath)) },
                editProvenance = editProvenance.map { it.copy(originalPart = it.originalPart.copy(recordingPath = local(it.originalPart.recordingPath))) },
            )
            payload = payload.copy(
                boards = payload.boards.map { board -> board.copy(
                    images = board.images.map { it.copy(path = local(it.path), url = localUrl(it.url)) },
                    sounds = board.sounds.map { it.copy(path = local(it.path), url = localUrl(it.url)) },
                ) },
                phrases = payload.phrases.map { it.copy(recordingPath = local(it.recordingPath), imageUrl = localUrl(it.imageUrl)) },
                history = payload.history.map { it.copy(audioFilePath = local(it.audioFilePath)) },
                communicationSession = payload.communicationSession.copy(
                    activeMessage = payload.communicationSession.activeMessage.local(),
                    heldMessage = payload.communicationSession.heldMessage?.local(),
                ),
            )
            store.restore(DesktopSnapshot(
                boards = payload.boards, boardSets = payload.boardSets, settings = payload.settings,
                pronunciations = payload.dictionary, phrases = payload.phrases, categories = payload.categories,
                voices = payload.voices, selectedVoice = payload.selectedVoice, history = payload.history,
                communicationSession = payload.communicationSession,
                // Recent files describe this installation, not the backup.
                recentFiles = store.snapshot().recentFiles,
            ))
        } finally { archive.close() }
    }

    private fun ByteArray.description(path: String) = BackupFile(path, size.toLong(), toByteString().sha256().hex())

    private fun validatePayload(payload: BackupPayload) {
        require(payload.boards.map { it.id }.distinct().size == payload.boards.size) { "Duplicate Page IDs" }
        require(payload.boardSets.map { it.id }.distinct().size == payload.boardSets.size) { "Duplicate Screen IDs" }
        val boards = payload.boards.associateBy { it.id }
        payload.boardSets.forEach { set ->
            BoardSetGraph(set, set.boardIds.map { requireNotNull(boards[it]) }).requireValid()
        }
        require(payload.boardSets.count { it.kind == ScreenKind.Typing } <= 1) { "Duplicate Typing Screen" }
    }

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val PAYLOAD_PATH = "data/user-data.json"
        const val MAX_JSON = 64L * 1024 * 1024
        const val MAX_MEDIA = 512L * 1024 * 1024
    }
}
