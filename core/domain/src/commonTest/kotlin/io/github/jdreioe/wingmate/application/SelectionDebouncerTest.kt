package io.github.jdreioe.wingmate.application

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionDebouncerTest {

    @Test
    fun firstActivation_isAlwaysAllowed() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 300L))
    }

    @Test
    fun repeatedHitInsideWindow_isIgnored() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-a", nowMillis = 1100L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-a", nowMillis = 1299L, debounceMillis = 300L))
    }

    @Test
    fun hitExactlyAtWindowBoundary_isAllowed() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 300L))
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1300L, debounceMillis = 300L))
    }

    @Test
    fun hitAfterWindowExpires_isAllowed() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-a", nowMillis = 1299L, debounceMillis = 300L))
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1500L, debounceMillis = 300L))
    }

    @Test
    fun differentTargets_remainResponsive() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-a", nowMillis = 1100L, debounceMillis = 300L))
        assertTrue(debouncer.tryActivate("target-b", nowMillis = 1100L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-b", nowMillis = 1150L, debounceMillis = 300L))
        assertTrue(debouncer.tryActivate("target-c", nowMillis = 1150L, debounceMillis = 300L))
    }

    @Test
    fun rejectedHit_doesNotExtendTheWindow() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-a", nowMillis = 1100L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-a", nowMillis = 1299L, debounceMillis = 300L))
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1300L, debounceMillis = 300L))
    }

    @Test
    fun zeroDuration_disablesDebounceCompletely() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 0L))
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1001L, debounceMillis = 0L))
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1002L, debounceMillis = 0L))
    }

    @Test
    fun changingDebounceDuration_takesEffectImmediately() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-a", nowMillis = 1100L, debounceMillis = 300L))
        // Reconfigured to zero: everything allowed again regardless of prior hits.
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1100L, debounceMillis = 0L))
    }

    @Test
    fun targetsStayTrackedIndependentlyAfterLongInactivity() {
        val debouncer = SelectionDebouncer()
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 1000L, debounceMillis = 300L))
        assertTrue(debouncer.tryActivate("target-a", nowMillis = 5000L, debounceMillis = 300L))
        assertFalse(debouncer.tryActivate("target-a", nowMillis = 5100L, debounceMillis = 300L))
    }
}
