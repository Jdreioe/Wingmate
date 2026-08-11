package io.github.jdreioe.wingmate.domain.obf

import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val OBF_WORD_TYPE_EXTENSION = "ext_wingmate_word_type"

/** Broad AAC vocabulary groups used by Fitzgerald-style color coding. */
enum class WordType(val wireValue: String, val fitzgeraldColor: String) {
    Pronoun("pronoun", "#FFF176"),
    Verb("verb", "#F48FB1"),
    Descriptor("descriptor", "#81D4FA"),
    Noun("noun", "#FFB74D"),
    Social("social", "#A5D6A7"),
    Other("other", "#E0E0E0")
}

val ObfButton.wordType: WordType?
    get() = (extensions[OBF_WORD_TYPE_EXTENSION] as? JsonPrimitive)?.contentOrNull
        ?.let { value -> WordType.entries.firstOrNull { it.wireValue == value } }

fun ObfButton.withWordType(type: WordType?): ObfButton = copy(
    extensions = type?.let {
        extensions + (OBF_WORD_TYPE_EXTENSION to JsonPrimitive(it.wireValue))
    } ?: (extensions - OBF_WORD_TYPE_EXTENSION)
)

/**
 * Returns a conservative classification. Unknown words deliberately return null
 * instead of receiving a misleading color. A stored [wordType] is always used first.
 */
fun ObfButton.resolvedWordType(boardLocale: String? = null, localizedLabel: String? = null): WordType? =
    wordType ?: inferWordType(localizedLabel ?: label ?: vocalization, locale ?: boardLocale)

/** Explicit OBF colors have precedence over generated colors. */
fun ObfButton.resolvedBackgroundColor(
    scheme: WordTypeColorScheme,
    boardLocale: String? = null,
    localizedLabel: String? = null
): String? = backgroundColor ?: when (scheme) {
    WordTypeColorScheme.None -> null
    WordTypeColorScheme.Fitzgerald -> resolvedWordType(boardLocale, localizedLabel)?.fitzgeraldColor
}

fun inferWordType(text: String?, locale: String?): WordType? {
    val word = text
        ?.trim()
        ?.lowercase()
        ?.trim { !it.isLetter() && it != '\'' && it != '-' }
        ?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        ?: return null
    val language = locale?.substringBefore('-')?.substringBefore('_')?.lowercase()
    val lexicon = when (language) {
        "da" -> danishWords
        "en", null, "" -> englishWords
        else -> return null
    }
    return lexicon[word] ?: inferBySuffix(word, language)
}

private fun inferBySuffix(word: String, language: String?): WordType? = when (language) {
    "en", null, "" -> when {
        word.endsWith("ly") -> WordType.Descriptor
        word.endsWith("ing") || word.endsWith("ed") -> WordType.Verb
        else -> null
    }
    "da" -> when {
        word.endsWith("ligt") -> WordType.Descriptor
        else -> null
    }
    else -> null
}

private fun words(type: WordType, value: String): Map<String, WordType> =
    value.split(' ').associateWith { type }

private val englishWords = buildMap {
    putAll(words(WordType.Pronoun, "i me my mine you your yours he him his she her hers it its we us our ours they them their theirs who what where when why"))
    putAll(words(WordType.Verb, "am is are was were be being been have has had do does did can could will would shall should may might must want need like go come make get put take give see hear say tell eat drink play help stop open close turn look feel think know"))
    putAll(words(WordType.Descriptor, "good bad big small hot cold happy sad angry tired sick more less fast slow loud quiet yes no red orange yellow green blue purple black white"))
    putAll(words(WordType.Social, "hello hi goodbye bye please thanks thank sorry okay ok"))
    putAll(words(WordType.Other, "a an the and or but if in on at to from with without for of this that these those"))
    putAll(words(WordType.Noun, "person people family mother father mom dad sister brother friend home school work food water drink toilet bathroom pain medicine doctor nurse book music game phone computer car bus dog cat"))
}

private val danishWords = buildMap {
    putAll(words(WordType.Pronoun, "jeg mig min mit mine du dig din dit dine han ham hans hun hende hendes den det vi os vores i jer jeres hvem hvad hvor hvornår hvorfor"))
    putAll(words(WordType.Verb, "er var være har havde gør gjorde kan kunne vil ville skal skulle må ønsker behøver lide gå komme lave få sætte tage give se høre sige fortælle spise drikke lege hjælpe stop åbne lukke føle tænke vide"))
    putAll(words(WordType.Descriptor, "god dårlig stor lille varm kold glad ked vred træt syg mere mindre hurtig langsom høj stille ja nej rød orange gul grøn blå lilla sort hvid"))
    putAll(words(WordType.Social, "hej farvel tak undskyld okay"))
    putAll(words(WordType.Other, "en et den det og eller men hvis i på til fra med uden for af denne dette disse"))
    putAll(words(WordType.Noun, "person mennesker familie mor far søster bror ven hjem skole arbejde mad vand drik toilet badeværelse smerte medicin læge sygeplejerske bog musik spil telefon computer bil bus hund kat"))
}

/** WCAG contrast ratio for tests and non-native export/rendering surfaces. */
fun contrastRatio(foregroundHex: String, backgroundHex: String): Double {
    val foreground = relativeLuminance(foregroundHex)
    val background = relativeLuminance(backgroundHex)
    val lighter = maxOf(foreground, background)
    val darker = minOf(foreground, background)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(hex: String): Double {
    val value = hex.removePrefix("#").takeLast(6).padStart(6, '0')
    fun component(offset: Int): Double {
        val channel = value.substring(offset, offset + 2).toInt(16) / 255.0
        return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).let { it * it * it }
    }
    return 0.2126 * component(0) + 0.7152 * component(2) + 0.0722 * component(4)
}
