package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.TtsEngine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhraseScreenBehaviorTest {

    @Test
    fun mathModeIsOnlyOfferedForAzureSpeechEngines() {
        assertFalse(supportsMathMode(TtsEngine.SYSTEM))
        assertTrue(supportsMathMode(TtsEngine.AZURE_USER_RESOURCE))
        assertTrue(supportsMathMode(TtsEngine.AZURE_MANAGED))
    }
}
