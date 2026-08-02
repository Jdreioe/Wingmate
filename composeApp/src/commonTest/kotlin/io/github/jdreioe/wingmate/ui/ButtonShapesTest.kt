package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfButtonShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ButtonShapesTest {

    @Test
    fun roundedResolvesToDefaultRoundedCornerShape() {
        val shape = ObfButtonShape.Rounded.toShape()
        assertTrue(shape is RoundedCornerShape)
    }

    @Test
    fun squareResolvesToUnroundedCornerShape() {
        val shape = ObfButtonShape.Square.toShape() as RoundedCornerShape
        assertEquals(RoundedCornerShape(0.dp), shape)
    }

    @Test
    fun pillResolvesToFiftyPercentCapsule() {
        val shape = ObfButtonShape.Pill.toShape() as RoundedCornerShape
        // percent==50 yields a stadium/capsule for any width/height ratio,
        // so wide (2x1) and tall (1x2) spans both render a proper pill.
        assertEquals(RoundedCornerShape(percent = 50), shape)
    }

    @Test
    fun speechResolvesToSpeechBubbleShape() {
        assertTrue(ObfButtonShape.Speech.toShape() is SpeechBubbleShape)
    }

    @Test
    fun thoughtResolvesToThoughtBubbleShape() {
        assertTrue(ObfButtonShape.Thought.toShape() is ThoughtBubbleShape)
    }

    @Test
    fun bubbleShapesProduceOutlinesAcrossSpanRatios() {
        // Ensure the geometry handling doesn't crash or emit empty outlines for
        // the "weird" span sizes (1x1, 2x1 tall, 1x2 wide, 2x2) called out in
        // the acceptance criteria.
        val sizes = listOf(
            Size(100f, 100f),
            Size(50f, 200f),
            Size(200f, 50f),
            Size(150f, 150f)
        )
        listOf(SpeechBubbleShape(), ThoughtBubbleShape()).forEach { shape ->
            sizes.forEach { size ->
                val outline = shape.createOutline(size, LayoutDirection.Ltr, Density(1f)) as Outline.Generic
                assertTrue(!outline.path.isEmpty, "$shape must produce a non-empty path @ $size")
            }
        }
    }

    @Test
    fun bubbleGeometryStaysInsideTheFieldAndThoughtBubblesAreVisibleOutsideTheBody() {
        val sizes = listOf(
            Size(100f, 100f),
            Size(50f, 200f),
            Size(200f, 50f),
            Size(150f, 150f)
        )
        sizes.forEach { size ->
            val geometry = assertNotNull(thoughtBubbleGeometry(size))
            assertTrue(geometry.body.left >= 0f && geometry.body.right <= size.width)
            assertTrue(geometry.body.top >= 0f && geometry.body.bottom <= size.height)
            assertTrue(geometry.largeBubble.left >= 0f && geometry.largeBubble.right <= size.width)
            assertTrue(geometry.largeBubble.top > geometry.body.bottom)
            assertTrue(geometry.smallBubble.left >= 0f && geometry.smallBubble.right <= size.width)
            assertTrue(geometry.smallBubble.top >= geometry.body.bottom)
            assertTrue(geometry.smallBubble.bottom <= size.height)
        }
    }

    @Test
    fun bubbleOutlinesStayWithinTheirRectangularField() {
        val size = Size(200f, 100f)
        listOf(SpeechBubbleShape(), ThoughtBubbleShape()).forEach { shape ->
            val bounds = (shape.createOutline(size, LayoutDirection.Ltr, Density(1f)) as Outline.Generic)
                .path
                .getBounds()
            assertTrue(bounds.left >= 0f && bounds.top >= 0f)
            assertTrue(bounds.right <= size.width && bounds.bottom <= size.height)
        }
    }

    @Test
    fun zeroSizedBubbleDoesNotThrow() {
        listOf(SpeechBubbleShape(), ThoughtBubbleShape()).forEach { shape ->
            shape.createOutline(Size.Zero, LayoutDirection.Ltr, Density(1f))
        }
    }
}
