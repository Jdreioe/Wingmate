package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier

actual fun Modifier.platformImePadding(): Modifier = this.imePadding()
