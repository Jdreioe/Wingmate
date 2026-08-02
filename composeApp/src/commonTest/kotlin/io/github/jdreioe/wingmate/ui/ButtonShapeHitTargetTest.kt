package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfButtonShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ButtonShapeHitTargetTest {

    @Composable
    private fun target(shape: ObfButtonShape, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .size(width = 160.dp, height = 100.dp)
                .testTag("shape-hit-target")
                .combinedClickable(onClick = onClick),
            shape = shape.toShape()
        ) {}
    }

    @Test
    fun everyShapeKeepsTheEntireRectangularFieldClickable() = runComposeUiTest {
        ObfButtonShape.entries.forEach { shape ->
            var clickCount = 0
            setContent {
                MaterialTheme { target(shape) { clickCount++ } }
            }

            val node = onNodeWithTag("shape-hit-target")
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 159f && bounds.height >= 99f)

            // These corners are deliberately outside the drawn silhouette for
            // rounded, speech, and thought shapes. The action must still work.
            node.performTouchInput {
                down(Offset(1f, 1f))
                up()
            }
            runOnIdle { assertEquals(1, clickCount, "${shape.wireValue} corner tap") }
        }
    }
}
