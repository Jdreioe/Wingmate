import io.github.jdreioe.wingmate.domain.obf.ZipBuilder
import io.github.jdreioe.wingmate.domain.obf.ZipBuilderError
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipBuilderTest {

    @Test
    fun buildsValidZipWithSingleEntry() {
        val zip = ZipBuilder.build(listOf("hello.txt" to "world".encodeToByteArray())).getOrThrow()
        assertTrue(zip.size > 30)
        assertTrue(zip[0] == 0x50.toByte() && zip[1] == 0x4B.toByte() &&
            zip[2] == 0x03.toByte() && zip[3] == 0x04.toByte())
    }

    @Test
    fun buildsZipWithMultipleEntries() {
        val entries = listOf(
            "a.txt" to "aaa".encodeToByteArray(),
            "b.txt" to "bbb".encodeToByteArray()
        )
        val zip = ZipBuilder.build(entries).getOrThrow()
        assertTrue(zip.size > 60)
        assertTrue(String(zip).contains("a.txt"))
        assertTrue(String(zip).contains("b.txt"))
    }

    @Test
    fun eocdSignaturePresent() {
        val zip = ZipBuilder.build(listOf("f" to "d".encodeToByteArray())).getOrThrow()
        val tail = zip.copyOfRange(zip.size - 22, zip.size)
        assertTrue(tail[0] == 0x50.toByte() && tail[1] == 0x4B.toByte() &&
            tail[2] == 0x05.toByte() && tail[3] == 0x06.toByte())
    }

    @Test
    fun emptyEntries() {
        val zip = ZipBuilder.build(emptyList()).getOrThrow()
        assertEquals(22, zip.size)
    }

    @Test
    fun dataIsPreserved() {
        val data = "Hello, OBZ!".encodeToByteArray()
        val zip = ZipBuilder.build(listOf("test.bin" to data)).getOrThrow()
        val dataStart = 30 + "test.bin".encodeToByteArray().size
        val extracted = zip.copyOfRange(dataStart, dataStart + data.size)
        assertTrue(extracted.contentEquals(data))
    }

    @Test
    fun utf8FlagSet() {
        val zip = ZipBuilder.build(listOf("héllo.txt" to "data".encodeToByteArray())).getOrThrow()
        val flags = ((zip[7].toInt() and 0xFF) shl 8) or (zip[6].toInt() and 0xFF)
        assertTrue((flags and 0x0800) != 0, "UTF-8 flag (bit 11) must be set in local header")
    }

    @Test
    fun utf8FlagSetInCentralDirectory() {
        val zip = ZipBuilder.build(listOf("héllo.txt" to "data".encodeToByteArray())).getOrThrow()
        var pos = 0
        var centralFlags: Int? = null
        while (pos < zip.size - 4) {
            if (zip[pos] == 0x50.toByte() && zip[pos + 1] == 0x4B.toByte() &&
                zip[pos + 2] == 0x01.toByte() && zip[pos + 3] == 0x02.toByte()
            ) {
                centralFlags = ((zip[pos + 9].toInt() and 0xFF) shl 8) or (zip[pos + 8].toInt() and 0xFF)
                break
            }
            pos++
        }
        assertNotNull(centralFlags)
        assertTrue((centralFlags and 0x0800) != 0, "UTF-8 flag (bit 11) must be set in central directory entry")
    }

    @Test
    fun unicodeFilenamePreserved() {
        val name = "smørrebrød/æøå.txt"
        val zip = ZipBuilder.build(listOf(name to "data".encodeToByteArray())).getOrThrow()
        assertTrue(String(zip).contains(name), "Unicode filename must be present in ZIP bytes")
    }

    @Test
    fun rejectsEmptyEntryName() {
        val error = assertFailsWith<ZipBuilderError.InvalidEntryName> {
            ZipBuilder.build(listOf("" to "data".encodeToByteArray())).getOrThrow()
        }
        assertTrue(error.message.contains("empty"))
    }

    @Test
    fun rejectsAbsolutePath() {
        val error = assertFailsWith<ZipBuilderError.InvalidEntryName> {
            ZipBuilder.build(listOf("/etc/passwd" to "data".encodeToByteArray())).getOrThrow()
        }
        assertTrue(error.message.contains("absolute"))
    }

    @Test
    fun rejectsDirectoryTraversal() {
        val error = assertFailsWith<ZipBuilderError.InvalidEntryName> {
            ZipBuilder.build(listOf("../outside/foo.txt" to "data".encodeToByteArray())).getOrThrow()
        }
        assertTrue(error.message.contains("traversal"))
    }

    @Test
    fun rejectsNestedDirectoryTraversal() {
        val error = assertFailsWith<ZipBuilderError.InvalidEntryName> {
            ZipBuilder.build(listOf("boards/../../outside/foo.txt" to "data".encodeToByteArray())).getOrThrow()
        }
        assertTrue(error.message.contains("traversal"))
    }

    @Test
    fun rejectsNulCharacter() {
        val error = assertFailsWith<ZipBuilderError.InvalidEntryName> {
            ZipBuilder.build(listOf("foo\u0000bar.txt" to "data".encodeToByteArray())).getOrThrow()
        }
        assertTrue(error.message.contains("NUL"))
    }

    @Test
    fun rejectsDuplicateEntryName() {
        assertFailsWith<ZipBuilderError.DuplicateEntryName> {
            ZipBuilder.build(listOf(
                "same.txt" to "first".encodeToByteArray(),
                "same.txt" to "second".encodeToByteArray()
            )).getOrThrow()
        }
    }

    @Test
    fun identicalInputsProduceIdenticalOutput() {
        val entries = listOf(
            "a.txt" to "111".encodeToByteArray(),
            "b.txt" to "222".encodeToByteArray()
        )
        val zip1 = ZipBuilder.build(entries).getOrThrow()
        val zip2 = ZipBuilder.build(entries).getOrThrow()
        assertTrue(zip1.contentEquals(zip2), "Same ordered inputs must produce identical bytes")
    }

    @Test
    fun crcValidatesForEntry() {
        val data = "Hello, CRC!".encodeToByteArray()
        val zip = ZipBuilder.build(listOf("check.me" to data)).getOrThrow()
        val entryName = "check.me"
        var pos = 0
        while (pos < zip.size - 4) {
            if (zip[pos] == 0x50.toByte() && zip[pos + 1] == 0x4B.toByte() &&
                zip[pos + 2] == 0x03.toByte() && zip[pos + 3] == 0x04.toByte()
            ) {
                val nameLen = ((zip[pos + 27].toInt() and 0xFF) shl 8) or (zip[pos + 26].toInt() and 0xFF)
                val extraLen = ((zip[pos + 29].toInt() and 0xFF) shl 8) or (zip[pos + 28].toInt() and 0xFF)
                val storedCrc = ((zip[pos + 17].toInt() and 0xFF) shl 24) or
                    ((zip[pos + 16].toInt() and 0xFF) shl 16) or
                    ((zip[pos + 15].toInt() and 0xFF) shl 8) or
                    (zip[pos + 14].toInt() and 0xFF)
                val headerSize = 30 + nameLen + extraLen
                val compSize = ((zip[pos + 21].toInt() and 0xFF) shl 24) or
                    ((zip[pos + 20].toInt() and 0xFF) shl 16) or
                    ((zip[pos + 19].toInt() and 0xFF) shl 8) or
                    (zip[pos + 18].toInt() and 0xFF)
                val extractedName = zip.copyOfRange(pos + 30, pos + 30 + nameLen).decodeToString()
                if (extractedName == entryName) {
                    val extracted = zip.copyOfRange(pos + headerSize, pos + headerSize + compSize)
                    assertTrue(extracted.contentEquals(data), "Data must be retrievable")
                    assertTrue(storedCrc != 0, "CRC must be non-zero")
                    return
                }
                pos += headerSize + compSize
            } else {
                pos++
            }
        }
    }

    @Test
    fun directoryOffsetsValid() {
        val entries = listOf(
            "first.txt" to "data1".encodeToByteArray(),
            "second.txt" to "data2".encodeToByteArray()
        )
        val zip = ZipBuilder.build(entries).getOrThrow()
        val tail = zip.copyOfRange(zip.size - 22, zip.size)
        val cdOffset = ((tail[19].toInt() and 0xFF) shl 24) or
            ((tail[18].toInt() and 0xFF) shl 16) or
            ((tail[17].toInt() and 0xFF) shl 8) or
            (tail[16].toInt() and 0xFF)
        val cdSize = ((tail[15].toInt() and 0xFF) shl 24) or
            ((tail[14].toInt() and 0xFF) shl 16) or
            ((tail[13].toInt() and 0xFF) shl 8) or
            (tail[12].toInt() and 0xFF)
        assertEquals(zip.size - 22, cdOffset + cdSize, "Central directory must end just before EOCD")
    }

    @Test
    fun zip64RequiredForLargeEntryCount() {
        val manyEntries = (0..65535).map { "file$it.txt" to "x".encodeToByteArray() }
        val error = assertFailsWith<ZipBuilderError.Zip64Required> {
            ZipBuilder.build(manyEntries).getOrThrow()
        }
    }
}
