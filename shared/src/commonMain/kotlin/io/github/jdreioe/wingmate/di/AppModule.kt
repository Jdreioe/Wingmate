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
import org.koin.dsl.module

val appModule = module {
    single<StoreFactory> { DefaultStoreFactory() }
    
    single { io.github.jdreioe.wingmate.infrastructure.ObfParser() }
    single<io.github.jdreioe.wingmate.domain.BoardRepository> { io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository() }

    factory { AddPhraseUseCase(get()) }
    factory { GetPhrasesAndCategoriesUseCase(get()) }
    factory { DeletePhraseUseCase(get()) }
    factory { UpdatePhraseUseCase(get()) }
    factory { MovePhraseUseCase(get()) }
    factory { GetAllItemsUseCase(get()) }
    
    single { io.github.jdreioe.wingmate.infrastructure.BoardImportService(get(), get(), get(), get()) }

    factory {
        PhraseListStoreFactory(
            storeFactory = get(),
            getPhrasesAndCategoriesUseCase = get(),
            addPhraseUseCase = get(),
            deletePhraseUseCase = get(),
            // legacy addCategory/deleteCategory removed after model unification
            updatePhraseUseCase = get(),
            movePhraseUseCase = get(),
            getAllItemsUseCase = get()
        ).create()
    }
}
