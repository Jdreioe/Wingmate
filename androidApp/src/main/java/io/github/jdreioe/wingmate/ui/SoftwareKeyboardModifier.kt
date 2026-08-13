package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Requests the software keyboard whenever the modified text field gains focus.
 *
 * Usage:
 * ```
 * OutlinedTextField(
 *     ...
 *     modifier = Modifier.fillMaxWidth().showKeyboardOnFocus()
 * )
 * ```
 */
@Composable
fun Modifier.showKeyboardOnFocus(): Modifier {
    val keyboardController = LocalSoftwareKeyboardController.current
    return onFocusChanged { focusState ->
        if (focusState.isFocused) {
            keyboardController?.show()
        }
    }
}
