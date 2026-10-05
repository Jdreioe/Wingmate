package io.github.jdreioe.wingmate

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.infrastructure.IosBoardRepository
import io.github.jdreioe.wingmate.infrastructure.IosBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.IosCommunicationSessionDataSource
import io.github.jdreioe.wingmate.infrastructure.IosConfigRepository
import io.github.jdreioe.wingmate.infrastructure.GoogleApiRequestHeaders
import io.github.jdreioe.wingmate.infrastructure.IosGoogleApiRequestHeaders
import io.github.jdreioe.wingmate.infrastructure.IosFileStorage
import io.github.jdreioe.wingmate.infrastructure.IosPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.IosPronunciationDictionaryRepository
import io.github.jdreioe.wingmate.infrastructure.IosSaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.IosSettingsRepository
import io.github.jdreioe.wingmate.infrastructure.IosShareService
import io.github.jdreioe.wingmate.infrastructure.IosSpeechService
import io.github.jdreioe.wingmate.infrastructure.IosVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.IosSecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.BackupMediaAccess
import io.github.jdreioe.wingmate.application.BackupSharingFacade
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.application.SettingsFacade
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.application.CommunicationSessionFacade
import io.github.jdreioe.wingmate.application.TypingScreenFacade
import io.github.jdreioe.wingmate.infrastructure.IosBackupMediaAccess
import io.github.jdreioe.wingmate.platform.ShareService
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.IosFilePicker
import okio.FileSystem
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

// Registers every iOS platform binding (persistence, HTTP, speech, sharing, files), overriding the
// in-memory defaults from initKoin.
private fun overrideIosSpeechService(deviceSpeech: IosDeviceSpeech) {
    loadKoinModules(
        module(createdAtStart = false) {
            // Ktor client for iOS (Darwin engine)
            single<HttpClient> {
                HttpClient(Darwin) {
                    followRedirects = false
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
            }
            single<GoogleApiRequestHeaders> { IosGoogleApiRequestHeaders() }
            // OS-backed file system (needed by AacLogger and other common code)
            single { FileSystem.SYSTEM }
            // Persist speech config and selected voice on iOS
            singleOf(::IosSettingsRepository) { bind<SettingsRepository>() }
            singleOf(::IosConfigRepository) { bind<ConfigRepository>() }
            singleOf(::IosVoiceRepository) { bind<VoiceRepository>() }
            singleOf(::IosSaidTextRepository) { bind<SaidTextRepository>() }
            singleOf(::IosBoardRepository) { bind<BoardRepository>() }
            singleOf(::IosBoardSetRepository) { bind<BoardSetRepository>() }
            singleOf(::IosPhraseRepository) { bind<PhraseRepository>() }
            single<CommunicationSessionDataSource> { IosCommunicationSessionDataSource() }
            // Cloud voices play in Kotlin; the device voice is Swift's AVSpeechSynthesizer.
            single<SpeechService> {
                IosSessionSpeechService(
                    cloud = IosSpeechService(
                        httpClient = get(),
                        configRepository = get(),
                        pronunciationDictionaryRepository = getOrNull(),
                        saidRepo = getOrNull(),
                        settingsRepository = getOrNull(),
                        voiceRepository = getOrNull(),
                        googleApiRequestHeaders = get(),
                    ),
                    device = deviceSpeech,
                )
            }
            
            // Share service
            singleOf(::IosShareService) { bind<ShareService>() }
            singleOf(::BackupSharingFacade)
            
            // Pronunciation dictionary (persisted)
            singleOf(::IosPronunciationDictionaryRepository) { bind<PronunciationDictionaryRepository>() }
            singleOf(::IosFileStorage) { bind<FileStorage>() }
            singleOf(::IosSecureEditingCredentialStorage) { bind<SecureEditingCredentialStorage>() }
            singleOf(::IosBackupMediaAccess) { bind<BackupMediaAccess>() }
            singleOf(::IosFilePicker) { bind<FilePicker>() }
            // Usage-log destination for RealAacLogger (app documents dir)
            single(named("logDir")) { IosFileStorage.documentsDirectory() }
        }
    )
}

// Start Koin including the iOS overrides module so platform bindings are present from startup.
private fun startKoinWithOverrides(deviceSpeech: IosDeviceSpeech) {
    // Ensure the base module + appModule (which registers PhraseListStore) are started
    KoinBridge.start()
    // Then apply iOS-specific overrides (repositories, Http client, speech service)
    overrideIosSpeechService(deviceSpeech)
}

// Swift entry point: IosViewModel.start() calls startKoinWithOverridesBridge(), then Swift resolves facades here.
class IosDiBridge {
    fun startKoinWithOverridesBridge(deviceSpeech: IosDeviceSpeech) = startKoinWithOverrides(deviceSpeech)
    fun backupFacade(): BackupSharingFacade = KoinPlatform.getKoin().get()
    fun speechFacade(): SpeechFacade = KoinPlatform.getKoin().get()
    fun settingsFacade(): SettingsFacade = KoinPlatform.getKoin().get()
    fun boardsFacade(): BoardsFacade = KoinPlatform.getKoin().get()
    fun communicationFacade(): CommunicationFacade = KoinPlatform.getKoin().get()
    fun communicationSessionFacade(): CommunicationSessionFacade = KoinPlatform.getKoin().get()
    fun typingScreenFacade(): TypingScreenFacade = KoinPlatform.getKoin().get()
}
