package io.github.jdreioe.wingmate

import android.app.Application
import com.hojmoseit.wingmate.BuildConfig
import io.github.jdreioe.wingmate.application.RemoteScreenActions
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import org.koin.core.context.GlobalContext

class WingmateApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (GlobalContext.getOrNull() == null) {
            initKoin(
                androidModule(
                    context = this,
                    aptabaseAppKey = BuildConfig.APTABASE_APP_KEY,
                    microsoftClientId = BuildConfig.MICROSOFT_CLIENT_ID,
                    googleDriveEnabled = BuildConfig.GOOGLE_DRIVE_ANDROID_ENABLED,
                )
            )
        }

        // Remote Screens sync on start and then every 45 s while the app process lives.
        runCatching { GlobalContext.get().get<RemoteScreenActions>().startAutoSync() }

        val openSymbolsProxyUrl = sequenceOf(
            BuildConfig.OPENSYMBOLS_PROXY_URL,
            System.getenv("WINGMATE_OPENSYMBOLS_PROXY_URL"),
            System.getenv("OPENSYMBOLS_PROXY_URL"),
        ).firstOrNull { !it.isNullOrBlank() }

        OpenSymbolsClient.setProxyBaseUrl(openSymbolsProxyUrl)
    }
}
