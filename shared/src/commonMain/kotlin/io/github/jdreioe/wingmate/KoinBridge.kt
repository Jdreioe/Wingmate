package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.di.appModule
import io.github.jdreioe.wingmate.initKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class KoinBridge : KoinComponent {
    fun phraseListStore(): PhraseListStore = get()

    companion object {
        fun start() {
            // Integrate the DI module using our shared initKoin(extraModule)
            initKoin(appModule)
        }
    }
}

