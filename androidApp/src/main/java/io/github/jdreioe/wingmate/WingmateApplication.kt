package io.github.jdreioe.wingmate

import android.app.Application
import com.hojmoseit.wingmate.BuildConfig
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import org.koin.core.context.GlobalContext

class WingmateApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (GlobalContext.getOrNull() == null) {
            initKoin(androidModule(this, BuildConfig.APTABASE_APP_KEY))
        }

        val openSymbolsProxyUrl = sequenceOf(
            BuildConfig.OPENSYMBOLS_PROXY_URL,
            System.getenv("WINGMATE_OPENSYMBOLS_PROXY_URL"),
            System.getenv("OPENSYMBOLS_PROXY_URL"),
        ).firstOrNull { !it.isNullOrBlank() }

        OpenSymbolsClient.setProxyBaseUrl(openSymbolsProxyUrl)
    }
}
