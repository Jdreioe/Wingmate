import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.TtsEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SettingsModelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun newInstallDefaultsToSystem() {
        val settings = Settings()
        assertEquals(TtsEngine.SYSTEM, settings.ttsEngine)
    }

    @Test
    fun canSetAzureUserResource() {
        val settings = Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE)
        assertEquals(TtsEngine.AZURE_USER_RESOURCE, settings.ttsEngine)
    }

    @Test
    fun canSetManaged() {
        val settings = Settings(ttsEngine = TtsEngine.AZURE_MANAGED)
        assertEquals(TtsEngine.AZURE_MANAGED, settings.ttsEngine)
    }

    @Test
    fun jsonRoundTripProducesNewFormat() {
        val original = Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Settings>(encoded)
        assertEquals(TtsEngine.AZURE_USER_RESOURCE, decoded.ttsEngine)
    }

    @Test
    fun jsonRoundTripSystem() {
        val original = Settings(ttsEngine = TtsEngine.SYSTEM)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Settings>(encoded)
        assertEquals(TtsEngine.SYSTEM, decoded.ttsEngine)
    }

    @Test
    fun oldFormatWithIgnoreUnknownKeysPreservesDefaults() {
        // Simulate old JSON that had useSystemTts instead of ttsEngine
        val oldJson = """{"language":"en-US","voice":"default","speechRate":1.0,"useSystemTts":true}"""
        val settings = json.decodeFromString<Settings>(oldJson)
        // With ignoreUnknownKeys = true, the old useSystemTts field is ignored
        // ttsEngine gets the default: SYSTEM
        assertEquals(TtsEngine.SYSTEM, settings.ttsEngine)
    }

    @Test
    fun oldFormatWithUseSystemTtsFalseDefaultsToSystem() {
        // Old format with useSystemTts=false. No ttsEngine field.
        // Since we use ignoreUnknownKeys=true, useSystemTts is ignored
        // and ttsEngine defaults to SYSTEM. Migration happens later in SettingsStateManager.
        val oldJson = """{"language":"en-US","voice":"default","speechRate":1.0,"useSystemTts":false}"""
        val settings = json.decodeFromString<Settings>(oldJson)
        assertEquals(TtsEngine.SYSTEM, settings.ttsEngine)
    }

    @Test
    fun newFormatDoesNotIncludeOldField() {
        // Verify that serializing with the new format does NOT include useSystemTts
        val settings = Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE)
        val encoded = json.encodeToString(settings)
        // The old field name should not appear
        assert(!encoded.contains("useSystemTts")) { "New serialization should not contain old useSystemTts field" }
    }

    @Test
    fun newFormatContainsTtsEngine() {
        val settings = Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE)
        val encoded = json.encodeToString(settings)
        assert(encoded.contains("ttsEngine")) { "New serialization should contain ttsEngine field" }
    }

    @Test
    fun migrationIdempotent() {
        // Simulate: First migration produces ttsEngine, second read should keep it
        val oldJson = """{"language":"en-US","voice":"default","speechRate":1.0,"useSystemTts":false}"""

        // First decode (simulates first read with migration)
        val firstRead = json.decodeFromString<Settings>(oldJson)
        assertEquals(TtsEngine.SYSTEM, firstRead.ttsEngine) // old field ignored, default applied

        // Manually migrate (as SettingsStateManager would)
        val migrated = firstRead.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE)
        val migratedJson = json.encodeToString(migrated)

        // Second read (simulates subsequent read after migration)
        val secondRead = json.decodeFromString<Settings>(migratedJson)
        assertEquals(TtsEngine.AZURE_USER_RESOURCE, secondRead.ttsEngine)

        // Third read should be identical
        val thirdRead = json.decodeFromString<Settings>(migratedJson)
        assertEquals(secondRead, thirdRead)
    }

    @Test
    fun settingsCopyWithTtsEngine() {
        val original = Settings(language = "da-DK", voice = "da-DK-ChristelNeural")
        val updated = original.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE)
        assertEquals(TtsEngine.AZURE_USER_RESOURCE, updated.ttsEngine)
        assertEquals("da-DK", updated.language) // other fields preserved
    }
}
