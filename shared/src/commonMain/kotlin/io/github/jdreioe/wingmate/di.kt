package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.SettingsBloc
import io.github.jdreioe.wingmate.application.VoiceBloc
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.CategoryUseCase
import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.infrastructure.*
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

fun initKoin(extra: Module? = null) {
    val baseModule: Module = module {
        single<PhraseRepository> { InMemoryPhraseRepository() }
        single<SettingsRepository> { InMemorySettingsRepository() }
        single<VoiceRepository> { InMemoryVoiceRepository() }
        single<SaidTextRepository> { InMemorySaidTextRepository() }
        single<ConfigRepository> { InMemoryConfigRepository() }
        single<SpeechService> { NoopSpeechService() } // Android overrides this
        single { AzureVoiceCatalog(get<ConfigRepository>()) }
        single { PhraseUseCase(get<PhraseRepository>()) }
    single { CategoryUseCase(get<io.github.jdreioe.wingmate.domain.CategoryRepository>()) }
        single { SettingsUseCase(get<SettingsRepository>()) }
        single { VoiceUseCase(get<VoiceRepository>(), get<AzureVoiceCatalog>(), get<ConfigRepository>()) }
        factory { PhraseBloc(get<PhraseUseCase>()) }
        factory { SettingsBloc(get<SettingsUseCase>()) }
        factory { VoiceBloc(get<VoiceUseCase>()) }
    }

    startKoin {
        val modulesList = listOf(baseModule) + listOfNotNull(extra)
        modules(modulesList)
    }
}
