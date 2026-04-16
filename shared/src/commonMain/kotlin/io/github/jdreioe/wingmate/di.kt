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
import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.infrastructure.*
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf

import io.github.jdreioe.wingmate.di.appModule

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
