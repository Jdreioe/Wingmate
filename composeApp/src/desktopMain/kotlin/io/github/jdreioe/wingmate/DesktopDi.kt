package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.infrastructure.DesktopSpeechService
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

// This file is compiled for the common/main source set. The JVM-specific
// DesktopConfigRepository lives in jvmMain and is registered there, so keep
// the common DI override minimal to avoid referencing JVM-only types here.
fun overrideDesktopSpeechService() {
    loadKoinModules(
        module {
            single<SpeechService> { DesktopSpeechService() }
            // Desktop JSON voice repository persists the selected voice to ~/.config/wingmate/selected_voice.json
            single<io.github.jdreioe.wingmate.domain.VoiceRepository> { io.github.jdreioe.wingmate.infrastructure.DesktopJsonVoiceRepository() }
        }
    )
}
