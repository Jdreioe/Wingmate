package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StartupLoadStateTest {
    @Test
    fun `settings failure is not represented as first launch and retry can recover`() = runBlocking {
        var shouldFail = true
        val loadSettings: suspend () -> Settings = {
            if (shouldFail) error("database unavailable")
            Settings(welcomeFlowCompleted = true)
        }

        assertSame(
            StartupLoadState.Failed,
            loadStartupState(loadSettings = loadSettings, loadSelectedVoice = { null }),
        )

        shouldFail = false
        val recovered = loadStartupState(loadSettings = loadSettings, loadSelectedVoice = { null })

        assertEquals(true, (recovered as StartupLoadState.Ready).settings.welcomeFlowCompleted)
    }

    @Test
    fun `voice read failure is not represented as no selected voice`() = runBlocking {
        val failed = loadStartupState(
            loadSettings = { Settings(welcomeFlowCompleted = true) },
            loadSelectedVoice = { error("voice database unavailable") },
        )

        assertSame(StartupLoadState.Failed, failed)

        val recovered = loadStartupState(
            loadSettings = { Settings(welcomeFlowCompleted = true) },
            loadSelectedVoice = { null },
        )
        assertEquals(null, (recovered as StartupLoadState.Ready).selectedVoice)
    }

    @Test
    fun `setup is shown only to installs that never finished it and have no legacy voice`() {
        assertEquals(true, needsWelcome(Settings(), hasSelectedVoice = false))
        // Finished setup but the voice is gone (cleared, or restored without one): stay out of setup.
        assertEquals(false, needsWelcome(Settings(welcomeFlowCompleted = true), hasSelectedVoice = false))
        // Installs from before the completion flag decided routing.
        assertEquals(false, needsWelcome(Settings(), hasSelectedVoice = true))
    }
}
