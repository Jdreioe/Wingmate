package io.github.jdreioe.wingmate.ui

import java.util.Locale

actual fun localizedLocaleDisplayName(languageTag: String): String {
    val locale = Locale.forLanguageTag(languageTag.replace('_', '-'))
    return locale.getDisplayName(Locale.getDefault()).ifBlank { languageTag }
}
