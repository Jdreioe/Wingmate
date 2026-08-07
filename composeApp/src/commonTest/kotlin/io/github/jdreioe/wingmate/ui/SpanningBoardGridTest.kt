package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.GridFieldSpan
import io.github.jdreioe.wingmate.domain.obf.availableFieldSpansAt
import io.github.jdreioe.wingmate.domain.obf.withFieldSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.board_resize_blocked_bounds
import wingmatekmp.composeapp.generated.resources.board_resize_blocked_occupied
import wingmatekmp.composeapp.generated.resources.board_resize_decrease_height
import wingmatekmp.composeapp.generated.resources.board_resize_decrease_width
import wingmatekmp.composeapp.generated.resources.board_resize_increase_height
import wingmatekmp.composeapp.generated.resources.board_resize_increase_width

@OptIn(ExperimentalTestApi::class)
class SpanningBoardGridTest {

    private val fieldButton = ObfButton(id = "field", label = "Field")
    private val otherButton = ObfButton(id = "other", label = "Other")

    private fun emptyGrid(rows: Int, columns: Int) = ObfGrid(
        rows = rows,
        columns = columns,
        order = List(rows) { List(columns) { null } }
    )

    private fun ComposeUiTest.hostGrid(
        initialGrid: ObfGrid,
        commits: MutableList<Triple<Int, Int, GridFieldSpan>>,
        labels: MutableMap<String, String> = mutableMapOf()
    ) {
        val buttonsById = mapOf("field" to fieldButton, "other" to otherButton)
        setContent {
            var grid by remember { mutableStateOf(initialGrid) }
            MaterialTheme {
                labels += mapOf(
                    "increaseWidth" to stringResource(Res.string.board_resize_increase_width),
                    "decreaseWidth" to stringResource(Res.string.board_resize_decrease_width),
                    "increaseHeight" to stringResource(Res.string.board_resize_increase_height),
                    "decreaseHeight" to stringResource(Res.string.board_resize_decrease_height),
                    "blockedBounds" to stringResource(Res.string.board_resize_blocked_bounds),
                    "blockedOccupied" to stringResource(Res.string.board_resize_blocked_occupied)
                )
                Box(Modifier.size(400.dp)) {
                    SpanningBoardGrid(
                        rows = grid.rows,
                        columns = grid.columns,
                        items = buildBoardGridItems(grid, buttonsById),
                        modifier = Modifier.fillMaxSize(),
                        selectedField = 0 to 0,
                        selectedFieldSpans = grid.availableFieldSpansAt(0, 0),
                        onResizeField = { row, column, rowSpan, columnSpan ->
                            commits += Triple(row, column, GridFieldSpan(rowSpan, columnSpan))
                            grid = grid.withFieldSpan(row, column, "field", rowSpan, columnSpan) ?: grid
                        }
                    ) { Box(Modifier.fillMaxSize().background(Color.LightGray)) }
                }
            }
        }
    }

    @Test
    fun resizeHandleAppearsAtTheFieldCornerAndIsAtLeast48Dp() = runComposeUiTest {
        val grid = emptyGrid(2, 2).withFieldSpan(0, 0, "field", 1, 1)!!
        hostGrid(grid, mutableListOf())

        val handle = onNodeWithTag("resize-handle")
        handle.assertExists()
        val bounds = handle.fetchSemanticsNode().boundsInRoot
        assertEquals(48f, bounds.width)
        assertEquals(48f, bounds.height)
        assertTrue(bounds.left in 172f..177f, "handle x was ${bounds.left}")
        assertTrue(bounds.top in 170f..175f, "handle y was ${bounds.top}")
    }

    @Test
    fun draggingTheHandleHorizontallyOnlyGrowsTheWidth() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val grid = emptyGrid(2, 2).withFieldSpan(0, 0, "field", 1, 1)!!
        hostGrid(grid, commits)

