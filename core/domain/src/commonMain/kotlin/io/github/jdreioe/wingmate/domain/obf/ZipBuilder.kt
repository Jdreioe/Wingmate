package io.github.jdreioe.wingmate.domain.obf

/**
 * Pure-Kotlin ZIP builder that creates store-method (uncompressed) ZIP archives.
 */
object ZipBuilder {

    fun build(entries: Map<String, ByteArray>): ByteArray {
        val localHeaders = mutableListOf<ByteArray>()
        val centralEntries = mutableListOf<ByteArray>()
        var offset = 0

        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)

            // Local file header
            localHeaders += buildLocalHeader(nameBytes, data.size, crc)
            localHeaders += data

            // Central directory entry
            centralEntries += buildCentralEntry(nameBytes, data.size, crc, offset)

            offset += 30 + nameBytes.size + data.size
        }

        val cdBytes = centralEntries.fold(byteArrayOf()) { acc, e -> acc + e }
        val eocd = buildEndOfCentralDirectory(
            totalEntries = entries.size,
            centralDirSize = cdBytes.size,
            centralDirOffset = offset
        )

        return localHeaders.fold(byteArrayOf()) { acc, e -> acc + e } + cdBytes + eocd
    }

    private fun buildLocalHeader(nameBytes: ByteArray, dataSize: Int, crc: Int): ByteArray {
        val buf = ByteArray(30 + nameBytes.size)
        writeLe32(buf, 0, 0x04034b50)       // signature
        writeLe16(buf, 4, 20)                // version needed
        writeLe16(buf, 6, 0)                 // flags
        writeLe16(buf, 8, 0)                 // compression: stored
        writeLe16(buf, 10, 0)                // mod time
        writeLe16(buf, 12, 0)                // mod date
        writeLe32(buf, 14, crc)              // crc-32
        writeLe32(buf, 18, dataSize)         // compressed size
        writeLe32(buf, 22, dataSize)         // uncompressed size
        writeLe16(buf, 26, nameBytes.size)   // filename length
        writeLe16(buf, 28, 0)                // extra field length
        nameBytes.copyInto(buf, 30)
        return buf
    }

    private fun buildCentralEntry(nameBytes: ByteArray, dataSize: Int, crc: Int, localOffset: Int): ByteArray {
        val buf = ByteArray(46 + nameBytes.size)
        writeLe32(buf, 0, 0x02014b50)        // signature
        writeLe16(buf, 4, 20)                // version made by
        writeLe16(buf, 6, 20)                // version needed
        writeLe16(buf, 8, 0)                 // flags
        writeLe16(buf, 10, 0)                // compression: stored
        writeLe16(buf, 12, 0)                // mod time
        writeLe16(buf, 14, 0)                // mod date
        writeLe32(buf, 16, crc)              // crc-32
        writeLe32(buf, 20, dataSize)         // compressed size
        writeLe32(buf, 24, dataSize)         // uncompressed size
        writeLe16(buf, 28, nameBytes.size)   // filename length
        writeLe16(buf, 30, 0)                // extra field length
        writeLe16(buf, 32, 0)                // file comment length
        writeLe16(buf, 34, 0)                // disk number start
        writeLe16(buf, 36, 0)                // internal file attributes
        writeLe32(buf, 38, 0)                // external file attributes
        writeLe32(buf, 42, localOffset)      // relative offset
        nameBytes.copyInto(buf, 46)
        return buf
    }

    private fun buildEndOfCentralDirectory(
        totalEntries: Int,
        centralDirSize: Int,
        centralDirOffset: Int
    ): ByteArray {
        val buf = ByteArray(22)
        writeLe32(buf, 0, 0x06054b50)        // signature
        writeLe16(buf, 4, 0)                 // disk number
        writeLe16(buf, 6, 0)                 // disk of central dir
        writeLe16(buf, 8, totalEntries)      // entries on disk
        writeLe16(buf, 10, totalEntries)     // total entries
        writeLe32(buf, 12, centralDirSize)   // size of central dir
        writeLe32(buf, 16, centralDirOffset) // offset of central dir
        writeLe16(buf, 20, 0)                // comment length
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
