package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.SettingsBloc
import io.github.jdreioe.wingmate.application.VoiceBloc
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.CategoryUseCase
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.domain.AzureF0Provisioner
import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.UserDataManager
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AutoF0FlowUseCase
import io.github.jdreioe.wingmate.infrastructure.AzureArmClient
import io.github.jdreioe.wingmate.infrastructure.AzureVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.DictionaryLoader
import io.github.jdreioe.wingmate.infrastructure.InMemoryAzureF0Provisioner
import io.github.jdreioe.wingmate.infrastructure.InMemoryCategoryRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryConfigRepository
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

import io.github.jdreioe.wingmate.di.appModule
import io.ktor.client.HttpClient

@Suppress("unused")
fun initKoin(extra: Module? = null) {
    val coreDataModule: Module = module {
        singleOf(::InMemoryPhraseRepository) { bind<PhraseRepository>() }
        singleOf(::InMemoryCategoryRepository) { bind<CategoryRepository>() }
        singleOf(::InMemorySettingsRepository) { bind<SettingsRepository>() }
        singleOf(::InMemoryVoiceRepository) { bind<VoiceRepository>() }
        singleOf(::InMemorySaidTextRepository) { bind<SaidTextRepository>() }
        singleOf(::InMemoryConfigRepository) { bind<ConfigRepository>() }
        singleOf(::InMemoryPronunciationDictionaryRepository) { bind<PronunciationDictionaryRepository>() }
        singleOf(::InMemoryAzureF0Provisioner) { bind<AzureF0Provisioner>() }
        single { AzureArmClient(HttpClient()) }
        singleOf(::AutoF0FlowUseCase)
        singleOf(::NoopSpeechService) { bind<SpeechService>() } // Android overrides this
        singleOf(::NoopFeatureUsageReporter) { bind<FeatureUsageReporter>() }
        singleOf(::AzureVoiceCatalog)
        single { DictionaryLoader(getOrNull<io.github.jdreioe.wingmate.domain.FileStorage>()) } // For language dictionary pretraining and caching
        singleOf(::PhraseUseCase)
        singleOf(::CategoryUseCase)
        singleOf(::SettingsUseCase)
        singleOf(::UserDataManager)
        singleOf(::SettingsStateManager)
        singleOf(::VoiceUseCase)
        factory { PhraseBloc(get<PhraseUseCase>(), get<FeatureUsageReporter>(), get<CategoryUseCase>()) }
        factory { SettingsBloc(get<SettingsUseCase>()) }
        factory { VoiceBloc(get<VoiceUseCase>()) }
    }

    startKoin {
        allowOverride(true)
        // Include base bindings, MVIKotlin store module, and any extra platform-specific modules
        val modulesList = listOf(coreDataModule, appModule) + listOfNotNull(extra)
        modules(modulesList)
    }
}

// Convenience no-arg for Swift where optional bridging might produce a different symbol name
@Suppress("unused")
fun initKoin() = initKoin(null)
