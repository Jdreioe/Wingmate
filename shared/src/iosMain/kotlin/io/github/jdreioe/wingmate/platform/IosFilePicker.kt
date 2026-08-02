package io.github.jdreioe.wingmate.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** Native iOS archive access. SwiftUI owns document selection and passes the selected path. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFilePicker : FilePicker {
    override suspend fun pickFile(title: String, extensions: List<String>): String? = null

    override suspend fun readFileAsText(path: String): String? {
        val data = NSData.dataWithContentsOfFile(path.removePrefix("file://")) ?: return null
        return NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    override suspend fun openArchive(path: String): ArchiveReader? {
        val data = NSData.dataWithContentsOfFile(path.removePrefix("file://")) ?: return null
        return IosStoredZipArchiveReader(data.toByteArray())
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { result ->
        if (result.isNotEmpty()) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}

private class IosStoredZipArchiveReader(bytes: ByteArray) : ArchiveReader {
    private data class Stored(val entry: ArchiveEntry, val offset: Int)
    private val data = bytes
    private val stored: List<Stored> = parse(bytes)

    override suspend fun entries(): List<ArchiveEntry> = stored.map { it.entry }

    override suspend fun readEntry(name: String, maxBytes: Long, onChunk: suspend (ByteArray) -> Unit) {
        val match = stored.firstOrNull { it.entry.name == name }
            ?: throw ArchiveReadException(ArchiveReadError.ENTRY_NOT_FOUND, "Archive entry not found: $name")
        if (match.entry.uncompressedSize > maxBytes) {
            throw ArchiveReadException(ArchiveReadError.ENTRY_TOO_LARGE, "Archive entry exceeds its size limit: $name")
        }
        var offset = match.offset
        var remaining = match.entry.uncompressedSize.toInt()
        while (remaining > 0) {
            val count = minOf(64 * 1024, remaining)
            onChunk(data.copyOfRange(offset, offset + count))
            offset += count
            remaining -= count
        }
    }

    override suspend fun close() = Unit

    private companion object {
        fun parse(bytes: ByteArray): List<Stored> {
            val result = mutableListOf<Stored>()
            var offset = 0
            while (offset + 4 <= bytes.size && bytes.le32(offset) == 0x04034b50L) {
                if (offset + 30 > bytes.size) malformed()
                val flags = bytes.le16(offset + 6)
                val compression = bytes.le16(offset + 8)
                val compressedSize = bytes.le32(offset + 18)
                val uncompressedSize = bytes.le32(offset + 22)
                val nameLength = bytes.le16(offset + 26)
                val extraLength = bytes.le16(offset + 28)
                if (compression != 0 || flags and 0x08 != 0) malformed("Only stored Wingmate ZIP entries are supported")
                val nameStart = offset + 30
                val dataStart = nameStart + nameLength + extraLength
                val dataEnd = dataStart.toLong() + compressedSize
                if (nameStart + nameLength > bytes.size || dataEnd > bytes.size) malformed()
                val name = bytes.copyOfRange(nameStart, nameStart + nameLength).decodeToString()
                result += Stored(
                    ArchiveEntry(name, uncompressedSize, compressedSize, isEncrypted = flags and 1 != 0),
                    dataStart
                )
                offset = dataEnd.toInt()
            }
            if (result.isEmpty()) malformed()
            return result
        }

        fun ByteArray.le16(offset: Int): Int =
            (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

        fun ByteArray.le32(offset: Int): Long =
            le16(offset).toLong() or (le16(offset + 2).toLong() shl 16)

        fun malformed(message: String = "Malformed ZIP archive"): Nothing =
            throw ArchiveReadException(ArchiveReadError.MALFORMED_ARCHIVE, message)
    }
}
