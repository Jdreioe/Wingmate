package io.github.jdreioe.wingmate.application

import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureUsageEventSanitizerTest {

    @Test
    fun freeTextValuesNeverLeaveTheDevice() {
        val props = FeatureUsageEventSanitizer.props(
            mapOf(
                "engine" to "AZURE",
                "count" to "12",
                "phrase" to "I need my medicine",
                "long" to "a".repeat(101),
            )
        )

        assertEquals(
            mapOf("engine" to "AZURE", "count" to "12", "phrase" to "redacted", "long" to "redacted"),
            props,
        )
    }

    @Test
    fun namesAreNormalizedToAptabaseSafeIdentifiers() {
        assertEquals("screen_view", FeatureUsageEventSanitizer.eventName(" Screen View "))
        assertEquals("e_1st", FeatureUsageEventSanitizer.eventName("1st"))
        assertEquals(setOf("board_id"), FeatureUsageEventSanitizer.props(mapOf("Board-Id" to "x")).keys)
    }
}
