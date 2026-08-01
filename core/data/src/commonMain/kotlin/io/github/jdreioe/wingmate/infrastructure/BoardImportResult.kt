package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet

sealed interface BoardImportResult {
    data class Success(
        val boardSet: ObfBoardSet,
        val warnings: List<BoardImportWarning> = emptyList()
    ) : BoardImportResult

    data object Cancelled : BoardImportResult

    data class Failure(
        val code: BoardImportErrorCode,
        val context: String,
        val warnings: List<BoardImportWarning> = emptyList()
    ) : BoardImportResult
}

data class BoardImportWarning(val code: String, val context: String)

enum class BoardImportErrorCode {
    FILE_UNREADABLE,
    MALFORMED_JSON,
    MALFORMED_ARCHIVE,
    INVALID_MANIFEST,
    INVALID_GRAPH,
    UNSAFE_ENTRY_NAME,
    DUPLICATE_ENTRY,
    ENCRYPTED_ENTRY,
    TOO_MANY_ENTRIES,
    JSON_ENTRY_TOO_LARGE,
    MEDIA_ENTRY_TOO_LARGE,
    ARCHIVE_TOO_LARGE,
    COMPRESSION_RATIO_EXCEEDED,
    MEDIA_UNRESOLVED,
    PERSISTENCE_FAILED
}

data class ObzImportLimits(
    val maxEntries: Int = 5_000,
    val maxJsonEntryBytes: Long = 5L * 1024 * 1024,
    val maxMediaEntryBytes: Long = 25L * 1024 * 1024,
    val maxTotalUncompressedBytes: Long = 512L * 1024 * 1024,
    val maxCompressionRatio: Double = 100.0
)
