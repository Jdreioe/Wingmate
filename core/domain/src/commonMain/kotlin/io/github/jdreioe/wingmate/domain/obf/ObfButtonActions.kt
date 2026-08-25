package io.github.jdreioe.wingmate.domain.obf

/**
 * Result of interpreting OBF button `action` / `actions` values for Run mode.
 */
sealed class ObfButtonActionEffect {
    data class AppendText(val text: String) : ObfButtonActionEffect()
    data class WrapSelection(
        val prefix: String,
        val suffix: String,
        val fallback: String = DEFAULT_WRAP_FALLBACK,
    ) : ObfButtonActionEffect()
    data object Backspace : ObfButtonActionEffect()
    data object Clear : ObfButtonActionEffect()
    data object Speak : ObfButtonActionEffect()
    data object Home : ObfButtonActionEffect()
    data object NativeKeyboard : ObfButtonActionEffect()
    data object Predictions : ObfButtonActionEffect()
    data class Unsupported(val action: String) : ObfButtonActionEffect()

    companion object {
        const val DEFAULT_WRAP_FALLBACK = "text"
    }
}

/**
 * Parses a single OBF action string into a Run-mode effect.
 *
 * Supported:
 * - `+…` append the following characters (including spaces after the `+`)
 * - `:space` append a single space
 * - `:wrap=PREFIX|SUFFIX` wrap the current selection in PREFIX/SUFFIX; where no
 *   selection exists (token-sentence surfaces) insert PREFIX + fallback + SUFFIX
 * - `:backspace` remove the last character of the composed sentence
 * - `:clear` clear the sentence
 * - `:speak` speak the current sentence
 * - `:home` navigate to the board set root
 * - `:native-keyboard` open the platform's keyboard-based communication workspace
 * - `:prediction` (or `:predictions`) insert an n-gram word prediction
 */
fun parseObfButtonAction(raw: String): ObfButtonActionEffect {
    if (raw.isEmpty()) return ObfButtonActionEffect.Unsupported(raw)

    if (raw.startsWith("+")) {
        val payload = raw.removePrefix("+")
        if (payload.isEmpty()) return ObfButtonActionEffect.Unsupported(raw)
        return ObfButtonActionEffect.AppendText(payload)
    }

    // Wrap payloads may end in meaningful whitespace (e.g. "</emphasis> "),
    // so match before the colon-command trim.
    if (raw.startsWith(":wrap=", ignoreCase = true)) {
        val body = raw.substring(":wrap=".length)
        val separator = body.indexOf('|')
        val prefix = separator.takeIf { it > 0 }?.let { body.substring(0, it) }
        val suffix = separator.takeIf { it != -1 && it < body.lastIndex }?.let { body.substring(it + 1) }
        if (prefix != null && suffix != null) {
            return ObfButtonActionEffect.WrapSelection(prefix = prefix, suffix = suffix)
        }
        return ObfButtonActionEffect.Unsupported(raw)
    }

    val action = raw.trim()
    return when {
        action.equals(":space", ignoreCase = true) -> ObfButtonActionEffect.AppendText(" ")
        action.equals(":backspace", ignoreCase = true) -> ObfButtonActionEffect.Backspace
        action.equals(":clear", ignoreCase = true) -> ObfButtonActionEffect.Clear
        action.equals(":speak", ignoreCase = true) -> ObfButtonActionEffect.Speak
        action.equals(":home", ignoreCase = true) -> ObfButtonActionEffect.Home
        action.equals(":native-keyboard", ignoreCase = true) -> ObfButtonActionEffect.NativeKeyboard
        action.equals(":prediction", ignoreCase = true) -> ObfButtonActionEffect.Predictions
        action.equals(":predictions", ignoreCase = true) -> ObfButtonActionEffect.Predictions
        else -> ObfButtonActionEffect.Unsupported(action)
    }
}

fun parseObfButtonActions(button: ObfButton): List<ObfButtonActionEffect> =
    button.resolvedActions().map(::parseObfButtonAction)
