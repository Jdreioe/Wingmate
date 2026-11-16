package io.github.jdreioe.wingmate.application

/**
 * The single rule for what a [FeatureUsageReporter] may send off the device.
 * Every platform reporter passes event names and metadata through here first.
 */
object FeatureUsageEventSanitizer {
    private const val MAX_NAME_LENGTH = 40
    private const val MAX_VALUE_LENGTH = 100
    private val NAME_SANITIZE_REGEX = Regex("[^a-z0-9_]")
    private val VALUE_ALLOWED_REGEX = Regex("[A-Za-z0-9_.:-]+")

    fun eventName(raw: String): String = name(raw, fallback = "app_event")

    /** Normalizes keys and replaces any value that could be free text. */
    fun props(metadata: Map<String, String>): Map<String, String> =
        metadata.entries.associate { (key, value) -> name(key, fallback = "param") to allowValue(value) }

    private fun name(raw: String, fallback: String): String {
        val cleaned = raw.trim().lowercase().replace(NAME_SANITIZE_REGEX, "_").trim('_')
        val prefixed = if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "e_$cleaned"
        return prefixed.take(MAX_NAME_LENGTH).trim('_').ifBlank { fallback }
    }

    /** Only bounded identifier-like values (enums, numbers, ids) may leave the
     *  device; free text — which could carry user phrases — is replaced. */
    private fun allowValue(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length <= MAX_VALUE_LENGTH && VALUE_ALLOWED_REGEX.matches(trimmed)) {
            trimmed
        } else {
            "redacted"
        }
    }
}
