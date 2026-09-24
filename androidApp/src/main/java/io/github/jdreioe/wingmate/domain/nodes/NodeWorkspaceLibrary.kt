package io.github.jdreioe.wingmate.domain.nodes

import kotlinx.serialization.Serializable

@Serializable
data class NodeDocument(
    val graph: SentenceGraph = SentenceGraph(),
    val selected: Int? = null,
    val draft: String = "<speak></speak>",
)

/** Vocabulary identity ignores case; each occurrence retains its own spelling. */
@Serializable
data class NodeVocabularyWord(val text: String) {
    val key: String get() = text.lowercase()
}

@Serializable
data class SavedNodeWorkspace(val id: String, val name: String, val document: NodeDocument = NodeDocument())

@Serializable
data class NodeWorkspaceLibrary(
    val version: Int = 1,
    val activeId: String = "main",
    val workspaces: List<SavedNodeWorkspace> = listOf(SavedNodeWorkspace("main", "")),
    val vocabulary: List<NodeVocabularyWord> = emptyList(),
    val frequencies: Map<String, Int> = emptyMap(),
    val creditedStart: Int? = null,
    val creditedWord: String? = null,
) {
    val active: SavedNodeWorkspace get() = workspaces.first { it.id == activeId }

    fun withDocument(document: NodeDocument) = copy(workspaces = workspaces.map {
        if (it.id == activeId) it.copy(document = document) else it
    })

    fun withVocabulary(words: List<String>): NodeWorkspaceLibrary = copy(
        vocabulary = (vocabulary + words.map(::NodeVocabularyWord)).distinctBy { it.key },
    )

    fun isValid(): Boolean = version == 1 && workspaces.isNotEmpty() && workspaces.size <= 100 &&
        workspaces.map { it.id }.distinct().size == workspaces.size && workspaces.any { it.id == activeId } &&
        vocabulary.all { it.text.isNotBlank() } && vocabulary.distinctBy { it.key }.size == vocabulary.size &&
        frequencies.values.all { it >= 0 } && workspaces.all { workspace ->
            val nodes = workspace.document.graph.nodes
            nodes.size <= 100 && nodes.map { it.id }.distinct().size == nodes.size &&
                nodes.all { node ->
                    node.x.isFinite() && node.y.isFinite() &&
                        (node.next == null || nodes.any { it.id == node.next }) &&
                        nodes.count { it.next == node.id } <= 1 &&
                        when (val content = node.content) {
                            is SentenceContent.Word -> content.text.isNotBlank() && vocabulary.any { it.key == content.text.lowercase() }
                            is SentenceContent.Pause -> content.milliseconds in 1..10000
                        }
                } && nodes.none { node ->
                    node.next?.let { next -> workspace.document.graph.chain(next).any { it.id == node.id } } == true
                }
        }
}

/** App-private storage. Failures propagate without exposing document contents. */
interface NodeWorkspaceStorage {
    suspend fun load(): NodeWorkspaceLibrary?
    suspend fun save(library: NodeWorkspaceLibrary)
}