        onNodeWithTag("resize-handle").performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            up()
        }
        runOnIdle {
            assertEquals(listOf(Triple(0, 0, GridFieldSpan(1, 2))), commits)
        }
    }

    @Test
    fun draggingTheHandleGrowsTheFieldAndCommitsOnRelease() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val grid = emptyGrid(2, 2).withFieldSpan(0, 0, "field", 1, 1)!!
        hostGrid(grid, commits)

        onNodeWithTag("resize-handle").performTouchInput {
            down(center)
            moveBy(Offset(20f, 20f))
            up()
        }
        runOnIdle {
            assertEquals(listOf(Triple(0, 0, GridFieldSpan(2, 2))), commits)
            onNodeWithTag("resize-preview").assertDoesNotExist()
        }
    }

    @Test
    fun draggingOverAnotherFieldShowsAnInvalidPreview() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val grid = emptyGrid(2, 2)
            .withFieldSpan(0, 0, "field", 1, 1)!!
            .withFieldSpan(1, 1, "other", 1, 1)!!
        hostGrid(grid, commits)

        onNodeWithTag("resize-handle").performTouchInput {
            down(center)
            moveBy(Offset(20f, 20f))
        }
        runOnIdle {
            val preview = onNodeWithTag("resize-preview")
            preview.assertExists()
            assertEquals(
                listOf("resize-preview-invalid"),
                preview.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            )
            assertTrue(commits.isEmpty())
        }
    }

    @Test
    fun releasingAnInvalidDragDoesNotCommit() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val labels = mutableMapOf<String, String>()
        val grid = emptyGrid(2, 2)
            .withFieldSpan(0, 0, "field", 1, 1)!!
            .withFieldSpan(1, 1, "other", 1, 1)!!
        hostGrid(grid, commits, labels)

        onNodeWithTag("resize-handle").performTouchInput {
            down(center)
            moveBy(Offset(20f, 20f))
            up()
        }
        runOnIdle {
            assertTrue(commits.isEmpty())
            onNodeWithTag("resize-preview").assertDoesNotExist()
            onNodeWithContentDescription(labels["blockedOccupied"]!!).assertExists()
        }
    }

    @Test
    fun releasingAnOutOfBoundsDragAnnouncesTheBlockingReason() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val labels = mutableMapOf<String, String>()
        val grid = emptyGrid(2, 2).withFieldSpan(0, 0, "field", 2, 2)!!
        hostGrid(grid, commits, labels)

        onNodeWithTag("resize-handle").performTouchInput {
            down(center)
            moveBy(Offset(100f, 100f))
            up()
        }
        runOnIdle {
            assertTrue(commits.isEmpty())
            onNodeWithContentDescription(labels["blockedBounds"]!!).assertExists()
        }
    }

    @Test
    fun cancelingBeforeTheDragStartsShowsNoPreview() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val grid = emptyGrid(2, 2).withFieldSpan(0, 0, "field", 1, 1)!!
        hostGrid(grid, commits)

        onNodeWithTag("resize-handle").performTouchInput {
            down(center)
            cancel()
        }
        runOnIdle {
            assertTrue(commits.isEmpty())
            onNodeWithTag("resize-preview").assertDoesNotExist()
        }
    }

    @Test
    fun cancelingTheDragDoesNotCommitAndLeavesTheHandleUsable() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val grid = emptyGrid(2, 2).withFieldSpan(0, 0, "field", 1, 1)!!
        hostGrid(grid, commits)

        onNodeWithTag("resize-handle").performTouchInput {
            down(center)
            moveBy(Offset(20f, 20f))
            cancel()
        }
        runOnIdle {
            assertTrue(commits.isEmpty())
        }
        // A new gesture must still work normally after the cancellation, and the
        // grid must end in a clean state without a lingering preview.
        onNodeWithTag("resize-handle").performTouchInput {
            down(center)
            moveBy(Offset(20f, 20f))
            up()
        }
        runOnIdle {
            assertEquals(listOf(Triple(0, 0, GridFieldSpan(2, 2))), commits)
            onNodeWithTag("resize-preview").assertDoesNotExist()
        }
    }

    @Test
    fun shiftArrowKeysResizeTheSelectedField() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val grid = emptyGrid(2, 2).withFieldSpan(0, 0, "field", 1, 1)!!
        hostGrid(grid, commits)

        val handle = onNodeWithTag("resize-handle")
        handle.requestFocus()
        handle.performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) }
        }
        runOnIdle {
            assertEquals(listOf(Triple(0, 0, GridFieldSpan(1, 2))), commits)
        }
    }

    @Test
    fun invalidKeyboardResizeAnnouncesTheBlockingReason() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val labels = mutableMapOf<String, String>()
        val grid = emptyGrid(2, 2)
            .withFieldSpan(0, 0, "field", 1, 1)!!
            .withFieldSpan(1, 1, "other", 1, 1)!!
        hostGrid(grid, commits, labels)

        val handle = onNodeWithTag("resize-handle")
        handle.requestFocus()
        handle.performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) }
        }
        runOnIdle {
            assertTrue(commits.isEmpty())
            onNodeWithContentDescription(labels["blockedBounds"]!!).assertExists()
        }
    }

    @Test
    fun customAccessibilityActionsResizeInBothDirections() = runComposeUiTest {
        val commits = mutableListOf<Triple<Int, Int, GridFieldSpan>>()
        val labels = mutableMapOf<String, String>()
        val grid = emptyGrid(2, 2).withFieldSpan(0, 0, "field", 1, 1)!!
        hostGrid(grid, commits, labels)

        val handle = onNodeWithTag("resize-handle")
        handle.performCustomAccessibilityActionWithLabel(labels["increaseWidth"]!!)
        handle.performCustomAccessibilityActionWithLabel(labels["increaseHeight"]!!)
        handle.performCustomAccessibilityActionWithLabel(labels["decreaseHeight"]!!)
        handle.performCustomAccessibilityActionWithLabel(labels["decreaseWidth"]!!)
        runOnIdle {
            assertEquals(
                listOf(
                    Triple(0, 0, GridFieldSpan(1, 2)),
                    Triple(0, 0, GridFieldSpan(2, 2)),
                    Triple(0, 0, GridFieldSpan(1, 2)),
                    Triple(0, 0, GridFieldSpan(1, 1))
                ),
                commits
            )
        }

        handle.performCustomAccessibilityActionWithLabel(labels["decreaseWidth"]!!)
        runOnIdle {
            assertEquals(4, commits.size)
            onNodeWithContentDescription(labels["blockedBounds"]!!).assertExists()
        }
    }
}
