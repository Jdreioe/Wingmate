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
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    singleOf(::DefaultStoreFactory) { bind<StoreFactory>() }
    
    singleOf(::ObfParser)
    singleOf(::InMemoryBoardRepository) { bind<BoardRepository>() }
    singleOf(::InMemoryBoardSetRepository) { bind<BoardSetRepository>() }
    singleOf(::BoardSetUseCase)

    singleOf(::AddPhraseUseCase)
    singleOf(::GetPhrasesAndCategoriesUseCase)
    singleOf(::DeletePhraseUseCase)
    singleOf(::UpdatePhraseUseCase)
    singleOf(::MovePhraseUseCase)
    singleOf(::GetAllItemsUseCase)
    
    singleOf(::BoardImportService)

    factoryOf(::PhraseListStoreFactory)

    factory {
        get<PhraseListStoreFactory>().create()
    }
}
