package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSpeechService
import io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlBoardRepository
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import io.github.jdreioe.wingmate.infrastructure.JvmImageCacher
import io.github.jdreioe.wingmate.infrastructure.PartnerWindowManager
import io.github.jdreioe.wingmate.ui.PartnerWindowAvailability
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlinx.coroutines.runBlocking

fun overrideDesktopSpeechService() {
    loadKoinModules(
        module {
            single<SpeechService> { DesktopSpeechService() }
            // SQLite-backed repositories for durability
            single<ConfigRepository> { DesktopSqlConfigRepository() }
            single<PhraseRepository> { DesktopSqlPhraseRepository() }
            single<CategoryRepository> { DesktopSqlCategoryRepository() }
            // Persist imported OBF/OBZ boards on desktop
            single<BoardRepository> { DesktopSqlBoardRepository() }
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
            // File picker for importing files
            single<io.github.jdreioe.wingmate.platform.FilePicker> { io.github.jdreioe.wingmate.platform.DesktopFilePicker() }
            single<ImageCacher> { JvmImageCacher() }
        }
    )
    // Optional: log the current virtual mic preference for visibility
    runCatching {
    val repo = org.koin.core.context.GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SettingsRepository>()
    val settings = runBlocking { repo.get() }
        org.slf4j.LoggerFactory.getLogger("DesktopDi").info("Virtual mic enabled: {}", settings.virtualMicEnabled)
    }

    // Partner window display manager (TD-I13 via FTDI FT232H)
    runCatching {
        val settingsStateManager = org.koin.core.context.GlobalContext.get().get<io.github.jdreioe.wingmate.application.SettingsStateManager>()
        val manager = PartnerWindowManager.initialize(settingsStateManager)
        manager.start()
        // Wire device detection into common UI bridge
        PartnerWindowAvailability.bind(manager.deviceConnected)
        org.slf4j.LoggerFactory.getLogger("DesktopDi").info("PartnerWindowManager started")
    }.onFailure { t ->
        org.slf4j.LoggerFactory.getLogger("DesktopDi").error("Failed to start PartnerWindowManager", t)
    }
}
