package io.github.jdreioe.wingmate

import android.app.Application
import org.koin.core.context.GlobalContext
import org.koin.dsl.module

class WingmateApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (GlobalContext.getOrNull() == null) {
            initKoin(module { })
        }

        // Register Android-specific implementations once at app startup.
        overrideAndroidSpeechService(this)
    }
}
