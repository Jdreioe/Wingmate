package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AndroidFirebaseFeatureUsageReporter(
    private val context: Context,
    private val settingsStateManager: SettingsStateManager
) : FeatureUsageReporter {

    private val analytics by lazy { FirebaseAnalytics.getInstance(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyticsEnabled = AtomicBoolean(false)

    init {
        // Keep Firebase collection state synced with settings changes.
        scope.launch {
            settingsStateManager.settings.collect { settings ->
                applyEnabledState(settings.featureUsageReportingEnabled)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        applyEnabledState(enabled)
    }

    override fun report(event: String, metadata: Map<String, String>) {
        if (!analyticsEnabled.get()) return

        val eventName = sanitizeName(event, fallback = "app_event")
        val bundle = Bundle()
        metadata.forEach { (key, value) ->
            val paramKey = sanitizeName(key, fallback = "param")
            bundle.putString(paramKey, value.take(100))
        }

        runCatching {
            analytics.logEvent(eventName, if (bundle.isEmpty) null else bundle)
        }
    }

    private fun applyEnabledState(enabled: Boolean) {
        val previous = analyticsEnabled.getAndSet(enabled)
        if (previous == enabled) return
        runCatching {
            analytics.setAnalyticsCollectionEnabled(enabled)
        }
    }

    private fun sanitizeName(raw: String, fallback: String): String {
        val cleaned = raw
            .trim()
            .lowercase()
            .replace(NAME_SANITIZE_REGEX, "_")
            .trim('_')

        val withPrefix = if (cleaned.firstOrNull()?.isLetter() == true) {
            cleaned
        } else {
            "e_$cleaned"
        }

        val normalized = withPrefix.take(MAX_NAME_LENGTH).trim('_')
        return if (normalized.isBlank()) fallback else normalized
    }

    private companion object {
        const val MAX_NAME_LENGTH = 40
        val NAME_SANITIZE_REGEX = Regex("[^a-z0-9_]")
    }
}
