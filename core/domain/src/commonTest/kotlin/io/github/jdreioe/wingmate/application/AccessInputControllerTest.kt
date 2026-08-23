package io.github.jdreioe.wingmate.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessInputControllerTest {
    @Test
    fun selectKeyActivatesCurrentTargetOncePerPress() {
        val controller = AccessInputController()
        controller.targetEntered("phrase:a", 0)

        assertEquals(AccessInputEffect.Activate("phrase:a"), controller.keyDown("Space", "Space", "F8", 1))
        assertNull(controller.keyDown("Space", "Space", "F8", 2))
        assertNull(controller.tick(10_000, 500), "the same hover must not dwell-activate after switch selection")
        controller.keyUp("Space", 2)
        assertEquals(AccessInputEffect.Activate("phrase:a"), controller.keyDown("Space", "Space", "F8", 3))
    }

    @Test
    fun hoverTakesPriorityAndFocusResumesAfterExit() {
        val controller = AccessInputController()
        controller.targetFocused("phrase:focused", 0)
        controller.targetEntered("phrase:hovered", 1)
        assertEquals("phrase:hovered", controller.state.currentTargetId)
        controller.targetFocused("phrase:other-focus", 400)
        assertEquals(
            AccessInputEffect.Activate("phrase:hovered"),
            controller.tick(501, 500),
            "a lower-priority focus change must not restart the hover timer",
        )
        controller.targetExited("phrase:hovered", 502)
        assertEquals("phrase:other-focus", controller.state.currentTargetId)
    }

    @Test
    fun restModeBlocksSelectAndCancelsDwellUntilFreshTimeout() {
        val controller = AccessInputController()
        controller.targetEntered("phrase:a", 0)
        assertNull(controller.tick(499, 500))
        assertEquals(AccessInputEffect.PauseChanged(true), controller.keyDown("F8", "Space", "F8", 499))
        assertTrue(controller.state.isPaused)
        assertNull(controller.tick(2_000, 500))
        controller.keyUp("F8", 500)
        assertEquals(AccessInputEffect.PauseChanged(false), controller.keyDown("F8", "Space", "F8", 2_000))
        assertNull(controller.tick(2_499, 500))
        assertIs<AccessInputEffect.Activate>(controller.tick(2_500, 500))
    }

    @Test
    fun dwellFiresOnlyOnceUntilTargetIsReentered() {
        val controller = AccessInputController()
        controller.targetEntered("phrase:a", 0)
        assertEquals(AccessInputEffect.Activate("phrase:a"), controller.tick(500, 500))
        assertNull(controller.tick(1_000, 500))
        controller.targetExited("phrase:a", 1_001)
        controller.targetEntered("phrase:a", 1_002)
        assertEquals(AccessInputEffect.Activate("phrase:a"), controller.tick(1_502, 500))
    }

    @Test
    fun rearmDelayDelaysDwellStartAfterTargetChange() {
        val controller = AccessInputController()
        controller.rearmDelayMillis = 200
        controller.targetEntered("phrase:a", 0)
        // Dwell timer is armed at t=200; nothing before that.
        assertNull(controller.tick(150, 500))
        assertNull(controller.tick(650, 500), "still inside the dwell window")
        assertEquals(AccessInputEffect.Activate("phrase:a"), controller.tick(700, 500))
    }

    @Test
    fun transientHoverDuringRearmNeverActivatesNeighbor() {
        val controller = AccessInputController()
        controller.rearmDelayMillis = 200
        controller.targetEntered("phrase:a", 0)
        // Tremor jitter: brief contact with the neighbor and back before either rearm elapses.
        controller.targetEntered("phrase:b", 50)
        controller.targetExited("phrase:b", 60)
        controller.targetEntered("phrase:a", 70)
        assertNull(controller.tick(400, 500), "jitter must not produce an early activation")
        assertEquals(AccessInputEffect.Activate("phrase:a"), controller.tick(770, 500))
    }

    @Test
    fun holdingSelectKeyWhilePausedResumesInput() {
        val controller = AccessInputController()
        controller.targetEntered("phrase:a", 0)
        assertEquals(AccessInputEffect.PauseChanged(true), controller.keyDown("F8", "Space", "F8", 10))
        assertTrue(controller.state.isPaused)

        // Short tap does NOT resume.
        assertNull(controller.keyDown("Space", "Space", "F8", 100))
        assertNull(controller.keyUp("Space", 600))
        assertTrue(controller.state.isPaused)

        // Holding for >= SELECT_HOLD_RESUME_MILLIS resumes.
        assertNull(controller.keyDown("Space", "Space", "F8", 700))
        assertEquals(
            AccessInputEffect.PauseChanged(false),
            controller.keyUp("Space", 700 + SELECT_HOLD_RESUME_MILLIS),
        )
        assertFalse(controller.state.isPaused)
    }
}
