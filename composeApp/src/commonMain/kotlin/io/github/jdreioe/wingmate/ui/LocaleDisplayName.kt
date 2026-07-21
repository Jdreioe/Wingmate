package io.github.jdreioe.wingmate.ui

/** A user-facing, localized name for a BCP-47 language tag while preserving the tag for storage. */
expect fun localizedLocaleDisplayName(languageTag: String): String
