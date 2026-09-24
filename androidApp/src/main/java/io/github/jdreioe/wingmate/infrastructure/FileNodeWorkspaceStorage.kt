package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.nodes.NodeWorkspaceLibrary
import io.github.jdreioe.wingmate.domain.nodes.NodeWorkspaceStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Uses FileStorage's atomic replacement so an interrupted write keeps the previous library. */
internal class FileNodeWorkspaceStorage(private val files: FileStorage, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : NodeWorkspaceStorage {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): NodeWorkspaceLibrary? = withContext(ioDispatcher) {
        files.load(FILE_NAME)?.let { source ->
            json.decodeFromString<NodeWorkspaceLibrary>(source).also {
                check(it.isValid()) { "Node workspace data is invalid" }
            }
        }
    }

    override suspend fun save(library: NodeWorkspaceLibrary) = withContext(ioDispatcher) {
        check(library.isValid()) { "Node workspace data is invalid" }
        files.save(FILE_NAME, json.encodeToString(library))
    }

    companion object {
        const val FILE_NAME = "nodes/workspaces.json"
    }
}
