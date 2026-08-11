package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfButtonShape
import kotlin.math.max
import kotlin.math.min

/**
 * Corner radius (in dp) used for the rounded button shape.
 */
internal val ButtonDefaultCornerRadius = 12.dp

/**
 * Central shape resolution shared by run mode, edit mode, previews, image
 * clipping, borders, focus/selection rings and pressed states. Every caller
 * derives the same [Shape] so the visual treatment stays consistent.
 *
 * Shapes are drawn *within* the button bounds; the clickable/semantic target
 * remains the full rectangular cell (never shrunk by clipping), which preserves
 * the accessibility minimum target because grid cells already meet or exceed it.
 */
internal fun ObfButtonShape.toShape(): Shape = when (this) {
    ObfButtonShape.Rounded -> roundedShape()
    ObfButtonShape.Square -> squareShape()
    ObfButtonShape.Pill -> pillShape()
    ObfButtonShape.Speech -> SpeechBubbleShape()
    ObfButtonShape.Thought -> ThoughtBubbleShape()
}

internal fun roundedShape(): RoundedCornerShape = RoundedCornerShape(ButtonDefaultCornerRadius)

/** A crisp square/rectangle (no corner rounding). */
internal fun squareShape(): RoundedCornerShape = RoundedCornerShape(0.dp)

/**
 * A capsule whose rounded ends match the shorter dimension, so it renders as a
 * proper pill for square, wide (1xN) and tall (Nx1) spans alike.
 */
internal fun pillShape(): RoundedCornerShape = RoundedCornerShape(percent = 50)

/**
 * A rounded rectangle with a triangular tail. The body is inset just enough to
 * keep the visible tail inside the field bounds, so adjacent cells are never
 * overlapped and the full rectangular cell remains hit-testable.
 */
internal class SpeechBubbleShape(
    private val cornerRadius: Float = 12f
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Generic(Path().apply { close() })

        val r = min(cornerRadius.coerceAtLeast(0f), min(w, h) / 2f)
        // Tail dimensions scale with the shorter dimension and are clamped so a
        // 1xN or Nx1 span still yields a sane bubble.
        val tailHalf = max(min(h * 0.14f, 14f), 4f)
        val tailDepth = max(min(w * 0.18f, 20f), 5f)
        val tailCenterY = h * 0.62f

        val bodyLeft = tailDepth
        val path = Path()
        // Top edge of the inset bubble body.
        path.moveTo(bodyLeft + r, 0f)
        path.lineTo(w - r, 0f)
        path.quadraticTo(w, 0f, w, r)
        // Right edge.
        path.lineTo(w, h - r)
        path.quadraticTo(w, h, w - r, h)
        // Bottom edge.
        path.lineTo(bodyLeft + r, h)
        path.quadraticTo(bodyLeft, h, bodyLeft, h - r)
        // Tail projecting left of the inset body, but never beyond the field.
        path.lineTo(bodyLeft, tailCenterY + tailHalf)
        path.lineTo(0f, tailCenterY)
        path.lineTo(bodyLeft, tailCenterY - tailHalf)
        // Continue up the left edge to the top corner.
        path.lineTo(bodyLeft, r)
        path.quadraticTo(bodyLeft, 0f, bodyLeft + r, 0f)
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * A rounded rectangle with two detached, diminishing circles in a reserved
 * lower-left lane. Keeping the body inset makes the circles visibly read as a
 * thought bubble while the complete silhouette still fits inside one field.
 */
internal class ThoughtBubbleShape(
    private val cornerRadius: Float = 12f
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Generic(Path().apply { close() })

        val geometry = thoughtBubbleGeometry(size, cornerRadius)
            ?: return Outline.Generic(Path().apply { close() })

        val path = Path()
        path.drawRoundedBody(
            geometry.body.left,
            geometry.body.top,
            geometry.body.width,
            geometry.body.height,
            geometry.cornerRadius
        )
        // Detached bubbles occupy the reserved lane below and left of the body.
        path.addOval(geometry.largeBubble)
        val circle = Path()
        circle.addOval(geometry.smallBubble)
        path.addPath(circle)
        return Outline.Generic(path)
    }
}

internal data class ThoughtBubbleGeometry(
    val body: Rect,
    val largeBubble: Rect,
    val smallBubble: Rect,
    val cornerRadius: Float
)

/** Geometry is kept separate so span-ratio and within-bounds guarantees are testable. */
internal fun thoughtBubbleGeometry(size: Size, requestedCornerRadius: Float = 12f): ThoughtBubbleGeometry? {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return null

    val requestedRadius = min(requestedCornerRadius.coerceAtLeast(0f), min(w, h) / 2f)
    val largeCircle = max(min(min(w, h) * 0.075f, 7f), 3f)
    val smallCircle = (largeCircle * 0.62f).coerceAtLeast(1f)
    val bubbleLaneWidth = max(min(w * 0.22f, 28f), largeCircle * 3f)
        .coerceAtMost(w * 0.45f)
    val bubbleLaneHeight = max(min(h * 0.22f, 24f), largeCircle * 3f + 3f)
        .coerceAtMost(h * 0.45f)
    val bodyHeight = (h - bubbleLaneHeight).coerceAtLeast(largeCircle * 2f)
    val body = Rect(
        offset = Offset(bubbleLaneWidth, 0f),
        size = Size(w - bubbleLaneWidth, bodyHeight)
    )
    val bodyRadius = min(requestedRadius, min(body.width, body.height) / 2f)
    val largeBubble = Rect(
        offset = Offset(bubbleLaneWidth * 0.56f - largeCircle, bodyHeight + 1f),
        size = Size(largeCircle * 2, largeCircle * 2)
    )
    val smallBubble = Rect(
        offset = Offset((bubbleLaneWidth * 0.22f - smallCircle).coerceAtLeast(0f), h - smallCircle * 2f),
        size = Size(smallCircle * 2, smallCircle * 2)
    )
    return ThoughtBubbleGeometry(body, largeBubble, smallBubble, bodyRadius)
}

private fun Path.drawRoundedBody(left: Float, top: Float, width: Float, height: Float, radius: Float) {
    val right = left + width
    val bottom = top + height
    val r = min(radius, min(width, height) / 2f).coerceAtLeast(0f)
    moveTo(left + r, top)
    lineTo(right - r, top)
    quadraticTo(right, top, right, top + r)
    lineTo(right, bottom - r)
    quadraticTo(right, bottom, right - r, bottom)
    lineTo(left + r, bottom)
    quadraticTo(left, bottom, left, bottom - r)
    lineTo(left, top + r)
    quadraticTo(left, top, left + r, top)
    close()
}
