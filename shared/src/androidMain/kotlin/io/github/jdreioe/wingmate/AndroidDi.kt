package io.github.jdreioe.wingmate

import android.content.Context
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.infrastructure.AndroidSpeechService
import io.github.jdreioe.wingmate.infrastructure.AndroidConfigRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.AndroidSqlSaidTextRepository
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

fun overrideAndroidSpeechService(context: Context) {
    loadKoinModules(
        module {
            single<Context> { context }
            single<SpeechService> { AndroidSpeechService(context) }
            single<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider> { io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider() }
            // Register Android SharedPreferences-backed ConfigRepository
            // Prefer SQLite-backed repositories on Android for parity with desktop
            single<io.github.jdreioe.wingmate.domain.ConfigRepository> { AndroidSqlConfigRepository(context) }
            single<io.github.jdreioe.wingmate.domain.PhraseRepository> { AndroidSqlPhraseRepository(context) }
            single<io.github.jdreioe.wingmate.domain.CategoryRepository> { AndroidSqlCategoryRepository(context) }
            single<io.github.jdreioe.wingmate.domain.VoiceRepository> { AndroidSqlVoiceRepository(context) }
            single<io.github.jdreioe.wingmate.domain.SettingsRepository> { AndroidSqlSettingsRepository(context) }
            single<io.github.jdreioe.wingmate.domain.SaidTextRepository> { AndroidSqlSaidTextRepository(context) }
        }
    )
}
