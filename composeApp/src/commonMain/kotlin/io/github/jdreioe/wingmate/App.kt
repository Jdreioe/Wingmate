package io.github.jdreioe.wingmate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.ui.WelcomeScreen
import io.github.jdreioe.wingmate.ui.PhraseScreen
import io.github.jdreioe.wingmate.ui.BoardSetManagerScreen
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.StartupMode
import org.koin.compose.koinInject
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
    val settingsRepository = koinInject<SettingsRepository>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // Check if welcome flow is completed
            var welcomeCompleted by remember { mutableStateOf<Boolean?>(null) }
            var currentScreen by remember { mutableStateOf<String?>(null) }
            var createBoardSetOnLaunch by remember { mutableStateOf(false) }
            var startupBoardSetId by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            fun routeFor(mode: StartupMode): String = when (mode) {
                StartupMode.Keyboard -> "phrase"
                StartupMode.Screens -> "boardsets"
            }

            fun completeWelcomeAndNavigate(mode: StartupMode, createScreen: Boolean) {
                scope.launch(Dispatchers.Default) {
                    val currentSettings = runCatching { settingsRepository.get() }.getOrNull() ?: Settings()
                    settingsRepository.update(
                        currentSettings.copy(
                            welcomeFlowCompleted = true,
                            startupMode = mode
                        )
                    )
                }
                createBoardSetOnLaunch = mode == StartupMode.Screens && createScreen
                startupBoardSetId = null
                val target = routeFor(mode)
                currentScreen = target
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.WELCOME_COMPLETED,
                    "target" to target,
                    "startup_mode" to mode.name.lowercase()
                )
            }
            
            LaunchedEffect(Unit) {
                val settings = withContext(Dispatchers.Default) {
                    runCatching { settingsRepository.get() }.getOrNull()
                }
                welcomeCompleted = settings?.welcomeFlowCompleted ?: false
                startupBoardSetId = settings?.startupBoardSetId
                currentScreen = if (welcomeCompleted == true) {
                    routeFor(settings?.startupMode ?: StartupMode.Keyboard)
                } else {
                    "welcome"
                }
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.APP_STARTED,
                    "welcome_completed" to (welcomeCompleted == true).toString()
                )
            }

            LaunchedEffect(currentScreen) {
                val screen = currentScreen ?: return@LaunchedEffect
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.SCREEN_VIEW,
                    "screen" to screen
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    "welcome" -> {
                        WelcomeScreen(
                            onComplete = ::completeWelcomeAndNavigate
                        )
                    }
                    "phrase" -> {
                        PhraseScreen(
                            onBackToWelcome = { currentScreen = "welcome" },
                            onOpenBoardSetManager = {
                                createBoardSetOnLaunch = false
                                startupBoardSetId = null
                                currentScreen = "boardsets"
                            }
                        )
                    }
                    "boardsets" -> {
                        BoardSetManagerScreen(
                            onBack = {
                                createBoardSetOnLaunch = false
                                currentScreen = "phrase"
                            },
                            createOnLaunch = createBoardSetOnLaunch,
                            initialBoardSetId = startupBoardSetId
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
