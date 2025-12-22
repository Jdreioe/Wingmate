package io.github.jdreioe.wingmate

import android.content.Context
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.infrastructure.AndroidAudioPlayer
import io.github.jdreioe.wingmate.infrastructure.AndroidFileStorage
import io.github.jdreioe.wingmate.infrastructure.AzureTtsDataSource
import io.github.jdreioe.wingmate.repository.TtsRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidConfigRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSaidTextRepository
import io.github.jdreioe.wingmate.db.TtsDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

fun overrideAndroidSpeechService(context: Context) {
    loadKoinModules(
        module {
            single<Context> { context }

            // Core Components for TtsRepository
            single { TtsDatabase(AndroidSqliteDriver(TtsDatabase.Schema, context, "tts.db")) }
            single { HttpClient(OkHttp) }
            single { AzureTtsDataSource(get()) }
            single<io.github.jdreioe.wingmate.domain.AudioPlayer> { AndroidAudioPlayer(context) }
            single<io.github.jdreioe.wingmate.domain.FileStorage> { AndroidFileStorage(context) }

            // Bind TtsRepository as the SpeechService implementation
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

            single<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider> { io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider() }
            // Prefer SQLite-backed repositories on Android for parity with desktop
            single<io.github.jdreioe.wingmate.domain.ConfigRepository> { AndroidSqlConfigRepository(context) }
            // Audio clipboard support
            single<io.github.jdreioe.wingmate.platform.AudioClipboard> { io.github.jdreioe.wingmate.platform.AndroidAudioClipboard(context) }
            // Share service for Android share sheet
            single<io.github.jdreioe.wingmate.platform.ShareService> { io.github.jdreioe.wingmate.platform.AndroidShareService(context) }
            single<io.github.jdreioe.wingmate.domain.PhraseRepository> { AndroidSqlPhraseRepository(context) }
            single<io.github.jdreioe.wingmate.domain.CategoryRepository> { AndroidSqlCategoryRepository(context) }
            single<io.github.jdreioe.wingmate.domain.VoiceRepository> { AndroidSqlVoiceRepository(context) }
            single<io.github.jdreioe.wingmate.domain.SettingsRepository> { AndroidSqlSettingsRepository(context) }
            single<io.github.jdreioe.wingmate.domain.SaidTextRepository> { AndroidSqlSaidTextRepository(context) }
        }
    )
}
