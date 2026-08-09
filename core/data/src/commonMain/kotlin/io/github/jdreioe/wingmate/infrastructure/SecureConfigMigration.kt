package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechServiceConfig

/** Deletes plaintext only after the secure store returns the exact value written. */
internal fun migrateLegacySpeechConfig(
    legacy: SpeechServiceConfig?,
    writeSecure: (SpeechServiceConfig) -> Unit,
    readSecure: () -> SpeechServiceConfig?,
    deleteLegacy: () -> Unit,
): SpeechServiceConfig? {
    legacy ?: return null
    writeSecure(legacy)
    check(readSecure() == legacy) { "Secure Azure credential migration could not be verified" }
    deleteLegacy()
    return legacy
}
