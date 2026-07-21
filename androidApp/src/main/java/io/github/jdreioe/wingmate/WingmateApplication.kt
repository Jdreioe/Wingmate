package io.github.jdreioe.wingmate

import android.app.Application
import com.hojmoseit.wingmate.BuildConfig
import io.github.jdreioe.wingmate.domain.AzureF0Provisioner
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

class WingmateApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (GlobalContext.getOrNull() == null) {
            initKoin(module { })
        }

        // Register Android-specific implementations once at app startup.
        overrideAndroidSpeechService(this)

        // Override AzureF0Provisioner with Android MSAL-based implementation
        loadKoinModules(module {
            single<AzureF0Provisioner> { AndroidAzureF0Provisioner(this@WingmateApplication) }
        })

        // OpenSymbols is configured in DesktopMain; wire it for Android app startup as well.
        val openSymbolsSecret = sequenceOf(
            BuildConfig.OPENSYMBOLS_SECRET,
            System.getenv("WINGMATE_OPENSYMBOLS_SECRET"),
            System.getenv("OPENSYMBOLS_SECRET"),
            System.getenv("openSymbols")
        ).firstOrNull { !it.isNullOrBlank() }

        OpenSymbolsClient.setSharedSecret(openSymbolsSecret)
    }
}
