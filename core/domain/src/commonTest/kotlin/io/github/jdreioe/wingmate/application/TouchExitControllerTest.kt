package io.github.jdreioe.wingmate.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchExitControllerTest {

    @Test
    fun beginOnButtonHighlightsAndPreviewsOnceUntilTargetChanges() {
        val controller = TouchExitController()

        assertEquals(TouchExitEffect.Preview("btn-a"), controller.begin("btn-a"))
        assertEquals(TouchExitState(isTracking = true, activeTargetId = "btn-a"), controller.state)

        // Staying on the same Button does not re-fire preview.
        assertNull(controller.move("btn-a"))
        assertEquals("btn-a", controller.state.activeTargetId)

        // Sliding to a neighbor moves highlight and previews the neighbor.
        assertEquals(TouchExitEffect.Preview("btn-b"), controller.move("btn-b"))
        assertEquals("btn-b", controller.state.activeTargetId)
    }

    @Test
    fun slidingOntoEmptySpaceClearsHighlightAndReenteringPreviewsAgain() {
        val controller = TouchExitController()
        controller.begin("btn-a")

        assertNull(controller.move(null))
        assertEquals(TouchExitState(isTracking = true, activeTargetId = null), controller.state)

        // Re-entering the original Button previews it again.
        assertEquals(TouchExitEffect.Preview("btn-a"), controller.move("btn-a"))
    }

    @Test
    fun liftingOnButtonActivatesAndClearsTrackingState() {
        val controller = TouchExitController()
        controller.begin("btn-a")
        controller.move("btn-b")

        assertEquals(TouchExitEffect.Activate("btn-b"), controller.release("btn-b"))
        assertEquals(TouchExitState(isTracking = false, activeTargetId = null), controller.state)

        // Stray moves after release are ignored.
        assertNull(controller.move("btn-c"))
    }

    @Test
    fun liftingOutsideAnyButtonCancelsSafelyWithoutActivation() {
        val controller = TouchExitController()
        controller.begin("btn-a")
        controller.move(null)

        assertEquals(TouchExitEffect.Cancelled, controller.release(null))
        assertEquals(TouchExitState(isTracking = false, activeTargetId = null), controller.state)
    }

    @Test
    fun beginningOnEmptySpaceStillTracksSlideOntoButton() {
        val controller = TouchExitController()

        assertNull(controller.begin(null))
        assertTrue(controller.state.isTracking)
        assertNull(controller.state.activeTargetId)

        assertEquals(TouchExitEffect.Preview("btn-a"), controller.move("btn-a"))
        assertEquals(TouchExitEffect.Activate("btn-a"), controller.release("btn-a"))
    }

    @Test
    fun systemCancelClearsStateWithoutActivatingAndIsIdempotent() {
        val controller = TouchExitController()
        assertNull(controller.cancel())

        controller.begin("btn-a")
        assertEquals(TouchExitEffect.Cancelled, controller.cancel())
        assertEquals(TouchExitState(isTracking = false, activeTargetId = null), controller.state)
        assertNull(controller.cancel())
        assertEquals(TouchExitEffect.Cancelled, controller.release("btn-a"))
    }

    @Test
    fun precedenceRulesMatchSharedTouchAccessPolicy() {
        assertTrue(touchExitIsActive(isEditMode = false, holdToSelectMillis = 0, selectOnTouchDown = false, selectOnTouchExit = true))
        assertFalse(touchExitIsActive(isEditMode = true, holdToSelectMillis = 0, selectOnTouchDown = false, selectOnTouchExit = true))
        assertFalse(touchExitIsActive(isEditMode = false, holdToSelectMillis = 300, selectOnTouchDown = false, selectOnTouchExit = true))
        assertFalse(touchExitIsActive(isEditMode = false, holdToSelectMillis = 0, selectOnTouchDown = true, selectOnTouchExit = true))
        assertFalse(touchExitIsActive(isEditMode = false, holdToSelectMillis = 0, selectOnTouchDown = false, selectOnTouchExit = false))

        assertTrue(pageSwipeIsActive(isEditMode = false, swipeBetweenPages = true, holdToSelectMillis = 0, selectOnTouchDown = false, selectOnTouchExit = false))
        assertFalse(pageSwipeIsActive(isEditMode = false, swipeBetweenPages = true, holdToSelectMillis = 0, selectOnTouchDown = false, selectOnTouchExit = true))
        assertFalse(pageSwipeIsActive(isEditMode = false, swipeBetweenPages = true, holdToSelectMillis = 0, selectOnTouchDown = true, selectOnTouchExit = false))
        assertFalse(pageSwipeIsActive(isEditMode = false, swipeBetweenPages = true, holdToSelectMillis = 200, selectOnTouchDown = false, selectOnTouchExit = false))
        assertTrue(pageSwipeIsActive(isEditMode = false, swipeBetweenPages = true, holdToSelectMillis = 200, selectOnTouchDown = false, selectOnTouchExit = false, disableOnHoldToSelect = false))
        assertTrue(pageSwipeIsActive(isEditMode = false, swipeBetweenPages = true, holdToSelectMillis = 200, selectOnTouchDown = false, selectOnTouchExit = true, disableOnHoldToSelect = false))
        assertFalse(pageSwipeIsActive(isEditMode = false, swipeBetweenPages = true, holdToSelectMillis = 0, selectOnTouchDown = false, selectOnTouchExit = true, disableOnHoldToSelect = false))
        assertFalse(pageSwipeIsActive(isEditMode = true, swipeBetweenPages = true, holdToSelectMillis = 0, selectOnTouchDown = false, selectOnTouchExit = false))
    }
}
