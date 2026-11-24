package io.github.jdreioe.wingmate.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import io.github.jdreioe.wingmate.application.BackupFacade
import io.github.jdreioe.wingmate.application.BackupManager
import io.github.jdreioe.wingmate.application.BackupMediaAccess
import io.github.jdreioe.wingmate.application.BackupSharingFacade
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.CategoryUseCase
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.application.CommunicationSessionFacade
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.DefaultEditingAccessStore
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.EditingAccessStore
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.GridsetExporter
import io.github.jdreioe.wingmate.application.InMemorySecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.ObzExporter
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.PredictionFacade
import io.github.jdreioe.wingmate.application.PronunciationFacade
import io.github.jdreioe.wingmate.application.QueuedCommunicationSession
import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.SettingsFacade
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.application.SymbolSearchFacade
import io.github.jdreioe.wingmate.application.TypingScreenFacade
import io.github.jdreioe.wingmate.application.TypingScreenUseCase
import io.github.jdreioe.wingmate.application.UnavailableBackupMediaAccess
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.bloc.PhraseListStoreFactory
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.BoardSpeechCache
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.NoopSoundPlayer
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.UserDataManager
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.infrastructure.AzureVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.DictionaryLoader
import io.github.jdreioe.wingmate.infrastructure.ElevenLabsVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.GoogleApiRequestHeaders
import io.github.jdreioe.wingmate.infrastructure.GoogleVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryCommunicationSessionDataSource
import io.github.jdreioe.wingmate.infrastructure.InMemoryConfigRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryPronunciationDictionaryRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.KtorObfMediaUrlLoader
import io.github.jdreioe.wingmate.infrastructure.LocalTextPredictionService
import io.github.jdreioe.wingmate.infrastructure.NoGoogleApiRequestHeaders
import io.github.jdreioe.wingmate.infrastructure.NoopSpeechService
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.infrastructure.RealAacLogger
import io.github.jdreioe.wingmate.infrastructure.SymbolSearchClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import io.github.jdreioe.wingmate.application.RemoteScreenActions
import io.github.jdreioe.wingmate.application.RemoteScreenSyncUseCase
import io.github.jdreioe.wingmate.domain.obf.RemoteScreenStorage
import io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudShareScreenStorage
import io.github.jdreioe.wingmate.domain.obf.UnsupportedLinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.UnsupportedRemoteScreenCredentialStorage
import io.github.jdreioe.wingmate.infrastructure.CompositeRemoteScreenStorage
import io.ktor.client.plugins.HttpTimeout
import io.github.jdreioe.wingmate.domain.obf.CloudDriveFiles
import io.github.jdreioe.wingmate.domain.obf.CloudDriveSignIn
import io.github.jdreioe.wingmate.domain.obf.UnsupportedCloudDriveSignIn
import io.github.jdreioe.wingmate.infrastructure.CloudDriveTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * In-memory and no-op defaults plus cross-cutting runtime primitives.
 * Platform modules ([androidModule] / `iosModule`) override storage, speech,
 * sharing, file-picker, and reporting bindings at startup.
 */
internal val coreDefaultsModule: Module = module {
    // Application-scoped coroutine scope: outlives any single screen so long-running
    // work (e.g. Screen persistence) is not cancelled when its UI leaves composition.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single {
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    singleOf(::InMemoryPhraseRepository) { bind<PhraseRepository>() }
    singleOf(::InMemorySettingsRepository) { bind<SettingsRepository>() }
    singleOf(::InMemoryVoiceRepository) { bind<VoiceRepository>() }
    singleOf(::InMemorySaidTextRepository) { bind<SaidTextRepository>() }
    singleOf(::InMemoryConfigRepository) { bind<ConfigRepository>() }
    singleOf(::InMemoryCommunicationSessionDataSource) { bind<CommunicationSessionDataSource>() }
    singleOf(::InMemoryPronunciationDictionaryRepository) { bind<PronunciationDictionaryRepository>() }
    singleOf(::InMemoryBoardRepository) { bind<BoardRepository>() }
    singleOf(::InMemoryBoardSetRepository) { bind<BoardSetRepository>() }
    singleOf(::InMemorySecureEditingCredentialStorage) { bind<SecureEditingCredentialStorage>() }
    singleOf(::UnavailableBackupMediaAccess) { bind<BackupMediaAccess>() }
    singleOf(::NoopSpeechService) { bind<SpeechService>() }
    singleOf(::NoopFeatureUsageReporter) { bind<FeatureUsageReporter>() }
    single<SoundPlayer> { NoopSoundPlayer() }
    single<GoogleApiRequestHeaders> { NoGoogleApiRequestHeaders }
    single<AacLogger> { RealAacLogger(get(), getOrNull(named("logDir")), get()) }
}

