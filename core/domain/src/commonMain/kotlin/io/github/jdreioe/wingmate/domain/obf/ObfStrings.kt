package io.github.jdreioe.wingmate.domain.obf

/**
 * Resolve a button label or vocalization through the board's `strings` localization table.
 *
 * Priority:
 * 1. Exact locale match (`da-DK` matches `da-DK`).
 * 2. General-locale match (`da-DK` matches `da`).
 * 3. Raw attribute value (fallback).
 *
 * @param strings the board's per-locale string dictionary (`ObfBoard.strings`)
 * @param locale the active UI language tag (e.g. `da-DK` or `da`)
 * @param rawValue the button's `label` or `vocalization` attribute
 * @return the localized text or [rawValue] when no match
 */
fun resolveObfLocalizedString(
    strings: Map<String, Map<String, String>>,
    locale: String?,
    rawValue: String?
): String? {
    if (rawValue == null) return null
    val normalizedLocale = locale?.trim()?.takeIf { it.isNotEmpty() } ?: return rawValue

    val exact = strings[normalizedLocale]?.get(rawValue)
    if (exact != null) return exact

    val general = strings.entries.firstOrNull { (key, _) ->
        normalizedLocale.startsWith("$key-", ignoreCase = true) ||
            key.startsWith("$normalizedLocale-", ignoreCase = true) ||
            key.equals(normalizedLocale, ignoreCase = true)
    }?.value?.get(rawValue)
    if (general != null) return general

    return rawValue
}
