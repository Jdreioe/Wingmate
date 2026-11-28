package io.github.jdreioe.wingmate.domain.obf

/** Default upper bound on matches returned by [BoardSetGraph.findWords]. */
const val DEFAULT_WORD_FINDER_LIMIT = 50

/**
 * One Button activation along a Word Finder navigation route.
 *
 * Intermediate steps carry [targetBoardId] pointing to the next Page in the route;
 * the final step identifies the matching target Button itself (which may also link
 * to another Page when the Communicator searched for a category Button).
 */
data class WordFinderStep(
    val boardId: String,
    val boardName: String,
    val buttonId: String,
    val buttonLabel: String,
    val targetBoardId: String? = null,
    val isHidden: Boolean = false,
)

/**
 * A vocabulary Button found by [BoardSetGraph.findWords], together with the
 * shortest navigation [steps] needed to reach and activate it.
 */
data class WordFinderMatch(
    val targetBoardId: String,
    val targetBoardName: String,
    val buttonId: String,
    val label: String,
    val vocalization: String? = null,
    val isHidden: Boolean = false,
    val startsFromRoot: Boolean = false,
    val steps: List<WordFinderStep>,
) {
    /** The Page where guided navigation begins (current Page or starting Page). */
    val startingBoardId: String? get() = steps.firstOrNull()?.boardId

    /** Number of Page transitions before the target Button is visible on screen. */
    val hopCount: Int get() = (steps.size - 1).coerceAtLeast(0)

    /** Whether any Button along the route (including the target) is hidden. */
    val hasHiddenStep: Boolean get() = steps.any { it.isHidden }

    /** Ordered display segments starting with the entry Page name, e.g. `["Home", "Food", "Apple"]`. */
    val pathSegments: List<String>
        get() = if (steps.isEmpty()) emptyList() else listOf(steps.first().boardName) + steps.map { it.buttonLabel }

    /** Human-readable breadcrumb path, e.g. `Home → Food → Apple`. */
    fun formattedPath(separator: String = " → "): String = pathSegments.joinToString(separator)
}

/**
 * Ephemeral step-by-step guidance state while the Communicator or Supporter follows a
 * [WordFinderMatch] through linked Pages. Never mutates Screen vocabulary.
 */
data class WordFinderGuidance(
    val match: WordFinderMatch,
    val currentStepIndex: Int = 0,
) {
    val currentStep: WordFinderStep? get() = match.steps.getOrNull(currentStepIndex)
    val totalSteps: Int get() = match.steps.size
    val isOnFinalStep: Boolean get() = match.steps.isNotEmpty() && currentStepIndex == match.steps.lastIndex

    /** The Button to highlight on [boardId], or `null` when [boardId] is not the active step's Page. */
    fun highlightedButtonIdFor(boardId: String?): String? {
        if (boardId == null) return null
        val step = currentStep ?: return null
        return if (step.boardId == boardId) step.buttonId else null
    }

    /**
     * Advances guidance when [buttonId] is activated on [boardId].
     * Returns `null` once the final target Button is activated; leaves guidance unchanged
     * when a different Button is tapped.
     */
    fun onButtonActivated(boardId: String?, buttonId: String): WordFinderGuidance? {
        val step = currentStep ?: return null
        if (step.boardId != boardId || step.buttonId != buttonId) return this
        val nextIndex = currentStepIndex + 1
        return if (nextIndex >= match.steps.size) null else copy(currentStepIndex = nextIndex)
    }

    /**
     * Syncs the active step when the workspace moves to [boardId] (for example via a link,
     * Back, Home, swipe, or an Edit-mode Page chip).
     */
    fun onBoardChanged(boardId: String?): WordFinderGuidance {
        if (boardId == null) return this
        val matchingIndex = match.steps.indexOfFirst { it.boardId == boardId }
        return if (matchingIndex >= 0 && matchingIndex != currentStepIndex) {
            copy(currentStepIndex = matchingIndex)
        } else {
            this
        }
    }
}

private val WORD_BOUNDARY_SPLIT = Regex("[\\s\\p{Punct}]+")

/**
 * Searches this Screen for Buttons whose localized label or vocalization matches [query]
 * (case-insensitive) and returns the shortest navigation path to each match.
 *
 * Shortest paths are computed via breadth-first search from [currentBoardId] first; any
 * Page unreachable from [currentBoardId] falls back to a breadth-first search from the
 * Screen's starting Page ([ObfBoardSet.rootBoardId]).
 *
 * When [includeHidden] is `false`, hidden Buttons are excluded from results and their Page
 * links are never traversed. When [includeHidden] is `true`, hidden Buttons and links are
 * included with [WordFinderMatch.isHidden] / [WordFinderStep.isHidden] metadata, while
 * still preferring visible links at equal hop depth.
 */
