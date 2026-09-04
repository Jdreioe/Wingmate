package io.github.jdreioe.wingmate.desktop

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Serializable
internal data class DesktopSnapshot(
    val boards: List<ObfBoard> = emptyList(),
    val boardSets: List<ObfBoardSet> = emptyList(),
    val settings: Settings = Settings(),
    val pronunciations: List<PronunciationEntry> = emptyList(),
    val phrases: List<Phrase> = emptyList(),
    val categories: List<CategoryItem> = emptyList(),
    val voices: List<Voice> = emptyList(),
    val selectedVoice: Voice? = null,
    val history: List<SaidText> = emptyList(),
    val communicationSession: CommunicationSessionSnapshot = CommunicationSessionSnapshot(),
    val recentFiles: List<String> = emptyList(),
    /**
     * The Rust shell's iced palette name. Desktop-only presentation state, so
     * it stays out of the shared [Settings] the other clients read.
     */
    val desktopTheme: String = "system",
)

/** One atomic JSON store for desktop. Domain repositories remain the API used by shared Kotlin. */
internal class DesktopStore(
    dataDirectory: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true },
) : BoardRepository, BoardSetRepository, SettingsRepository, PronunciationDictionaryRepository {
    private val root: Path = dataDirectory.toPath()
    private val stateFile = root / "state.json"
    private val temporaryStateFile = root / "state.json.tmp"
    private var state = loadSnapshot()

    init {
        fileSystem.createDirectories(root)
    }

    private fun loadSnapshot(): DesktopSnapshot = runCatching {
        if (!fileSystem.exists(stateFile)) DesktopSnapshot()
        else json.decodeFromString<DesktopSnapshot>(fileSystem.read(stateFile) { readUtf8() })
    }.getOrDefault(DesktopSnapshot())

    private fun persist() {
        fileSystem.createDirectories(root)
        fileSystem.write(temporaryStateFile) { writeUtf8(json.encodeToString(state)) }
        if (fileSystem.exists(stateFile)) fileSystem.delete(stateFile)
        fileSystem.atomicMove(temporaryStateFile, stateFile)
    }

    fun recentFiles(): List<String> = state.recentFiles

    fun desktopTheme(): String = state.desktopTheme

    fun setDesktopTheme(value: String) {
        state = state.copy(desktopTheme = value)
        persist()
    }

    fun remember(path: String) {
        state = state.copy(recentFiles = (listOf(path) + state.recentFiles.filterNot { it == path }).take(8))
        persist()
    }

    fun snapshot(): DesktopSnapshot = state

    fun restore(restored: DesktopSnapshot) {
        state = restored
        persist()
    }

    override suspend fun getBoard(id: String) = state.boards.firstOrNull { it.id == id }
    override suspend fun listBoards() = state.boards
    override suspend fun saveBoard(board: ObfBoard) {
        state = state.copy(boards = state.boards.filterNot { it.id == board.id } + board)
        persist()
    }
    override suspend fun saveBoards(boards: List<ObfBoard>) {
        val ids = boards.mapTo(mutableSetOf()) { it.id }
        state = state.copy(boards = state.boards.filterNot { it.id in ids } + boards)
        persist()
    }
    override suspend fun deleteBoard(id: String) {
        state = state.copy(boards = state.boards.filterNot { it.id == id })
        persist()
    }
    override suspend fun getBoardSet(id: String) = state.boardSets.firstOrNull { it.id == id }
    override suspend fun listBoardSets() = state.boardSets.sortedByDescending { it.updatedAt }
    override suspend fun saveBoardSet(boardSet: ObfBoardSet) {
        state = state.copy(boardSets = state.boardSets.filterNot { it.id == boardSet.id } + boardSet)
        persist()
    }
    override suspend fun deleteBoardSet(id: String) {
        state = state.copy(boardSets = state.boardSets.filterNot { it.id == id })
        persist()
    }
    override suspend fun get() = state.settings
    override suspend fun update(settings: Settings): Settings {
        state = state.copy(settings = settings)
        persist()
        return settings
    }
    override suspend fun getAll() = state.pronunciations
    override suspend fun add(entry: PronunciationEntry) {
        state = state.copy(pronunciations = state.pronunciations.filterNot { it.word == entry.word } + entry)
        persist()
    }
    override suspend fun delete(word: String) {
        state = state.copy(pronunciations = state.pronunciations.filterNot { it.word == word })
        persist()
    }
    override suspend fun clear() {
        state = state.copy(pronunciations = emptyList())
        persist()
    }
}

internal class DesktopMediaStorage(
    dataDirectory: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : io.github.jdreioe.wingmate.domain.FileStorage {
    private val root = dataDirectory.toPath() / "media"
    init { fileSystem.createDirectories(root) }
    fun resolve(fileName: String): String = (root / fileName).toString()
    override suspend fun save(fileName: String, content: String) = saveBytes(fileName, content.encodeToByteArray())
    override suspend fun load(fileName: String) = loadBytes(fileName)?.decodeToString()
    override suspend fun saveBytes(fileName: String, content: ByteArray) {
        val target = root / fileName
        target.parent?.let(fileSystem::createDirectories)
        fileSystem.write(target) { write(content) }
    }
    override suspend fun loadBytes(fileName: String) = (root / fileName).let {
        if (fileSystem.exists(it)) fileSystem.read(it) { readByteArray() } else null
    }
    override suspend fun exists(fileName: String) = fileSystem.exists(root / fileName)
    override suspend fun delete(fileName: String) {
        val target = root / fileName
        if (fileSystem.exists(target)) fileSystem.delete(target)
    }
}
