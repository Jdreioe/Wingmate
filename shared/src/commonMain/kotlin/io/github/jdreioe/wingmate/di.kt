package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.CategoryUseCase
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.UserDataManager
import io.github.jdreioe.wingmate.application.DefaultEditingAccessStore
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.EditingAccessStore
import io.github.jdreioe.wingmate.application.InMemorySecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.BackupMediaAccess
import io.github.jdreioe.wingmate.application.BackupFacade
import io.github.jdreioe.wingmate.application.BackupManager
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.application.SettingsFacade
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.application.QueuedCommunicationSession
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.UnavailableBackupMediaAccess
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.infrastructure.AzureVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.GoogleVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.GoogleApiRequestHeaders
import io.github.jdreioe.wingmate.infrastructure.NoGoogleApiRequestHeaders
import io.github.jdreioe.wingmate.infrastructure.DictionaryLoader
import io.github.jdreioe.wingmate.infrastructure.InMemoryCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryConfigRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryCommunicationSessionDataSource
import io.github.jdreioe.wingmate.infrastructure.InMemoryPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryPronunciationDictionaryRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.NoopSpeechService
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named

import io.github.jdreioe.wingmate.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Suppress("unused")
fun initKoin(extra: Module? = null) {
    val coreDataModule = createCoreDataModule()

    startKoin {
        allowOverride(true)
        // Include base bindings, MVIKotlin store module, and any extra platform-specific modules
        val modulesList = listOf(coreDataModule, appModule) + listOfNotNull(extra)
        modules(modulesList)
    }
}

internal fun createCoreDataModule(): Module = module {
        singleOf(::InMemoryPhraseRepository) { bind<PhraseRepository>() }
        singleOf(::InMemoryCategoryRepository) { bind<CategoryRepository>() }
        singleOf(::InMemorySettingsRepository) { bind<SettingsRepository>() }
        singleOf(::InMemoryVoiceRepository) { bind<VoiceRepository>() }
        singleOf(::InMemorySaidTextRepository) { bind<SaidTextRepository>() }
        singleOf(::InMemoryConfigRepository) { bind<ConfigRepository>() }
        singleOf(::InMemoryCommunicationSessionDataSource) { bind<CommunicationSessionDataSource>() }
        singleOf(::InMemoryPronunciationDictionaryRepository) { bind<PronunciationDictionaryRepository>() }
        singleOf(::NoopSpeechService) { bind<SpeechService>() } // Overridden per platform (Android, iOS)
        singleOf(::NoopFeatureUsageReporter) { bind<FeatureUsageReporter>() }
        singleOf(::AzureVoiceCatalog)
        single<GoogleApiRequestHeaders> { NoGoogleApiRequestHeaders }
        singleOf(::GoogleVoiceCatalog)
        single { DictionaryLoader(getOrNull<io.github.jdreioe.wingmate.domain.FileStorage>()) } // For language dictionary pretraining and caching
        singleOf(::PhraseUseCase)
        singleOf(::CategoryUseCase)
        single { SettingsUseCase(get(), getOrNull()) }
        singleOf(::UserDataManager)
        singleOf(::InMemorySecureEditingCredentialStorage) { bind<SecureEditingCredentialStorage>() }
        // Use explicit constructors here: Koin's constructor-reference DSL attempts
        // to inject Kotlin parameters that have default values (iterations/timeout).
        single<EditingAccessStore> { DefaultEditingAccessStore(get()) }
        single { EditingAccessController(get()) }
        singleOf(::UnavailableBackupMediaAccess) { bind<BackupMediaAccess>() }
        single {
            CompleteBackupManager(
                boardRepository = get(),
                boardSetRepository = get(),
                phraseRepository = get(),
                categoryRepository = get(),
                settingsRepository = get(),
                voiceRepository = get(),
                saidTextRepository = get(),
                communicationSessionDataSource = get(),
                dictionaryRepository = get(),
                configRepository = get(),
                filePicker = getOrNull(),
                mediaAccess = get()
            )
        }
        single<BackupManager> { get<CompleteBackupManager>() }
        singleOf(::BackupFacade)
        singleOf(::SpeechFacade)
        singleOf(::SettingsFacade)
        singleOf(::BoardsFacade)
        singleOf(::CommunicationFacade)
        singleOf(::SettingsStateManager)
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
                predictionService = getOrNull(),
            )
        }
        singleOf(::VoiceUseCase)
        factory { PhraseBloc(get<PhraseUseCase>(), get<FeatureUsageReporter>(), get<CategoryUseCase>()) }
}

// Convenience no-arg for Swift where optional bridging might produce a different symbol name
@Suppress("unused")
fun initKoin() = initKoin(null)
