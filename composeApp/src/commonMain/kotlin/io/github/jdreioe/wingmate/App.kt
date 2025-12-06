package io.github.jdreioe.wingmate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.jdreioe.wingmate.ui.WelcomeScreen
import io.github.jdreioe.wingmate.ui.PhraseScreen
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.domain.SettingsRepository
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

enum class Screen { Welcome, Phrases }

/**
 * Main shared app composable that can be used by both Android and Desktop
 */
@OptIn(ExperimentalMaterial3Api::class )
@Composable
fun App() {
    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // Check if welcome flow is completed
            var welcomeCompleted by remember { mutableStateOf<Boolean?>(null) }
            var currentScreen by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            
            LaunchedEffect(Unit) {
                val settingsRepo = GlobalContext.getOrNull()?.get<SettingsRepository>()
                val settings = settingsRepo?.let { 
                    withContext(Dispatchers.Default) { 
                        runCatching { it.get() }.getOrNull() 
                    }
                }
                welcomeCompleted = settings?.welcomeFlowCompleted ?: false
                currentScreen = if (welcomeCompleted == true) "phrase" else "welcome"
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    "welcome" -> {
                        WelcomeScreen {
                            // Mark welcome flow as completed
                            scope.launch(Dispatchers.Default) {
                                val settingsRepo = GlobalContext.getOrNull()?.get<SettingsRepository>()
                                settingsRepo?.let { repo ->
                                    val currentSettings = runCatching { repo.get() }.getOrNull()
                                    currentSettings?.let { settings ->
                                        repo.update(settings.copy(welcomeFlowCompleted = true))
                                    }
                                }
                            }
                            currentScreen = "phrase"
                        }
                    }
                    "phrase" -> {
                        PhraseScreen(
                            onBackToWelcome = { currentScreen = "welcome" }
                        )
                    }
                    null -> {
                        // Loading state
                        Box(modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}
