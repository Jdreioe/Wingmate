package io.github.jdreioe.wingmate.domain.obf

sealed class ZipBuilderError(message: String) : Exception(message) {
    data class InvalidEntryName(val name: String, override val message: String) : ZipBuilderError(message)
    data class DuplicateEntryName(val name: String) : ZipBuilderError("Duplicate ZIP entry: $name")
    data object Zip64Required : ZipBuilderError(
        "Archive exceeds classic ZIP limits (entries > 65535, entry size > 4 GiB, " +
            "or total archive > 4 GiB). ZIP64 is not implemented."
    )
}

object ZipBuilder {

    fun build(entries: List<Pair<String, ByteArray>>): Result<ByteArray> = runCatching {
        val sink = GrowableByteArray(8192)
        val centralEntries = GrowableByteArray(4096)
        val seenNames = mutableSetOf<String>()
        var localOffset = 0L

        for ((name, data) in entries) {
            validateEntryName(name, seenNames)
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)
            val dataSize = data.size

            checkOverflow(name, dataSize, localOffset)

            sink.write(buildLocalHeader(nameBytes, dataSize, crc))
            sink.write(data)

            centralEntries.write(buildCentralEntry(nameBytes, dataSize, crc, localOffset))

            localOffset += 30L + nameBytes.size + dataSize
        }

        val entryCount = entries.size
        val centralDirSize = centralEntries.size
        checkClassicLimit(entryCount, "entry count", 65535)
        checkClassicLimit(centralDirSize.toLong(), "central directory size", 0xFFFFFFFFL)
        checkClassicLimit(localOffset, "central directory offset", 0xFFFFFFFFL)

        sink.write(centralEntries.toByteArray())
        sink.write(buildEndOfCentralDirectory(entryCount, centralDirSize.toLong(), localOffset))

        sink.toByteArray()
    }

    private fun validateEntryName(name: String, seenNames: MutableSet<String>) {
        if (name.isEmpty()) throw ZipBuilderError.InvalidEntryName(name, "Entry name must not be empty")
        if (name.contains('\u0000')) throw ZipBuilderError.InvalidEntryName(name, "Entry name contains NUL character")
        if (name.startsWith('/')) throw ZipBuilderError.InvalidEntryName(name, "Entry name must not be absolute")
        if (Regex("(^|/)\\.\\.(/|$)").containsMatchIn(name)) {
            throw ZipBuilderError.InvalidEntryName(name, "Entry name must not contain parent-directory traversal")
        }
        if (!seenNames.add(name)) throw ZipBuilderError.DuplicateEntryName(name)
    }

    private fun checkOverflow(name: String, dataSize: Int, localOffset: Long) {
        if (dataSize > 0xFFFFFFFFL) {
            throw ZipBuilderError.InvalidEntryName(name, "Entry size (${dataSize}B) exceeds 4 GiB limit")
        }
        if (localOffset + 30L + name.encodeToByteArray().size + dataSize > 0xFFFFFFFFL) {
            throw ZipBuilderError.Zip64Required
        }
    }

    private fun checkClassicLimit(value: Long, label: String, limit: Long) {
        if (value > limit) throw ZipBuilderError.Zip64Required
    }

    private fun checkClassicLimit(value: Int, label: String, limit: Int) {
        if (value > limit) throw ZipBuilderError.Zip64Required
    }

    private fun buildLocalHeader(nameBytes: ByteArray, dataSize: Int, crc: Int): ByteArray {
        val buf = ByteArray(30 + nameBytes.size)
        writeLe32(buf, 0, 0x04034b50)
        writeLe16(buf, 4, 20)
        writeLe16(buf, 6, 0x0800)
        writeLe16(buf, 8, 0)
        writeLe16(buf, 10, 0)
        writeLe16(buf, 12, 0)
        writeLe32(buf, 14, crc)
        writeLe32(buf, 18, dataSize)
        writeLe32(buf, 22, dataSize)
        writeLe16(buf, 26, nameBytes.size)
        writeLe16(buf, 28, 0)
        nameBytes.copyInto(buf, 30)
        return buf
    }

    private fun buildCentralEntry(nameBytes: ByteArray, dataSize: Int, crc: Int, localOffset: Long): ByteArray {
        val buf = ByteArray(46 + nameBytes.size)
        writeLe32(buf, 0, 0x02014b50)
        writeLe16(buf, 4, 20)
        writeLe16(buf, 6, 20)
        writeLe16(buf, 8, 0x0800)
        writeLe16(buf, 10, 0)
        writeLe16(buf, 12, 0)
        writeLe16(buf, 14, 0)
        writeLe32(buf, 16, crc)
        writeLe32(buf, 20, dataSize)
        writeLe32(buf, 24, dataSize)
        writeLe16(buf, 28, nameBytes.size)
        writeLe16(buf, 30, 0)
        writeLe16(buf, 32, 0)
        writeLe16(buf, 34, 0)
        writeLe16(buf, 36, 0)
        writeLe32(buf, 38, 0)
        writeLe32(buf, 42, localOffset.toInt())
        nameBytes.copyInto(buf, 46)
        return buf
    }

    private fun buildEndOfCentralDirectory(
        totalEntries: Int,
        centralDirSize: Long,
        centralDirOffset: Long
    ): ByteArray {
        val buf = ByteArray(22)
        writeLe32(buf, 0, 0x06054b50)
        writeLe16(buf, 4, 0)
        writeLe16(buf, 6, 0)
        writeLe16(buf, 8, totalEntries)
        writeLe16(buf, 10, totalEntries)
        writeLe32(buf, 12, centralDirSize.toInt())
        writeLe32(buf, 16, centralDirOffset.toInt())
        writeLe16(buf, 20, 0)
        return buf
    }

    private fun writeLe16(buf: ByteArray, pos: Int, value: Int) {
        buf[pos] = (value and 0xFF).toByte()
        buf[pos + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLe32(buf: ByteArray, pos: Int, value: Int) {
        buf[pos] = (value and 0xFF).toByte()
        buf[pos + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[pos + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[pos + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private val crcTable = IntArray(256) { i ->
        var crc = i
        repeat(8) {
            crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
        }
        crc
    }

    private fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in data) {
            crc = (crc ushr 8) xor crcTable[(crc xor byte.toInt()) and 0xFF]
        }
        return crc xor 0xFFFFFFFF.toInt()
    }
}

private class GrowableByteArray(initialCapacity: Int) {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun write(bytes: ByteArray) {
        ensureCapacity(size + bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > buffer.size) {
            var newSize = buffer.size
            while (newSize < minCapacity) newSize = (newSize * 2).coerceAtLeast(1)
            buffer = buffer.copyOf(newSize)
        }
    }
}
