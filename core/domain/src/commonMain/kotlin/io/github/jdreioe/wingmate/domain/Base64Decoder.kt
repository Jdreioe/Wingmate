package io.github.jdreioe.wingmate.domain

object Base64Decoder {
    private val table by lazy {
        val t = IntArray(128) { -1 }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { index, c -> t[c.code] = index }
        t
    }

    fun decode(input: String): ByteArray {
        val cleaned = input.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return ByteArray(0)

        val padding = when {
            cleaned.endsWith("==") -> 2
            cleaned.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArray(cleaned.length / 4 * 3 - padding)
        var outIndex = 0
        var i = 0
        while (i < cleaned.length) {
            val c0 = charValue(cleaned[i])
            val c1 = charValue(cleaned[i + 1])
            val c2 = if (cleaned[i + 2] == '=') 0 else charValue(cleaned[i + 2])
            val c3 = if (cleaned[i + 3] == '=') 0 else charValue(cleaned[i + 3])
            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (outIndex < out.size) out[outIndex++] = ((triple shr 16) and 0xFF).toByte()
            if (outIndex < out.size) out[outIndex++] = ((triple shr 8) and 0xFF).toByte()
            if (outIndex < out.size) out[outIndex++] = (triple and 0xFF).toByte()
            i += 4
        }
        return out
    }

    fun decodeOrNull(input: String): ByteArray? = runCatching { decode(input) }.getOrNull()

    private fun charValue(c: Char): Int {
        val v = table.getOrElse(c.code) { -1 }
        require(v >= 0) { "Invalid base64 char: $c" }
        return v
    }
}
