package io.github.jdreioe.wingmate

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.infrastructure.IosAudioClipboard
import io.github.jdreioe.wingmate.infrastructure.IosCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.IosConfigRepository
import io.github.jdreioe.wingmate.infrastructure.IosPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.IosPronunciationDictionaryRepository
import io.github.jdreioe.wingmate.infrastructure.IosSaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.IosSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.IosShareService
import io.github.jdreioe.wingmate.infrastructure.IosSpeechService
import io.github.jdreioe.wingmate.infrastructure.IosVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
import io.github.jdreioe.wingmate.platform.AudioClipboard
import io.github.jdreioe.wingmate.platform.ShareService
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

// Call this from iOS host after the Kotlin framework is initialized to register iOS-specific implementations.
fun overrideIosSpeechService() {
    loadKoinModules(
        module(createdAtStart = false) {
            // Ktor client for iOS (Darwin engine)
            single<HttpClient> {
                HttpClient(Darwin) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
            }
            // Persist speech config and selected voice on iOS
            singleOf(::IosSettingsRepository) { bind<SettingsRepository>() }
            singleOf(::IosConfigRepository) { bind<ConfigRepository>() }
            singleOf(::IosVoiceRepository) { bind<VoiceRepository>() }
            singleOf(::IosSaidTextRepository) { bind<SaidTextRepository>() }
            // Persist phrases and categories on iOS as well
            singleOf(::IosPhraseRepository) { bind<PhraseRepository>() }
            singleOf(::IosCategoryRepository) { bind<CategoryRepository>() }
            singleOf(::IosSpeechService) { bind<SpeechService>() }
            // Text prediction service
            singleOf(::SimpleNGramPredictionService) { bind<TextPredictionService>() }
            
            // Share service
            singleOf(::IosShareService) { bind<ShareService>() }
            // Clipboard
            singleOf(::IosAudioClipboard) { bind<AudioClipboard>() }
            
            // Pronunciation dictionary (persisted)
            singleOf(::IosPronunciationDictionaryRepository) { bind<PronunciationDictionaryRepository>() }
        }
    )
}

// Start Koin including the iOS overrides module so platform bindings are present from startup.
fun startKoinWithOverrides() {
    // Ensure the base module + appModule (which registers PhraseListStore) are started
    KoinBridge.start()
    // Then apply iOS-specific overrides (repositories, Http client, speech service)
    overrideIosSpeechService()
}

// Simple Swift-friendly wrapper to apply the overrides
class IosDiBridge {
    fun applyOverrides() = overrideIosSpeechService()
    // Start Koin including iOS overrides (Swift-friendly)
    fun start() = startKoinWithOverrides()
    // Alternative explicit bridge name for Swift binding
    fun startKoinWithOverridesBridge() = startKoinWithOverrides()
}
