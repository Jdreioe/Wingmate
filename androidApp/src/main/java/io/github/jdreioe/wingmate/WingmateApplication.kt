package io.github.jdreioe.wingmate

import android.app.Application
import com.hojmoseit.wingmate.BuildConfig
import io.github.jdreioe.wingmate.infrastructure.AzureArmClient
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
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
        overrideAndroidSpeechService(this, BuildConfig.APTABASE_APP_KEY)

        // Override Azure services with Android implementations
        loadKoinModules(module {
            single { AzureArmClient(HttpClient(OkHttp)) }
        })

        val openSymbolsProxyUrl = sequenceOf(
            BuildConfig.OPENSYMBOLS_PROXY_URL,
            System.getenv("WINGMATE_OPENSYMBOLS_PROXY_URL"),
            System.getenv("OPENSYMBOLS_PROXY_URL"),
        ).firstOrNull { !it.isNullOrBlank() }

        OpenSymbolsClient.setProxyBaseUrl(openSymbolsProxyUrl)
    }
}
