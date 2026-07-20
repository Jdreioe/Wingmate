package io.github.jdreioe.wingmate

import android.app.Application
import com.hojmoseit.wingmate.BuildConfig
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.github.jdreioe.wingmate.infrastructure.ChatterboxSpeechService
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

        // OpenSymbols is configured in DesktopMain; wire it for Android app startup as well.
        val openSymbolsSecret = sequenceOf(
            BuildConfig.OPENSYMBOLS_SECRET,
            System.getenv("WINGMATE_OPENSYMBOLS_SECRET"),
            System.getenv("OPENSYMBOLS_SECRET"),
            System.getenv("openSymbols")
        ).firstOrNull { !it.isNullOrBlank() }

        OpenSymbolsClient.setSharedSecret(openSymbolsSecret)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            GlobalContext.getOrNull()?.getOrNull<ChatterboxSpeechService>()?.unload()
        }
    }
}
