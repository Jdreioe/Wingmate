package io.github.jdreioe.wingmate.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Decodes image bytes, or returns null when they are not a supported image. */
fun ByteArray.toComposeImageBitmap(): ImageBitmap? =
    BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()

/**
 * Decodes image bytes no larger than about [maxPixels] on their longest side, so a
 * full-size photo does not cost tens of megabytes. Returns null for unsupported data.
 */
fun ByteArray.decodeSampledBitmap(maxPixels: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPixels || bounds.outHeight / (sample * 2) >= maxPixels) sample *= 2
    return BitmapFactory.decodeByteArray(this, 0, size, BitmapFactory.Options().apply { inSampleSize = sample })
}