/** Voice catalogs, cloud speech configuration, and pronunciation dictionary. */
internal val speechModule: Module = module {
    singleOf(::AzureVoiceCatalog)
    singleOf(::GoogleVoiceCatalog)
    singleOf(::ElevenLabsVoiceCatalog)
    singleOf(::VoiceUseCase)
    singleOf(::SpeechFacade)
    singleOf(::PronunciationFacade)
}

/** App settings state, history export/import, and device-level Editing access. */
internal val settingsModule: Module = module {
    singleOf(::SettingsStateManager)
    single { SettingsUseCase(get(), get()) }
    single { UserDataManager(get(), get()) }
    // Explicit constructors: Koin's constructor-reference DSL attempts to inject
    // Kotlin parameters that have default values (iterations/timeout).
    single<EditingAccessStore> { DefaultEditingAccessStore(get()) }
    single { EditingAccessController(get()) }
    singleOf(::SettingsFacade)
}

/** Typing workspace: Phrases, Categories, Typing Screen, predictions, and symbol search. */
internal val typingModule: Module = module {
    single { DictionaryLoader(getOrNull<FileStorage>()) }
    single(named("predictionScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    single<TextPredictionService> {
        LocalTextPredictionService(
            languages = get<SettingsStateManager>().settings.map { it.primaryLanguage },
            historyRepository = get(),
            loadDictionary = get<DictionaryLoader>()::loadDictionary,
            scope = get(named("predictionScope")),
        )
    }
    singleOf(::PhraseUseCase)
    singleOf(::CategoryUseCase)
    singleOf(::AddPhraseUseCase)
    singleOf(::GetPhrasesAndCategoriesUseCase)
    singleOf(::DeletePhraseUseCase)
    singleOf(::UpdatePhraseUseCase)
    singleOf(::DefaultStoreFactory) { bind<StoreFactory>() }
    singleOf(::PhraseListStoreFactory)
    // One store per process: the source of truth iOS observes through CommunicationFacade.
    single { get<PhraseListStoreFactory>().create() }
    singleOf(::TypingScreenUseCase)
    singleOf(::TypingScreenFacade)
    singleOf(::PredictionFacade)
    single { SymbolSearchFacade(SymbolSearchClient::search) }
}

/** Shared active/held Message session, playback queue, and History access. */
internal val communicationModule: Module = module {
    single(named("communicationSessionScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    single<CommunicationSession> {
        val settingsStateManager = get<SettingsStateManager>()
        QueuedCommunicationSession(
            dataSource = get(),
            speechService = get(),
            saidTextRepository = get(),
            currentSettings = settingsStateManager::getCurrentSettings,
            scope = get(named("communicationSessionScope")),
            predictionService = get(),
        )
    }
    singleOf(::CommunicationFacade)
    singleOf(::CommunicationSessionFacade)
}

/** Screens workspace: OBF/OBZ/Gridset parsing & export, Screen editing, and caching. */
internal val screensModule: Module = module {
    singleOf(::ObfParser)
    single<ObfMediaUrlLoader> { KtorObfMediaUrlLoader(getOrNull() ?: HttpClient()) }
    singleOf(::ObzExporter)
    single { GridsetExporter() }
    singleOf(::BoardSetSpeechCacheUseCase) { bind<BoardSpeechCache>() }
    single {
        BoardSetUseCase(
            boardSetRepository = get(),
            boardRepository = get(),
            featureUsageReporter = get(),
            obzExporter = get(),
            fileStorage = getOrNull(),
            speechCache = get(),
            gridsetExporter = get(),
            remoteCredentials = getOrNull(),
            linkedFileStorage = getOrNull(),
            cloudShareStorage = getOrNull(),
        )
    }
    single(named(REMOTE_SCREEN_HTTP)) {
        HttpClient {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
            }
        }
    }
    single<CloudDriveSignIn> { UnsupportedCloudDriveSignIn }
    single {
        CompositeRemoteScreenStorage(
            httpClient = get(named(REMOTE_SCREEN_HTTP)),
            linkedFileStorage = getOrNull() ?: UnsupportedLinkedRemoteFileStorage,
            cloudShareStorage = getOrNull() ?: UnsupportedCloudShareScreenStorage,
            cloudDriveTokens = CloudDriveTokenManager(
                httpClient = get(named(REMOTE_SCREEN_HTTP)),
                credentials = getOrNull() ?: UnsupportedRemoteScreenCredentialStorage,
                signIn = get(),
            ),
        )
    }
    single<RemoteScreenStorage> { get<CompositeRemoteScreenStorage>() }
    single<CloudDriveFiles> { get<CompositeRemoteScreenStorage>().cloudDriveFiles }
    single {
        RemoteScreenSyncUseCase(
            boardSetUseCase = get(),
            boardSetRepository = get(),
            importService = get(),
            storage = get(),
            credentials = getOrNull() ?: UnsupportedRemoteScreenCredentialStorage,
            speechCache = getOrNull(),
            linkedFileStorage = getOrNull() ?: UnsupportedLinkedRemoteFileStorage,
            backgroundScope = get(),
            cloudShareStorage = getOrNull() ?: UnsupportedCloudShareScreenStorage,
            cloudDriveFiles = get(),
        )
    }
    single {
        RemoteScreenActions(
            sync = get(),
            editingAccess = get(),
            linkedFileStorage = getOrNull() ?: UnsupportedLinkedRemoteFileStorage,
            cloudDriveSignIn = get(),
            cloudDriveFiles = get(),
        )
    }
    single {
        BoardImportService(
            obfParser = get(),
            boardRepository = get(),
            boardSetRepository = get(),
            filePicker = get(),
            fileStorage = getOrNull(),
            urlLoader = get(),
        )
    }
    single {
        BoardsFacade(
            boardSetUseCase = get(),
            boardRepository = get(),
            settingsUseCase = get(),
            boardSetSpeechCache = get(),
            shareService = get(),
            mediaUrlLoader = get(),
        )
    }
}

/** Full-installation Backup export, restore, and platform sharing. */
internal val backupModule: Module = module {
    single {
        CompleteBackupManager(
            boardRepository = get(),
            boardSetRepository = get(),
            phraseRepository = get(),
            settingsRepository = get(),
            voiceRepository = get(),
            saidTextRepository = get(),
            communicationSessionDataSource = get(),
            dictionaryRepository = get(),
            configRepository = get(),
            filePicker = getOrNull(),
            mediaAccess = get(),
        )
    }
    single<BackupManager> { get<CompleteBackupManager>() }
    single { BackupFacade(get(), get(), get()) }
    singleOf(::BackupSharingFacade)
}

/** Complete ordered list of shared Koin modules loaded by every platform and wiring test. */
fun sharedModules(): List<Module> = listOf(
    coreDefaultsModule,
    speechModule,
    settingsModule,
    typingModule,
    communicationModule,
    screensModule,
    backupModule,
)

private const val REMOTE_SCREEN_HTTP = "remoteScreenHttp"
