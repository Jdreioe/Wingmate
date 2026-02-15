package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.platform.FilePicker
import kotlinx.datetime.Clock
import java.util.UUID

class BoardImportService(
    private val obfParser: ObfParser,
    private val phraseRepository: PhraseRepository,
    private val categoryRepository: CategoryRepository,
    private val filePicker: FilePicker
) {

    suspend fun importBoards(isModern: Boolean): Boolean {
        // 1. Pick file
        val filePath = filePicker.pickFile("Select Board File", listOf("obf", "obz", "json")) ?: return false
        val isObz = filePath.lowercase().endsWith(".obz")

        if (isObz) {
            return importObz(filePath, isModern)
        } else {
            return importSingleObf(filePath, isModern)
        }
    }

    private suspend fun importSingleObf(filePath: String, isModern: Boolean): Boolean {
        val content = filePicker.readFileAsText(filePath) ?: return false
        val boardResult = obfParser.parseBoard(content)
        val board = boardResult.getOrNull() ?: return false
        
        // Single file treated as Root. Remapping context is local to this single board.
        // But to avoid collisions, we must remap its ID.
        importBoardsWithRemapping(listOf(board to true), isModern, emptyMap())
        return true
    }

    private suspend fun importObz(filePath: String, isModern: Boolean): Boolean {
        val entries = filePicker.readZipEntries(filePath) ?: return false
        
        val manifestContent = entries["manifest.json"]?.decodeToString() ?: return false
        val manifestResult = obfParser.parseManifest(manifestContent)
        val manifest = manifestResult.getOrNull() ?: return false

        val zipImages = entries.filterKeys { !it.endsWith(".json") && !it.endsWith(".obf") }
        
        val boardsToImport = mutableListOf<Pair<ObfBoard, Boolean>>() // Board, isRoot

        // Parse Root
        val rootPath = manifest.root
        val rootContent = entries[rootPath]?.decodeToString()
        if (rootContent != null) {
            val rootBoard = obfParser.parseBoard(rootContent).getOrNull()
            if (rootBoard != null) {
                boardsToImport.add(rootBoard to true)
            }
        }

        // Parse Others
        manifest.paths.boards.forEach { (id, path) ->
            if (path != rootPath) { 
                val content = entries[path]?.decodeToString()
                if (content != null) {
                    val board = obfParser.parseBoard(content).getOrNull()
                    if (board != null) {
                        boardsToImport.add(board to false)
                    }
                }
            }
        }

        importBoardsWithRemapping(boardsToImport, isModern, zipImages)
        return true
    }

    private suspend fun importBoardsWithRemapping(
        boards: List<Pair<ObfBoard, Boolean>>,
        isModern: Boolean,
        zipImages: Map<String, ByteArray>
    ) {
        // 1. Build ID Map (Old ID -> New UUID)
        // This ensures every import gets fresh IDs, avoiding PK collisions.
        val idMap = mutableMapOf<String, String>()
        boards.forEach { (board, _) ->
            if (board.id.isNotEmpty()) {
                idMap[board.id] = UUID.randomUUID().toString()
            }
        }

        // 2. Import each board using the map
        boards.forEach { (board, isRoot) ->
            importBoard(board, isRoot, isModern, zipImages, idMap)
        }
    }

    private suspend fun importBoard(
        board: ObfBoard, 
        isRoot: Boolean, 
        isModern: Boolean,
        zipImages: Map<String, ByteArray>,
        idMap: Map<String, String>
    ) {
        // Resolve new Board ID
        val originalBoardId = board.id
        val newBoardId = idMap[originalBoardId] ?: UUID.randomUUID().toString()
        val boardName = board.name ?: "Imported Board"
        
        val createCategoryChip = isModern
        val createGridFolder = !isModern && isRoot
        
        if (createCategoryChip || createGridFolder) {
            val folderPhrase = Phrase(
                id = newBoardId,
                text = boardName,
                name = boardName,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                parentId = null, // Top level
                linkedBoardId = newBoardId, // Self-link
                isGridItem = createGridFolder
            )
            phraseRepository.add(folderPhrase)
            
            if (createCategoryChip) {
                categoryRepository.add(CategoryItem(
                    id = folderPhrase.id,
                    name = boardName,
                    isFolder = true
                ))
            }
        }
        
        val contextId = newBoardId 
        val boardImages = board.images.associateBy { it.id }
        
        board.buttons.forEach { button ->
            importButton(button, contextId, boardImages, zipImages, isModern, idMap)
        }
    }

    private suspend fun importButton(
        button: ObfButton, 
        parentId: String, 
        boardImages: Map<String, ObfImage>,
        zipImages: Map<String, ByteArray>,
        isModern: Boolean,
        idMap: Map<String, String>
    ) {
        var imageUrl = button.imageId?.let { id ->
            val img = boardImages[id]
            val path = img?.path
            if (path != null && zipImages.containsKey(path)) {
                 // skip bytes for now, just null or placeholder?
                 // Ideally we'd write to file.
                null
            } else {
                img?.url ?: img?.data
            }
        }

        val loadBoard = button.loadBoard
        if (loadBoard != null) {
            // Remap the link target!
            val originalLinkId = loadBoard.id
            // If originalLink is null, check path?
            // Manifest logic uses IDs.
            
            val newLinkId = if (originalLinkId != null) {
                idMap[originalLinkId] ?: UUID.randomUUID().toString() // Fallback if unknown
            } else {
                 UUID.randomUUID().toString()
            }
            
            val folderName = button.label ?: loadBoard.name ?: "Folder"
            
            val folderPhrase = Phrase(
                id = UUID.randomUUID().toString(),
                text = folderName,
                imageUrl = imageUrl,
                backgroundColor = button.backgroundColor,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                parentId = parentId,
                linkedBoardId = newLinkId,
                isGridItem = true
            )
            phraseRepository.add(folderPhrase)
        } else {
             val phrase = Phrase(
                id = UUID.randomUUID().toString(), // Always new UUID for buttons to avoid collision
                text = button.label ?: "",
                name = button.vocalization,
                imageUrl = imageUrl,
                backgroundColor = button.backgroundColor,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                parentId = parentId,
                isGridItem = true
            )
            phraseRepository.add(phrase)
        }
    }
}
