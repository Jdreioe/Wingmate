package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import com.aptabase.Aptabase
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Sends only user-consented, anonymous feature events to Aptabase. */
class AndroidAptabaseFeatureUsageReporter(
    context: Context,
    private val settingsStateManager: SettingsStateManager,
    appKey: String
) : FeatureUsageReporter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyticsEnabled = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)

    init {
        if (appKey.isNotBlank()) {
            runCatching {
                Aptabase.instance.initialize(context.applicationContext, appKey.trim())
                configured.set(true)
            }
        }
        scope.launch {
            settingsStateManager.settings.collect { settings ->
                analyticsEnabled.set(settings.featureUsageReportingEnabled)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        analyticsEnabled.set(enabled)
    }

    override fun report(event: String, metadata: Map<String, String>) {
        if (!configured.get() || !analyticsEnabled.get()) return
        runCatching {
            Aptabase.instance.trackEvent(
                sanitizeName(event, fallback = "app_event"),
                metadata.mapKeys { (key, _) -> sanitizeName(key, fallback = "param") }
                    .mapValues { (_, value) -> value.take(100) }
            )
        }
    }

    private fun sanitizeName(raw: String, fallback: String): String {
        val cleaned = raw.trim().lowercase().replace(NAME_SANITIZE_REGEX, "_").trim('_')
        val prefixed = if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "e_$cleaned"
        return prefixed.take(MAX_NAME_LENGTH).trim('_').ifBlank { fallback }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 40
        val NAME_SANITIZE_REGEX = Regex("[^a-z0-9_]")
    }
}
