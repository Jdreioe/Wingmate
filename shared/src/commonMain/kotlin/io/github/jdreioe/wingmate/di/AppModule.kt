package io.github.jdreioe.wingmate.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.application.bloc.PhraseListStoreFactory
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.MovePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetAllItemsUseCase
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSpeechCache
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.ObzExporter
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.infrastructure.RealAacLogger
import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.NoopSoundPlayer
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.infrastructure.KtorObfMediaUrlLoader
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    singleOf(::DefaultStoreFactory) { bind<StoreFactory>() }

    // Application-scoped coroutine scope: outlives any single screen so long-running
    // work (e.g. board-set persistence) is not cancelled when its UI leaves composition.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    
    single {
        kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
    
    singleOf(::ObfParser)
    single<ObfMediaUrlLoader> { KtorObfMediaUrlLoader(getOrNull() ?: HttpClient()) }
    singleOf(::InMemoryBoardRepository) { bind<BoardRepository>() }
    singleOf(::InMemoryBoardSetRepository) { bind<BoardSetRepository>() }
    single { ObzExporter(getOrNull() ?: kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }) }
    singleOf(::BoardSetSpeechCacheUseCase) { bind<BoardSpeechCache>() }
    singleOf(::BoardSetUseCase)
    // Platforms override with a real player; default is a no-op.
    single<SoundPlayer> { NoopSoundPlayer() }

    singleOf(::AddPhraseUseCase)
    singleOf(::GetPhrasesAndCategoriesUseCase)
    singleOf(::DeletePhraseUseCase)
    singleOf(::UpdatePhraseUseCase)
    singleOf(::MovePhraseUseCase)
    singleOf(::GetAllItemsUseCase)
    
    single {
        BoardImportService(
            obfParser = get(),
            boardRepository = get(),
            boardSetRepository = get(),
            filePicker = get(),
            fileStorage = getOrNull(),
            urlLoader = get()
        )
    }
    
    single<AacLogger> { RealAacLogger(get(), getOrNull(named("logDir")), get()) }

    factoryOf(::PhraseListStoreFactory)

    factory {
        get<PhraseListStoreFactory>().create()
    }
}
