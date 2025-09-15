package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.infrastructure.DesktopSpeechService
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlinx.coroutines.runBlocking

fun overrideDesktopSpeechService() {
    loadKoinModules(
        module {
            single<SpeechService> { DesktopSpeechService() }
            // override config repository with a JVM-backed implementation that persists to disk
            // Use SQLite-backed repo for durability
            single<ConfigRepository> { DesktopSqlConfigRepository() }
            // Persist UI settings on desktop
            single<io.github.jdreioe.wingmate.domain.SettingsRepository> { io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository() }
            // Persist selected voice on desktop
            single<io.github.jdreioe.wingmate.domain.VoiceRepository> { DesktopSqlVoiceRepository() }
            // Persist said texts on desktop
            single<io.github.jdreioe.wingmate.domain.SaidTextRepository> { io.github.jdreioe.wingmate.infrastructure.DesktopSqlSaidTextRepository() }
            // Share service for desktop (opens file in default handler)
            single<io.github.jdreioe.wingmate.platform.ShareService> { io.github.jdreioe.wingmate.platform.DesktopShareService() }
        }
    )
    // Optional: log the current virtual mic preference for visibility
    runCatching {
    val repo = org.koin.core.context.GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SettingsRepository>()
    val settings = runBlocking { repo.get() }
        org.slf4j.LoggerFactory.getLogger("DesktopDi").info("Virtual mic enabled: {}", settings.virtualMicEnabled)
    }
}
