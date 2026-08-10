package io.github.jdreioe.wingmate.kde

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickCorePresetTest {
    @Test
    fun presetUrlsAreWhitelisted() {
        assertEquals(
            "https://openboards.s3.amazonaws.com/examples/quick-core-24.obz",
            quickCorePresetUrl("quick-core-24"),
        )
        assertEquals(
            "https://openboards.s3.amazonaws.com/examples/quick-core-112.obz",
            quickCorePresetUrl("QUICK-CORE-112"),
        )
        assertNull(quickCorePresetUrl("https://example.com/untrusted.obz"))
        assertNull(quickCorePresetUrl("quick-core-999"))
    }
}
