package io.github.jdreioe.wingmate.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class ChatterboxTokenizer(private val vocabPath: String) {
    private val SOT_TEXT = 255
    private val EOT_TEXT = 0

    private val vocab: Map<String, Int>
    private val idToToken: Map<Int, String>

    init {
        val jsonStr = File(vocabPath).readText()
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject
        val vocabMap = mutableMapOf<String, Int>()
        val idMap = mutableMapOf<Int, String>()
        for ((key, value) in parsed) {
            val id = value.jsonPrimitive.content.toInt()
            vocabMap[key] = id
            idMap[id] = key
        }
        vocab = vocabMap
        idToToken = idMap
    }

    fun textToTokens(text: String, languageId: String = "en"): List<Int> {
        val toks = mutableListOf<Int>()
        val lower = text.lowercase()
        var i = 0
        while (i < lower.length) {
            var bestMatch: String? = null
            var bestLen = 0
            for (len in 8 downTo 1) {
                if (i + len > lower.length) continue
                val sub = lower.substring(i, i + len)
                if (vocab.containsKey(sub)) {
                    bestMatch = sub
                    bestLen = len
                    break
                }
            }
            if (bestMatch != null) {
                toks.add(vocab[bestMatch]!!)
                i += bestLen
            } else {
                toks.add(vocab["<unk>"] ?: 0)
                i++
            }
        }
        return toks
    }

    fun tokenToText(id: Int): String = idToToken[id] ?: "<unk>"

    companion object {
        const val MAX_TEXT_LEN = 256
        const val SOT_TEXT_ID = 255
        const val EOT_TEXT_ID = 0
        const val SOT_SPEECH = 6561
        const val EOT_SPEECH = 6562
        const val SPEECH_VOCAB = 8194
    }
}
