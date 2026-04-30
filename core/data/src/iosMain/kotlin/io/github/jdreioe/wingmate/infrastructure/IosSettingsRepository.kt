package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

class IosSettingsRepository : SettingsRepository {
    private val prefs by lazy { NSUserDefaults.standardUserDefaults() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val key = "ui_settings_v1"

    override suspend fun get(): Settings = withContext(Dispatchers.Default) {
        val text = prefs.stringForKey(key)
        if (text == null) Settings() else runCatching { json.decodeFromString(Settings.serializer(), text) }.getOrElse { Settings() }
    }

    override suspend fun update(settings: Settings): Settings = withContext(Dispatchers.Default) {
        val text = json.encodeToString(Settings.serializer(), settings)
        prefs.setObject(text, forKey = key)
        prefs.synchronize()
        settings
    }
}
