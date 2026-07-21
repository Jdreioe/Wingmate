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
import io.github.jdreioe.wingmate.ui.PlatformBackHandler
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.application.VoiceUseCase
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

enum class Screen { Welcome, Phrases, BoardSets }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val settingsRepository = koinInject<SettingsRepository>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val voiceUseCase = koinInject<VoiceUseCase>()

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            var welcomeCompleted by remember { mutableStateOf<Boolean?>(null) }
            var currentScreen by remember { mutableStateOf<Screen?>(null) }
            var createBoardSetOnLaunch by remember { mutableStateOf(false) }
            var startupBoardSetId by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            fun routeFor(mode: StartupMode): Screen = when (mode) {
                StartupMode.Keyboard -> Screen.Phrases
                StartupMode.Screens -> Screen.BoardSets
            }

            fun completeWelcomeAndNavigate(mode: StartupMode, createScreen: Boolean, analyticsEnabled: Boolean) {
                scope.launch(Dispatchers.Default) {
                    val currentSettings = runCatching { settingsRepository.get() }.getOrNull() ?: Settings()
                    settingsRepository.update(
                        currentSettings.copy(
                            welcomeFlowCompleted = true,
                            startupMode = mode,
                            featureUsageReportingEnabled = analyticsEnabled
                        )
                    )
                }
                createBoardSetOnLaunch = mode == StartupMode.Screens && createScreen
                startupBoardSetId = null
                featureUsageReporter.setEnabled(analyticsEnabled)
                if (analyticsEnabled) {
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.ANALYTICS_CONSENT_CHANGED,
                        "enabled" to "true",
                        "source" to "welcome_flow"
                    )
                }
                val target = routeFor(mode)
                currentScreen = target
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.WELCOME_COMPLETED,
                    "target" to target.name,
                    "startup_mode" to mode.name.lowercase()
                )
            }

            fun showWelcomeFlow() {
                createBoardSetOnLaunch = false
                startupBoardSetId = null
                currentScreen = Screen.Welcome
                featureUsageReporter.reportEvent(FeatureUsageEvents.WELCOME_REOPENED)
            }

            LaunchedEffect(Unit) {
                val settings = withContext(Dispatchers.Default) {
                    runCatching { settingsRepository.get() }.getOrNull()
                }
                val hasSelectedVoice = withContext(Dispatchers.Default) {
                    runCatching { voiceUseCase.selected() }.getOrNull() != null
                }
                welcomeCompleted = settings?.welcomeFlowCompleted ?: false
                startupBoardSetId = settings?.startupBoardSetId
                currentScreen = if (hasSelectedVoice) routeFor(settings?.startupMode ?: StartupMode.Keyboard) else Screen.Welcome
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.APP_STARTED,
                    "welcome_completed" to (welcomeCompleted == true).toString(),
                    "voice_selected" to hasSelectedVoice.toString()
                )
            }

            LaunchedEffect(currentScreen) {
                val screen = currentScreen ?: return@LaunchedEffect
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.SCREEN_VIEW,
                    "screen" to screen.name
                )
            }

            PlatformBackHandler(enabled = currentScreen == Screen.BoardSets) {
                createBoardSetOnLaunch = false
                startupBoardSetId = null
                currentScreen = Screen.Phrases
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    Screen.Welcome -> {
                        WelcomeScreen(
                            onComplete = ::completeWelcomeAndNavigate
                        )
                    }
                    Screen.Phrases -> {
                        PhraseScreen(
                            onBackToWelcome = ::showWelcomeFlow,
                            onOpenBoardSetManager = {
                                createBoardSetOnLaunch = false
                                startupBoardSetId = null
                                currentScreen = Screen.BoardSets
                            }
                        )
                    }
                    Screen.BoardSets -> {
                        BoardSetManagerScreen(
                            onBackToWelcome = ::showWelcomeFlow,
                            onBack = {
                                createBoardSetOnLaunch = false
                                currentScreen = Screen.Phrases
                            },
                            createOnLaunch = createBoardSetOnLaunch,
                            initialBoardSetId = startupBoardSetId
                        )
                    }
                    null -> {
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
