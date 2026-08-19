package io.github.jdreioe.wingmate

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.infrastructure.IosCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.IosBoardRepository
import io.github.jdreioe.wingmate.infrastructure.IosBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.IosConfigRepository
import io.github.jdreioe.wingmate.infrastructure.IosFileStorage
import io.github.jdreioe.wingmate.infrastructure.IosPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.IosPronunciationDictionaryRepository
import io.github.jdreioe.wingmate.infrastructure.IosSaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.IosSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.IosSoundPlayer
import io.github.jdreioe.wingmate.infrastructure.IosSpeechService
import io.github.jdreioe.wingmate.infrastructure.IosVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.IosSystemVoiceProvider
import io.github.jdreioe.wingmate.infrastructure.IosSecureEditingCredentialStorage
import io.github.jdreioe.wingmate.infrastructure.MacosAudioClipboard
import io.github.jdreioe.wingmate.infrastructure.MacosShareService
import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.BackupMediaAccess
import io.github.jdreioe.wingmate.application.BackupSharingFacade
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.application.SettingsFacade
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.infrastructure.IosBackupMediaAccess
import io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
import io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider
import io.github.jdreioe.wingmate.platform.AudioClipboard
import io.github.jdreioe.wingmate.platform.ShareService
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.IosFilePicker
import okio.FileSystem
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.context.loadKoinModules
import org.koin.mp.KoinPlatform
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

// Registers the macOS-native implementations (AppKit clipboard/share, Darwin HTTP, Keychain)
// after Koin has been started. Mirrors overrideIosSpeechService on iOS.
fun overrideMacosSpeechService() {
    loadKoinModules(
        module(createdAtStart = false) {
            single<HttpClient> {
                HttpClient(Darwin) {
                    followRedirects = false
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
            }
            single { FileSystem.SYSTEM }
            singleOf(::IosSettingsRepository) { bind<SettingsRepository>() }
            singleOf(::IosConfigRepository) { bind<ConfigRepository>() }
            singleOf(::IosVoiceRepository) { bind<VoiceRepository>() }
            singleOf(::IosSystemVoiceProvider) { bind<SystemVoiceProvider>() }
            singleOf(::IosSaidTextRepository) { bind<SaidTextRepository>() }
            singleOf(::IosBoardRepository) { bind<BoardRepository>() }
            singleOf(::IosBoardSetRepository) { bind<BoardSetRepository>() }
            singleOf(::IosPhraseRepository) { bind<PhraseRepository>() }
            singleOf(::IosCategoryRepository) { bind<CategoryRepository>() }
            singleOf(::IosSpeechService) { bind<SpeechService>() }
            singleOf(::SimpleNGramPredictionService) { bind<TextPredictionService>() }
            singleOf(::MacosShareService) { bind<ShareService>() }
            singleOf(::BackupSharingFacade)
            singleOf(::MacosAudioClipboard) { bind<AudioClipboard>() }
            singleOf(::IosPronunciationDictionaryRepository) { bind<PronunciationDictionaryRepository>() }
            singleOf(::IosFileStorage) { bind<FileStorage>() }
            singleOf(::IosSecureEditingCredentialStorage) { bind<SecureEditingCredentialStorage>() }
            singleOf(::IosBackupMediaAccess) { bind<BackupMediaAccess>() }
            singleOf(::IosFilePicker) { bind<FilePicker>() }
            singleOf(::IosSoundPlayer) { bind<SoundPlayer>() }
        }
    )
}

// Start Koin including the macOS overrides module so platform bindings are present from startup.
fun startKoinWithOverrides() {
    KoinBridge.start()
    overrideMacosSpeechService()
}

// Swift-facing bridge. Kept under the same name as the iOS bridge so the SwiftUI host
// code compiles unchanged for Mac Catalyst.
class IosDiBridge {
    fun applyOverrides() = overrideMacosSpeechService()
    fun start() = startKoinWithOverrides()
    fun startKoinWithOverridesBridge() = startKoinWithOverrides()
    fun backupFacade(): BackupSharingFacade = KoinPlatform.getKoin().get()
    fun speechFacade(): SpeechFacade = KoinPlatform.getKoin().get()
    fun settingsFacade(): SettingsFacade = KoinPlatform.getKoin().get()
    fun boardsFacade(): BoardsFacade = KoinPlatform.getKoin().get()
    fun communicationFacade(): CommunicationFacade = KoinPlatform.getKoin().get()
}