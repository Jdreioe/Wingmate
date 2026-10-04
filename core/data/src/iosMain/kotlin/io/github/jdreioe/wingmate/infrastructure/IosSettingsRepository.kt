package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import kotlinx.serialization.json.Json

class IosSettingsRepository : SettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val store = IosPreferencesJsonStore(
        key = "ui_settings_v1",
        encode = { json.encodeToString(Settings.serializer(), it) },
        decode = { json.decodeFromString(Settings.serializer(), it) },
    )

    override suspend fun get(): Settings = store.read(::Settings)

    override suspend fun update(settings: Settings): Settings {
        store.replace(settings, ::Settings)
        return settings
    }
}
