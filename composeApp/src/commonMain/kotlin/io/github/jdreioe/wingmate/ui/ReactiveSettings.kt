package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.application.SettingsStateManager
import org.koin.compose.koinInject

/**
 * Composable function that provides reactive access to app settings.
 * Returns settings that automatically update when changed, enabling
 * hot reload of UI settings without app restart.
 */
@Composable
fun rememberReactiveSettings(): State<Settings> {
    val settingsStateManager = koinInject<SettingsStateManager>()
    return settingsStateManager.settings.collectAsStateWithLifecycle()
}