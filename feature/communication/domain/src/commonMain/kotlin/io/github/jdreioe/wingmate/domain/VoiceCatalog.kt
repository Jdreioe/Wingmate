package io.github.jdreioe.wingmate.domain

/**
 * Domain port for loading voices from a cloud TTS provider catalog.
 *
 * - [list] returns an empty list when unconfigured or on transient failures so pickers can keep cached voices.
 * - [load] propagates provider and transport errors so credential-verification flows can report them.
 */
interface CloudVoiceCatalog {
    suspend fun list(): List<Voice>
    suspend fun load(): List<Voice> = list()
}

/** Domain port for Azure Speech voice catalog loading. */
interface AzureVoiceCatalogPort : CloudVoiceCatalog

/** Domain port for Google Cloud TTS voice catalog loading. */
interface GoogleVoiceCatalogPort : CloudVoiceCatalog

/** Domain port for ElevenLabs voice catalog loading. */
interface ElevenLabsVoiceCatalogPort : CloudVoiceCatalog
