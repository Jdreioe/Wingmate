package io.github.jdreioe.wingmate.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Base64DecoderTest {

    @Test
    fun decodeHelloWorld() {
        val result = Base64Decoder.decode("SGVsbG8gV29ybGQ=")
        assertContentEquals("Hello World".encodeToByteArray(), result)
    }

    @Test
    fun decodeEmpty() {
        val result = Base64Decoder.decode("")
        assertContentEquals(ByteArray(0), result)
    }

    @Test
    fun decodeNoPadding() {
        val result = Base64Decoder.decode("Zm9vYmFy")
        assertContentEquals("foobar".encodeToByteArray(), result)
    }

    @Test
    fun decodeDoublePadding() {
        val result = Base64Decoder.decode("Zm8=")
        assertContentEquals("fo".encodeToByteArray(), result)
    }

    @Test
    fun decodeWithWhitespace() {
        val result = Base64Decoder.decode("SGVs\nbG8g\tV29y\nbGQ=")
        assertContentEquals("Hello World".encodeToByteArray(), result)
    }

    @Test
    fun decodeOrNullValid() {
        val result = Base64Decoder.decodeOrNull("SGVsbG8gV29ybGQ=")
        assertContentEquals("Hello World".encodeToByteArray(), result!!)
    }

    @Test
    fun decodeOrNullInvalid() {
        assertNull(Base64Decoder.decodeOrNull("!!!invalid!!!"))
    }

    @Test
    fun decodeOrNullInvalidLength() {
        assertNull(Base64Decoder.decodeOrNull("abc"))
    }

    @Test
    fun decodeStandardAlphabet() {
        val result = Base64Decoder.decode("dGVzdGluZw==")
        assertContentEquals("testing".encodeToByteArray(), result)
    }

    @Test
    fun decodeAllByteValues() {
        val original = ByteArray(255) { it.toByte() }
        val encoded = buildString {
            var i = 0
            while (i < original.size) {
                fun enc(b: Int): Char = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"[b and 0x3F]
                val b0 = original[i].toInt() and 0xFF
                val b1 = original[i + 1].toInt() and 0xFF
                val b2 = original[i + 2].toInt() and 0xFF
                val triple = (b0 shl 16) or (b1 shl 8) or b2
                append(enc(triple shr 18))
                append(enc(triple shr 12))
                append(enc(triple shr 6))
                append(enc(triple))
                i += 3
            }
        }
        val result = Base64Decoder.decode(encoded)
        assertContentEquals(original, result)
    }
}