fun BoardSetGraph.findWords(
    query: String,
    currentBoardId: String?,
    locale: String? = null,
    includeHidden: Boolean = false,
    maxResults: Int = DEFAULT_WORD_FINDER_LIMIT,
): List<WordFinderMatch> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty() || maxResults <= 0) return emptyList()

    val boardMap = boardsById
    val rootId = boardSet.rootBoardId

    fun boardDisplayName(board: ObfBoard): String =
        resolveObfLocalizedString(board.strings, locale, board.name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: board.id

    fun placedButtons(board: ObfBoard): List<ObfButton> {
        val grid = board.grid
        if (grid == null || board.isAbsoluteLayout) return board.buttons
        val byId = board.buttons.associateBy { it.id }
        val seen = HashSet<String>()
        return buildList {
            for (field in grid.fieldItems()) {
                val id = field.buttonId ?: continue
                if (seen.add(id)) {
                    byId[id]?.let(::add)
                }
            }
        }
    }

    fun navigationTarget(button: ObfButton): ObfBoard? {
        val effects = parseObfButtonActions(button)
        return when {
            effects.isEmpty() -> resolveLinkedBoard(button.loadBoard)
            effects.size == 1 && effects.single() === ObfButtonActionEffect.Home -> boardMap[rootId]
            else -> null
        }
    }

    fun bfsShortestPaths(startId: String): LinkedHashMap<String, List<WordFinderStep>> {
        val paths = LinkedHashMap<String, List<WordFinderStep>>()
        if (startId !in boardMap) return paths
        val queue = ArrayDeque<Pair<String, List<WordFinderStep>>>()
        paths[startId] = emptyList()
        queue.addLast(startId to emptyList())

        while (queue.isNotEmpty()) {
            val (boardId, pathToBoard) = queue.removeFirst()
            val board = boardMap[boardId] ?: continue
            val boardName = boardDisplayName(board)
            val buttons = placedButtons(board)
            val traversable = if (includeHidden) {
                // Prefer visible links over hidden links when both reach a Page in one hop.
                buttons.filterNot { it.hidden } + buttons.filter { it.hidden }
            } else {
                buttons.filterNot { it.hidden }
            }
            for (button in traversable) {
                val target = navigationTarget(button) ?: continue
                if (target.id in paths) continue
                val stepLabel = resolveObfLocalizedString(board.strings, locale, button.label)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: resolveObfLocalizedString(board.strings, locale, button.vocalization)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    ?: boardDisplayName(target)
                val nextPath = pathToBoard + WordFinderStep(
                    boardId = board.id,
                    boardName = boardName,
                    buttonId = button.id,
                    buttonLabel = stepLabel,
                    targetBoardId = target.id,
                    isHidden = button.hidden,
                )
                paths[target.id] = nextPath
                queue.addLast(target.id to nextPath)
            }
        }
        return paths
    }

    val fromCurrent = currentBoardId?.takeIf { it in boardMap }?.let(::bfsShortestPaths)
        ?: LinkedHashMap()
    val fromRoot = if (rootId in boardMap && rootId != currentBoardId) {
        bfsShortestPaths(rootId)
    } else {
        LinkedHashMap()
    }

    val scoredMatches = mutableListOf<ScoredWordFinderMatch>()

    fun collectMatches(
        boardPaths: Map<String, List<WordFinderStep>>,
        skipBoardIds: Set<String>,
        startsFromRoot: Boolean,
    ) {
        for ((boardId, pathToBoard) in boardPaths) {
            if (boardId in skipBoardIds) continue
            val board = boardMap[boardId] ?: continue
            val boardName = boardDisplayName(board)
            for (button in placedButtons(board)) {
                if (!includeHidden && button.hidden) continue
                if (button.isDynamicPredictionButton()) continue

                val resolvedLabel = resolveObfLocalizedString(board.strings, locale, button.label)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val resolvedVocalization = resolveObfLocalizedString(board.strings, locale, button.vocalization)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                val rank = minRank(
                    textMatchRank(resolvedLabel, normalizedQuery),
                    textMatchRank(resolvedVocalization, normalizedQuery),
                ) ?: continue

                val displayLabel = resolvedLabel ?: resolvedVocalization ?: continue
                val distinctVocalization = resolvedVocalization?.takeIf {
                    !it.equals(displayLabel, ignoreCase = true)
                }
                val target = navigationTarget(button)
                val finalStep = WordFinderStep(
                    boardId = board.id,
                    boardName = boardName,
                    buttonId = button.id,
                    buttonLabel = displayLabel,
                    targetBoardId = target?.id,
                    isHidden = button.hidden,
                )
                scoredMatches += ScoredWordFinderMatch(
                    rank = rank,
                    match = WordFinderMatch(
                        targetBoardId = board.id,
                        targetBoardName = boardName,
                        buttonId = button.id,
                        label = displayLabel,
                        vocalization = distinctVocalization,
                        isHidden = button.hidden,
                        startsFromRoot = startsFromRoot,
                        steps = pathToBoard + finalStep,
                    ),
                )
            }
        }
    }

    collectMatches(fromCurrent, skipBoardIds = emptySet(), startsFromRoot = false)
    collectMatches(
        boardPaths = fromRoot,
        skipBoardIds = fromCurrent.keys,
        startsFromRoot = currentBoardId != rootId,
    )

    return scoredMatches
        .sortedWith(
            compareBy<ScoredWordFinderMatch>(
                { it.rank },
                { it.match.steps.size },
                { it.match.startsFromRoot },
                { it.match.label.lowercase() },
                { it.match.targetBoardId },
                { it.match.buttonId },
            )
        )
        .take(maxResults)
        .map { it.match }
}

private data class ScoredWordFinderMatch(
    val rank: Int,
    val match: WordFinderMatch,
)

private fun ObfButton.isDynamicPredictionButton(): Boolean =
    type == ObfButtonType.NGramPrediction ||
        parseObfButtonActions(this).any { it === ObfButtonActionEffect.Predictions }

private fun textMatchRank(candidate: String?, normalizedQuery: String): Int? {
    val normalized = candidate?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        normalized == normalizedQuery -> 0
        normalized.startsWith(normalizedQuery) -> 1
        normalized.split(WORD_BOUNDARY_SPLIT).any { it.startsWith(normalizedQuery) } -> 2
        normalized.contains(normalizedQuery) -> 3
        else -> null
    }
}

private fun minRank(first: Int?, second: Int?): Int? = when {
    first == null -> second
    second == null -> first
    else -> minOf(first, second)
}
