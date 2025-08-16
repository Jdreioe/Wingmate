package io.github.jdreioe.wingmate

import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

// Call this from iOS host after the Kotlin framework is initialized to register iOS-specific implementations.
fun overrideIosSpeechService() {
    loadKoinModules(
        module {
            single<io.github.jdreioe.wingmate.domain.ConfigRepository> { io.github.jdreioe.wingmate.infrastructure.IosConfigRepository() }
            single<io.github.jdreioe.wingmate.domain.SpeechService> { io.github.jdreioe.wingmate.infrastructure.IosSpeechService() }
        }
    )
}
