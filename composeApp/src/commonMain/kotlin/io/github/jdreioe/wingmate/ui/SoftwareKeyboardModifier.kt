package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * A composable that returns a [Modifier] which requests the software keyboard to show
 * whenever the TextField gains focus. Apply this modifier to any TextField's modifier chain.
 *
 * Usage:
 * ```
 * val showKeyboard = rememberShowKeyboardOnFocus()
 * OutlinedTextField(
 *     ...
 *     modifier = Modifier.fillMaxWidth().then(showKeyboard)
 * )
 * ```
 */
@Composable
fun rememberShowKeyboardOnFocus(): Modifier {
    val keyboardController = LocalSoftwareKeyboardController.current
    return Modifier.onFocusChanged { focusState ->
        if (focusState.isFocused) {
            keyboardController?.show()
        }
    }
}
