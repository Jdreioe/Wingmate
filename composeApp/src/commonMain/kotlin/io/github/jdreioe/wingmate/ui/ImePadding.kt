package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.Modifier

// Expect/actual shim so we can apply IME padding only on Android
expect fun Modifier.platformImePadding(): Modifier
