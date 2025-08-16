package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.infrastructure.DesktopSpeechService
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopConfigRepository
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

fun overrideDesktopSpeechService() {
    loadKoinModules(
        module(override = true) {
            single<SpeechService> { DesktopSpeechService() }
            // override config repository with a JVM-backed implementation that persists to disk
            // Use SQLite-backed repo for durability
            single<ConfigRepository> { DesktopSqlConfigRepository() }
            // Persist UI settings on desktop
            single<io.github.jdreioe.wingmate.domain.SettingsRepository> { io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository() }
            // Persist selected voice on desktop
            single<io.github.jdreioe.wingmate.domain.VoiceRepository> { DesktopSqlVoiceRepository() }
            // Persist UI settings on desktop
            single<io.github.jdreioe.wingmate.domain.SettingsRepository> { DesktopSqlSettingsRepository() }
        }
    )
}
