package io.github.jdreioe.wingmate

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

// Call this from iOS host after the Kotlin framework is initialized to register iOS-specific implementations.
fun overrideIosSpeechService() {
    loadKoinModules(
        module {
            // Ktor client for iOS (Darwin engine)
            single<HttpClient> {
                HttpClient(Darwin) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
            }
            // Persist speech config and selected voice on iOS
            single<io.github.jdreioe.wingmate.domain.ConfigRepository> { io.github.jdreioe.wingmate.infrastructure.IosConfigRepository() }
            single<io.github.jdreioe.wingmate.domain.VoiceRepository> { io.github.jdreioe.wingmate.infrastructure.IosVoiceRepository() }
            // Persist phrases and categories on iOS as well
            single<io.github.jdreioe.wingmate.domain.PhraseRepository> { io.github.jdreioe.wingmate.infrastructure.IosPhraseRepository() }
            single<io.github.jdreioe.wingmate.domain.CategoryRepository> { io.github.jdreioe.wingmate.infrastructure.IosCategoryRepository() }
            single<io.github.jdreioe.wingmate.domain.SpeechService> { 
                io.github.jdreioe.wingmate.infrastructure.IosSpeechService(
                    httpClient = get(),
                    configRepository = get()
                ) 
            }
        }
    )
}

// Simple Swift-friendly wrapper to apply the overrides
class IosDiBridge {
    fun applyOverrides() = overrideIosSpeechService()
}
