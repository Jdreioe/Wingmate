package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfManifest

data class ParsedObfBoard(val path: String?, val board: ObfBoard)

data class ObfValidationIssue(val field: String, val message: String)

class ObfValidator {
    fun validate(
        boards: List<ParsedObfBoard>,
        rootBoardId: String,
        manifest: ObfManifest? = null,
        archiveEntryNames: Set<String> = emptySet()
    ): List<ObfValidationIssue> {
        val issues = mutableListOf<ObfValidationIssue>()
        if (boards.isEmpty()) return listOf(ObfValidationIssue("boards", "No boards were found"))

        val boardIds = boards.map { it.board.id }
        duplicateValues(boardIds).forEach { duplicate ->
            issues += ObfValidationIssue("boards[$duplicate].id", "Duplicate board ID")
        }
        if (rootBoardId !in boardIds) {
            issues += ObfValidationIssue("root", "Root board '$rootBoardId' does not exist")
        }

        boards.forEach { parsed ->
            val board = parsed.board
            val prefix = "board[${board.id}]"
            if (board.id.isBlank()) issues += ObfValidationIssue("$prefix.id", "Board ID is required")
            if (!isSupportedFormat(board.format)) {
                issues += ObfValidationIssue("$prefix.format", "Unsupported format '${board.format}'")
            }
            reportDuplicates(board.buttons.map { it.id }, "$prefix.buttons", issues)
            reportDuplicates(board.images.map { it.id }, "$prefix.images", issues)
            reportDuplicates(board.sounds.map { it.id }, "$prefix.sounds", issues)

            val buttonIds = board.buttons.map { it.id }.toSet()
            val imageIds = board.images.map { it.id }.toSet()
            val soundIds = board.sounds.map { it.id }.toSet()
            board.buttons.forEach { button ->
                if (button.id.isBlank()) issues += ObfValidationIssue("$prefix.buttons.id", "Button ID is required")
                if (button.imageId != null && button.imageId !in imageIds) {
                    issues += ObfValidationIssue("$prefix.button[${button.id}].image_id", "Referenced image does not exist")
                }
                if (button.soundId != null && button.soundId !in soundIds) {
                    issues += ObfValidationIssue("$prefix.button[${button.id}].sound_id", "Referenced sound does not exist")
                }
                val target = button.loadBoard?.id
                val hasExternalTarget = button.loadBoard?.let {
                    !it.url.isNullOrBlank() || !it.dataUrl.isNullOrBlank()
                } == true
                if (target != null && target !in boardIds && !hasExternalTarget) {
                    issues += ObfValidationIssue("$prefix.button[${button.id}].load_board.id", "Linked board '$target' does not exist")
                }
                val localPath = button.loadBoard?.path
                if (
                    manifest != null && target == null && !localPath.isNullOrBlank() &&
                    localPath !in manifest.paths.boards.values && localPath != manifest.root
                ) {
                    issues += ObfValidationIssue(
                        "$prefix.button[${button.id}].load_board.path",
                        "Linked board path '$localPath' does not identify a packaged board"
                    )
                }
                listOf("top" to button.top, "left" to button.left, "width" to button.width, "height" to button.height)
                    .forEach { (field, value) ->
                        if (value != null && (!value.isFinite() || value < 0.0 || value > 1.0)) {
                            issues += ObfValidationIssue("$prefix.button[${button.id}].$field", "Coordinate must be finite and within 0.0–1.0")
                        }
                    }
            }

            board.grid?.let { grid ->
                if (grid.rows <= 0 || grid.columns <= 0) {
                    issues += ObfValidationIssue("$prefix.grid", "Grid dimensions must be positive")
                }
                if (grid.order.size != grid.rows) {
                    issues += ObfValidationIssue("$prefix.grid.order", "Grid row count does not match rows")
                }
                grid.order.forEachIndexed { index, row ->
                    if (row.size != grid.columns) {
                        issues += ObfValidationIssue("$prefix.grid.order[$index]", "Grid row length does not match columns")
                    }
                    row.filterNotNull().filterNot { it in buttonIds }.forEach { missing ->
                        issues += ObfValidationIssue("$prefix.grid.order[$index]", "Button '$missing' does not exist")
                    }
                }
            }
        }

        manifest?.let { value ->
            if (!isSupportedFormat(value.format)) {
                issues += ObfValidationIssue("manifest.format", "Unsupported format '${value.format}'")
            }
            if (value.root !in archiveEntryNames) {
                issues += ObfValidationIssue("manifest.root", "Root path '${value.root}' is missing")
            }
            value.paths.boards.forEach { (id, path) ->
                if (path !in archiveEntryNames) {
                    issues += ObfValidationIssue("manifest.paths.boards[$id]", "Board path '$path' is missing")
                }
                val embedded = boards.firstOrNull { it.path == path }?.board?.id
                if (embedded != null && embedded != id) {
                    issues += ObfValidationIssue("manifest.paths.boards[$id]", "Embedded board ID '$embedded' does not match manifest key")
                }
            }
            (value.paths.images + value.paths.sounds).forEach { (id, path) ->
                if (path !in archiveEntryNames) {
                    issues += ObfValidationIssue("manifest.paths.media[$id]", "Media path '$path' is missing")
                }
            }
        }
        return issues
    }

    private fun isSupportedFormat(format: String): Boolean =
        format == "open-board-0.1" || format == "open-board-0.2"

    private fun duplicateValues(values: List<String>): Set<String> =
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun reportDuplicates(
        ids: List<String>,
        field: String,
        issues: MutableList<ObfValidationIssue>
    ) {
        val local = mutableSetOf<String>()
        ids.forEach { id ->
            if (id.isBlank()) issues += ObfValidationIssue("$field.id", "ID is required")
            if (!local.add(id)) issues += ObfValidationIssue("$field[$id].id", "Duplicate board-local ID")
        }
    }
}
