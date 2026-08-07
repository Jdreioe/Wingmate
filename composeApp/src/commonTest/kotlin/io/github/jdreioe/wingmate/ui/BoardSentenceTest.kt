package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.infrastructure.InMemoryFileStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardSentenceTest {

    @Test
    fun soundPlaybackFallsBackFromMalformedDataToStoredPath() = runBlocking {
        val storage = InMemoryFileStorage()
        storage.saveBytes("sound.mp3", byteArrayOf(7, 8))
        val player = CapturingSoundPlayer()
        val played = playButtonSound(
            ObfSound(id = "sound", data = "broken!", path = "sound.mp3"),
            storage,
            player
        )
        assertTrue(played)
        assertEquals(listOf<Byte>(7, 8), player.lastBytes?.toList())
    }

    @Test
    fun failedSoundUrlLeavesTtsFallbackAvailable() = runBlocking {
        val played = playButtonSound(
            ObfSound(id = "sound", url = "https://unavailable.example/sound"),
            InMemoryFileStorage(),
            CapturingSoundPlayer(),
            ObfMediaUrlLoader { null }
        )
        assertFalse(played)
    }

    private class CapturingSoundPlayer : SoundPlayer {
        var lastBytes: ByteArray? = null
        override suspend fun playBytes(bytes: ByteArray, contentType: String?): Boolean {
            lastBytes = bytes.copyOf()
            return true
        }
    }
}
