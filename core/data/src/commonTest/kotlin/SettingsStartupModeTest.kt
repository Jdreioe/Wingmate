import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.StartupMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsStartupModeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oldSettingsDefaultToKeyboardMode() {
        val settings = json.decodeFromString<Settings>("{}")

        assertEquals(StartupMode.Keyboard, settings.startupMode)
        assertNull(settings.startupBoardSetId)
    }

    @Test
    fun screensModeSurvivesPersistenceRoundTrip() {
        val encoded = json.encodeToString(Settings(startupMode = StartupMode.Screens))
        val decoded = json.decodeFromString<Settings>(encoded)

        assertEquals(StartupMode.Screens, decoded.startupMode)
    }

    @Test
    fun selectedStartupScreenSurvivesPersistenceRoundTrip() {
        val encoded = json.encodeToString(
            Settings(
                startupMode = StartupMode.Screens,
                startupBoardSetId = "screen-people"
            )
        )
        val decoded = json.decodeFromString<Settings>(encoded)

        assertEquals("screen-people", decoded.startupBoardSetId)
    }
}
