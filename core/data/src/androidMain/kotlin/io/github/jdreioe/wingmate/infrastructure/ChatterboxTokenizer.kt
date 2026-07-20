package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Minimal implementation of the BPE pipeline declared by the pinned tokenizer.json. */
class ChatterboxTokenizer(tokenizerPath: String) {
    data class Encoding(val inputIds: LongArray, val positionIds: LongArray)

    private val vocab: Map<String, Int>
    private val idToToken: Map<Int, String>
    private val mergeRanks: Map<Pair<String, String>, Int>
    private val addedTokens: Map<String, Int>

    init {
        val root = Json.parseToJsonElement(File(tokenizerPath).readText()).jsonObject
        vocab = root.getValue("model").jsonObject.getValue("vocab").jsonObject
            .mapValues { it.value.jsonPrimitive.content.toInt() }
        idToToken = vocab.entries.associate { (token, id) -> id to token }
        mergeRanks = root.getValue("model").jsonObject.getValue("merges").jsonArray
            .mapIndexedNotNull { rank, element ->
                val parts = element.jsonPrimitive.content.split(' ', limit = 2)
                if (parts.size == 2) (parts[0] to parts[1]) to rank else null
            }.toMap()
        addedTokens = root["added_tokens"]?.jsonArray.orEmpty().associate { element ->
            val token = element.jsonObject
            token.getValue("content").jsonPrimitive.content to token.getValue("id").jsonPrimitive.content.toInt()
        }
    }

    fun encode(text: String, languageId: String): Encoding {
        val language = languageId.lowercase().take(2)
        if (language !in SUPPORTED_LANGUAGES) throw ChatterboxError.UnsupportedLanguage(language)
        val normalized = "[$language]${text.replace(" ", "[SPACE]")}"
        val contentIds = tokenizeNormalized(normalized)
        if (contentIds.size > MAX_TEXT_LEN) throw ChatterboxError.TextTooLong(MAX_TEXT_LEN)
        val ids = longArrayOf(EXAGGERATION_ID, SOT_TEXT_ID) +
            contentIds.map(Int::toLong).toLongArray() +
            longArrayOf(EOT_TEXT_ID, SOT_SPEECH, SOT_SPEECH)
        val positions = LongArray(ids.size) { index ->
            if (ids[index] >= SOT_SPEECH) 0L else (index - 1).toLong()
        }
        return Encoding(ids, positions)
    }

    fun textToTokens(text: String, languageId: String = "en"): List<Int> =
        encode(text, languageId).inputIds.map(Long::toInt)

    fun tokenToText(id: Int): String = idToToken[id] ?: "[UNK]"

    private fun tokenizeNormalized(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        var cursor = 0
        while (cursor < text.length) {
            val added = findAddedToken(text, cursor)
            if (added != null) {
                ids += added.second
                cursor += added.first.length
                continue
            }

            val nextAdded = nextAddedTokenStart(text, cursor)
            val plain = text.substring(cursor, nextAdded)
            WHITESPACE_PATTERN.findAll(plain).forEach { match -> ids += bpe(match.value) }
            cursor = nextAdded
        }
        return ids
    }

    private fun findAddedToken(text: String, start: Int): Pair<String, Int>? =
        addedTokens.entries.asSequence()
            .filter { text.startsWith(it.key, start) }
            .maxByOrNull { it.key.length }
            ?.let { it.key to it.value }

    private fun nextAddedTokenStart(text: String, start: Int): Int {
        var result = text.length
        for (token in addedTokens.keys) {
            val index = text.indexOf(token, start)
            if (index >= 0 && index < result) result = index
        }
        return result
    }

    private fun bpe(piece: String): List<Int> {
        if (piece.isEmpty()) return emptyList()
        val symbols = piece.codePoints().toArray().map { String(Character.toChars(it)) }.toMutableList()
        while (symbols.size > 1) {
            var bestIndex = -1
            var bestRank = Int.MAX_VALUE
            for (index in 0 until symbols.lastIndex) {
                val rank = mergeRanks[symbols[index] to symbols[index + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = index
                }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] = symbols[bestIndex] + symbols[bestIndex + 1]
            symbols.removeAt(bestIndex + 1)
        }
        return symbols.map { vocab[it] ?: UNK_ID }
    }

    companion object {
        const val MAX_TEXT_LEN = 256
        const val EOT_TEXT_ID = 0L
        const val SOT_TEXT_ID = 255L
        const val SOT_SPEECH = 6561L
        const val EOT_SPEECH = 6562L
        const val EXAGGERATION_ID = 6563L
        const val SPEECH_VOCAB = 8194
        private const val UNK_ID = 1
        val SUPPORTED_LANGUAGES = setOf("en", "da")
        private val WHITESPACE_PATTERN = Regex("\\w+|[^\\w\\s]+")
    }
}
