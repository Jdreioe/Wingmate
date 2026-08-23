package io.github.jdreioe.wingmate.domain

/**
 * Returns a phrase and all of its descendants in parent-first order.
 *
 * The order lets callers restore a deleted hierarchy by adding each item back
 * with its original id, since a child never arrives before its parent.
 */
fun phraseSubtree(phrases: List<Phrase>, rootId: String): List<Phrase> {
    val root = phrases.firstOrNull { it.id == rootId } ?: return emptyList()
    val result = mutableListOf(root)
    val visited = mutableSetOf(root.id)
    val pendingParents = ArrayDeque(listOf(root.id))

    while (pendingParents.isNotEmpty()) {
        val parentId = pendingParents.removeFirst()
        phrases.filter { it.parentId == parentId && visited.add(it.id) }.forEach { child ->
            result += child
            pendingParents += child.id
        }
    }
    return result
}
