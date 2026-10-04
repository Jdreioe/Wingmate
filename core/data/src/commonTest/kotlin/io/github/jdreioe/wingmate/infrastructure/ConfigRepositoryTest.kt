package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigRepositoryTest {
    @Test
    fun saveStatusLoadAndDelete() = runBlocking {
        val repository = InMemoryConfigRepository()
        val config = SpeechServiceConfig("northeurope", "azure-secret")

        repository.saveSpeechConfig(config)

        assertEquals(config, repository.getSpeechConfig())
        assertEquals("northeurope", repository.getSpeechConfigStatus().endpoint)
        assertTrue(repository.getSpeechConfigStatus().credentialConfigured)

        repository.clearSpeechConfig()

        assertNull(repository.getSpeechConfig())
        assertFalse(repository.getSpeechConfigStatus().credentialConfigured)
    }

    @Test
    fun saveCanonicalizesEndpointAndCredential() = runBlocking {
        val repository = InMemoryConfigRepository()

        repository.saveSpeechConfig(
            SpeechServiceConfig(
                endpoint = "  HTTPS://NorthEurope.api.cognitive.microsoft.com/  ",
                subscriptionKey = "  azure-secret  ",
            )
        )

        assertEquals(
            SpeechServiceConfig(
                endpoint = "northeurope",
                subscriptionKey = "azure-secret",
            ),
            repository.getSpeechConfig(),
        )
    }

    @Test
    fun saveRejectsUntrustedEndpointWithoutReplacingExistingConfig() = runBlocking {
        val repository = InMemoryConfigRepository()
        val existing = SpeechServiceConfig("northeurope", "existing-secret")
        repository.saveSpeechConfig(existing)

        assertFailsWith<IllegalArgumentException> {
            repository.saveSpeechConfig(
                SpeechServiceConfig("https://attacker.example", "new-secret")
            )
        }

        assertEquals(existing, repository.getSpeechConfig())
    }

    @Test
    fun configStringRepresentationRedactsCredential() {
        val rendered = SpeechServiceConfig("northeurope", "azure-secret").toString()

        assertTrue(rendered.contains("northeurope"))
        assertFalse(rendered.contains("azure-secret"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun failedMigrationKeepsLegacyPlaintextForRetry() {
        val legacy = SpeechServiceConfig("northeurope", "azure-secret")
        var legacyDeleted = false

        assertFailsWith<IllegalStateException> {
            migrateLegacySpeechConfig(
                legacy = legacy,
                writeSecure = { /* simulate a backend claiming success without persisting */ },
                readSecure = { null },
                deleteLegacy = { legacyDeleted = true },
            )
        }

        assertFalse(legacyDeleted)
    }

    @Test
    fun verifiedMigrationDeletesLegacyValue() {
        val legacy = SpeechServiceConfig("northeurope", "azure-secret")
        var secure: SpeechServiceConfig? = null
        var legacyDeleted = false

        assertEquals(
            legacy,
            migrateLegacySpeechConfig(
                legacy = legacy,
                writeSecure = { secure = it },
                readSecure = { secure },
                deleteLegacy = { legacyDeleted = true },
            )
        )
        assertTrue(legacyDeleted)
    }
}
