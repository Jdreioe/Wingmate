import io.github.jdreioe.wingmate.domain.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsChatterboxTest {

    @Test
    fun defaultValuesAreCorrect() {
        val s = Settings()
        assertFalse(s.useChatterboxTts)
        assertEquals("", s.chatterboxReferenceAudioPath)
        assertEquals("", s.chatterboxModelDir)
        assertEquals("default", s.chatterboxPresetVoice)
        assertEquals("en", s.chatterboxLanguage)
    }

    @Test
    fun enableChatterboxTts() {
        val s = Settings(useChatterboxTts = true)
        assertTrue(s.useChatterboxTts)
    }

    @Test
    fun setChatterboxReferenceAudioPath() {
        val s = Settings(chatterboxReferenceAudioPath = "/sdcard/ref.wav")
        assertEquals("/sdcard/ref.wav", s.chatterboxReferenceAudioPath)
    }

    @Test
    fun setChatterboxModelDir() {
        val s = Settings(chatterboxModelDir = "/data/data/com.example/files/chatterbox_models")
        assertEquals("/data/data/com.example/files/chatterbox_models", s.chatterboxModelDir)
    }

    @Test
    fun setChatterboxPresetVoice() {
        val s = Settings(chatterboxPresetVoice = "voice_clone_1")
        assertEquals("voice_clone_1", s.chatterboxPresetVoice)
    }

    @Test
    fun setChatterboxLanguage() {
        val s = Settings(chatterboxLanguage = "zh")
        assertEquals("zh", s.chatterboxLanguage)
    }

    @Test
    fun chatterboxFieldsSurviveCopy() {
        val original = Settings(
            language = "da-DK",
            useChatterboxTts = true,
            chatterboxReferenceAudioPath = "/tmp/ref.wav",
            chatterboxModelDir = "/tmp/models",
            chatterboxPresetVoice = "custom",
            chatterboxLanguage = "da",
        )
        val copy = original.copy()
        assertTrue(copy.useChatterboxTts)
        assertEquals("/tmp/ref.wav", copy.chatterboxReferenceAudioPath)
        assertEquals("/tmp/models", copy.chatterboxModelDir)
        assertEquals("custom", copy.chatterboxPresetVoice)
        assertEquals("da", copy.chatterboxLanguage)
        assertEquals("da-DK", copy.language)
    }

    @Test
    fun enableChatterboxWhileKeepingSystemTtsDisabled() {
        val s = Settings(useSystemTts = false, useChatterboxTts = true)
        assertTrue(s.useChatterboxTts)
        assertFalse(s.useSystemTts)
    }
}
