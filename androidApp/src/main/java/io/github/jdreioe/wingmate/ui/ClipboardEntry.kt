package io.github.jdreioe.wingmate.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

fun plainTextClipEntry(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("", text))
