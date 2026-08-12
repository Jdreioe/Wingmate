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
}
