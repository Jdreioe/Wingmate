package io.github.jdreioe.wingmate.ui

import java.util.Locale

/** A user-facing, localized name for a BCP-47 language tag while preserving the tag for storage. */
fun localizedLocaleDisplayName(languageTag: String): String {
    val locale = Locale.forLanguageTag(languageTag.replace('_', '-'))
    return locale.getDisplayName(Locale.getDefault()).ifBlank { languageTag }
}
