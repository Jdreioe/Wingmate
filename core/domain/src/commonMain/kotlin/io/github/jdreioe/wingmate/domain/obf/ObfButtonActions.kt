package io.github.jdreioe.wingmate.domain.obf

/**
 * Result of interpreting OBF button `action` / `actions` values for Run mode.
 */
sealed class ObfButtonActionEffect {
    data class AppendText(val text: String) : ObfButtonActionEffect()
    data object Backspace : ObfButtonActionEffect()
    data object Clear : ObfButtonActionEffect()
    data object Speak : ObfButtonActionEffect()
    data object Home : ObfButtonActionEffect()
    data class Unsupported(val action: String) : ObfButtonActionEffect()
}

/**
 * Parses a single OBF action string into a Run-mode effect.
 *
 * Supported:
 * - `+…` append the following characters (including spaces after the `+`)
 * - `:space` append a single space
 * - `:backspace` remove the last character of the composed sentence
 * - `:clear` clear the sentence
 * - `:speak` speak the current sentence
 * - `:home` navigate to the board set root
 */
fun parseObfButtonAction(raw: String): ObfButtonActionEffect {
    val action = raw.trim()
    if (action.isEmpty()) return ObfButtonActionEffect.Unsupported(raw)
    return when {
        action.startsWith("+") -> ObfButtonActionEffect.AppendText(action.removePrefix("+"))
        action.equals(":space", ignoreCase = true) -> ObfButtonActionEffect.AppendText(" ")
        action.equals(":backspace", ignoreCase = true) -> ObfButtonActionEffect.Backspace
        action.equals(":clear", ignoreCase = true) -> ObfButtonActionEffect.Clear
        action.equals(":speak", ignoreCase = true) -> ObfButtonActionEffect.Speak
        action.equals(":home", ignoreCase = true) -> ObfButtonActionEffect.Home
        else -> ObfButtonActionEffect.Unsupported(action)
    }
}

fun parseObfButtonActions(button: ObfButton): List<ObfButtonActionEffect> =
    button.resolvedActions().map(::parseObfButtonAction)
