package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSpeechService
import io.github.jdreioe.wingmate.infrastructure.DesktopPhraseRecordingService
import io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlConfigRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlBoardRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.DesktopSqlSaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import io.github.jdreioe.wingmate.infrastructure.JvmImageCacher
import io.github.jdreioe.wingmate.infrastructure.PartnerWindowManager
import io.github.jdreioe.wingmate.platform.AudioClipboard
import io.github.jdreioe.wingmate.platform.DesktopAudioClipboard
import io.github.jdreioe.wingmate.platform.DesktopFilePicker
import io.github.jdreioe.wingmate.platform.DesktopShareService
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import io.github.jdreioe.wingmate.ui.PartnerWindowAvailability
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import kotlinx.coroutines.runBlocking

fun overrideDesktopSpeechService() {
    loadKoinModules(
        module {
            singleOf(::DesktopSpeechService) { bind<SpeechService>() }
            singleOf(::DesktopPhraseRecordingService) { bind<PhraseRecordingService>() }
            // SQLite-backed repositories for durability
            singleOf(::DesktopSqlConfigRepository) { bind<ConfigRepository>() }
            singleOf(::DesktopSqlPhraseRepository) { bind<PhraseRepository>() }
            singleOf(::DesktopSqlCategoryRepository) { bind<CategoryRepository>() }
            // Persist imported OBF/OBZ boards on desktop
            singleOf(::DesktopSqlBoardRepository) { bind<BoardRepository>() }
            // Persist boardset metadata on desktop
            singleOf(::DesktopSqlBoardSetRepository) { bind<BoardSetRepository>() }
            // Persist UI settings on desktop
            singleOf(::DesktopSqlSettingsRepository) { bind<SettingsRepository>() }
            // Persist selected voice on desktop
            singleOf(::DesktopSqlVoiceRepository) { bind<VoiceRepository>() }
            // Persist said texts on desktop
            singleOf(::DesktopSqlSaidTextRepository) { bind<SaidTextRepository>() }
            // Share service for desktop (opens file in default handler)
            singleOf(::DesktopShareService) { bind<ShareService>() }
            // Audio clipboard for desktop
            singleOf(::DesktopAudioClipboard) { bind<AudioClipboard>() }
            // Text prediction service using n-grams trained on user's history
            singleOf(::SimpleNGramPredictionService) { bind<TextPredictionService>() }
            // File picker for importing files
            singleOf(::DesktopFilePicker) { bind<FilePicker>() }
            singleOf(::JvmImageCacher) { bind<ImageCacher>() }
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
