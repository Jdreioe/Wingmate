package io.github.jdreioe.wingmate.ui

object ConsentPhrases {
    val ENGLISH = "I hereby consent to my voice being cloned for use in Wingmate"
    val DANISH = "Jeg giver hermed samtykke til at min stemme bliver klonet til brug i Wingmate"

    fun get(languageCode: String): String = when {
        languageCode.startsWith("da") -> DANISH
        else -> ENGLISH
    }
}
