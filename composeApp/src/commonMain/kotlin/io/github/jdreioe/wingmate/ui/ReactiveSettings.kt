package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.*
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.application.SettingsStateManager
import org.koin.core.context.GlobalContext

/**
 * Composable function that provides reactive access to app settings.
 * Returns settings that automatically update when changed, enabling
 * hot reload of UI settings without app restart.
 */
@Composable
fun rememberReactiveSettings(): State<Settings> {
    val settingsStateManager = remember {
        GlobalContext.getOrNull()?.let { koin ->
            runCatching { koin.get<SettingsStateManager>() }.getOrNull()
        }
    }
    
    return if (settingsStateManager != null) {
        settingsStateManager.settings.collectAsState()
    } else {
        // Fallback to default settings if state manager is not available
        remember { mutableStateOf(Settings()) }
    }
}