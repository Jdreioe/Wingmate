package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.infrastructure.DesktopAudioPlayer
import io.github.jdreioe.wingmate.infrastructure.DesktopFileStorage
import io.github.jdreioe.wingmate.infrastructure.AzureTtsDataSource
import io.github.jdreioe.wingmate.repository.TtsRepository
import io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository
import io.github.jdreioe.wingmate.db.TtsDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlinx.coroutines.runBlocking
import java.io.File

fun overrideDesktopSpeechService() {
    loadKoinModules(
        module {
             // Core Components for TtsRepository
            single {
                val dbPath = "${System.getProperty("user.home")}/.wingmate/tts.db"
                val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
                try {
                    TtsDatabase.Schema.create(driver)
                } catch (_: Exception) {
                    // Ignore if already exists
                }
                TtsDatabase(driver)
            }
            single { HttpClient(OkHttp) }
            single { AzureTtsDataSource(get()) }
            single<io.github.jdreioe.wingmate.domain.AudioPlayer> { DesktopAudioPlayer() }
            single<io.github.jdreioe.wingmate.domain.FileStorage> { DesktopFileStorage() }

            single<SpeechService> {
                TtsRepository(
                    database = get(),
                    azure = get(),
                    audioPlayer = get(),
                    fileStorage = get(),
                    configRepository = get(),
                    settingsRepository = get(),
                    saidTextRepository = get()
                )
            }
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
            // Audio clipboard for desktop
            single<io.github.jdreioe.wingmate.platform.AudioClipboard> { io.github.jdreioe.wingmate.platform.DesktopAudioClipboard() }
            // Text prediction service using n-grams trained on user's history
            single<TextPredictionService> { SimpleNGramPredictionService() }
        }
    )
    // Optional: log the current virtual mic preference for visibility
    runCatching {
    val repo = org.koin.core.context.GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SettingsRepository>()
    val settings = runBlocking { repo.get() }
        org.slf4j.LoggerFactory.getLogger("DesktopDi").info("Virtual mic enabled: {}", settings.virtualMicEnabled)
    }
}
