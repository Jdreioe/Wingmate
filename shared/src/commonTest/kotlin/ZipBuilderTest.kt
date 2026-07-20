import io.github.jdreioe.wingmate.domain.obf.ZipBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZipBuilderTest {

    @Test
    fun buildsValidZipWithSingleEntry() {
        val zip = ZipBuilder.build(mapOf("hello.txt" to "world".encodeToByteArray()))
        assertTrue(zip.size > 30)
        // PK signature for local file header
        assertTrue(zip[0] == 0x50.toByte() && zip[1] == 0x4B.toByte() &&
            zip[2] == 0x03.toByte() && zip[3] == 0x04.toByte())
    }

    @Test
    fun buildsZipWithMultipleEntries() {
        val entries = mapOf(
            "a.txt" to "aaa".encodeToByteArray(),
            "b.txt" to "bbb".encodeToByteArray()
        )
        val zip = ZipBuilder.build(entries)
        assertTrue(zip.size > 60)
        assertTrue(String(zip).contains("a.txt"))
        assertTrue(String(zip).contains("b.txt"))
    }

    @Test
    fun eocdSignaturePresent() {
        val zip = ZipBuilder.build(mapOf("f" to "d".encodeToByteArray()))
        val tail = zip.copyOfRange(zip.size - 22, zip.size)
        assertTrue(tail[0] == 0x50.toByte() && tail[1] == 0x4B.toByte() &&
            tail[2] == 0x05.toByte() && tail[3] == 0x06.toByte())
    }

    @Test
    fun emptyEntries() {
        val zip = ZipBuilder.build(emptyMap())
        // EOCD only - 22 bytes
        assertTrue(zip.size == 22)
    }

    @Test
    fun dataIsPreserved() {
        val data = "Hello, OBZ!".encodeToByteArray()
        val zip = ZipBuilder.build(mapOf("test.bin" to data))
        val dataStart = 30 + "test.bin".encodeToByteArray().size
        val extracted = zip.copyOfRange(dataStart, dataStart + data.size)
        assertTrue(extracted.contentEquals(data))
    }
}
