package io.github.jdreioe.wingmate.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Decodes image bytes, or returns null when they are not a supported image. */
fun ByteArray.toComposeImageBitmap(): ImageBitmap? =
    BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
