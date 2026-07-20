package io.github.jdreioe.wingmate.infrastructure

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatterboxDeviceBenchmarkTest {
    @Test
    fun synthesizeShortDefaultVoicePhrase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = ChatterboxModelDownloader.getModelDir(context)
        assumeTrue("Pinned Chatterbox model is not installed", modelDir.isDirectory)
        val tokenizerFile = modelDir.resolve("tokenizer.json")
        assumeTrue("Tokenizer is not installed", tokenizerFile.isFile)

        val engine = ChatterboxTtsEngine(modelDir, ChatterboxTokenizer(tokenizerFile.absolutePath))
        try {
            engine.load().getOrThrow()
            val pcm = engine.synthesize("Hello, this is a speed test.", "en")
            assertTrue("Expected non-empty synthesized PCM", pcm.isNotEmpty())
        } finally {
            engine.unload()
        }
    }
}
