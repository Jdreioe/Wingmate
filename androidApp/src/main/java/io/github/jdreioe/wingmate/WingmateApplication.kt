package io.github.jdreioe.wingmate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.hojmoseit.wingmate.BuildConfig
import io.github.jdreioe.wingmate.domain.AzureF0Provisioner
import io.github.jdreioe.wingmate.infrastructure.AzureArmClient
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.lang.ref.WeakReference

class WingmateApplication : Application() {

    companion object {
        /** Weak reference to the currently resumed Activity, tracked via lifecycle callbacks. */
        @Volatile
        @JvmStatic
        var currentActivity: WeakReference<Activity>? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // Register activity tracking BEFORE any activity starts so we never miss onActivityResumed.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity?.get() === activity) currentActivity = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity?.get() === activity) currentActivity = null
            }
        })

        if (GlobalContext.getOrNull() == null) {
            initKoin(module { })
        }

        // Register Android-specific implementations once at app startup.
        overrideAndroidSpeechService(this, BuildConfig.APTABASE_APP_KEY)

        // Override Azure services with Android implementations
        loadKoinModules(module {
            single<AzureF0Provisioner> { AndroidAzureF0Provisioner(this@WingmateApplication) }
            single { AzureArmClient(HttpClient(OkHttp)) }
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
