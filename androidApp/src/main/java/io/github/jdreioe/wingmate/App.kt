package io.github.jdreioe.wingmate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.ui.WelcomeScreen
import io.github.jdreioe.wingmate.ui.PhraseScreen
import io.github.jdreioe.wingmate.ui.BoardSetManagerRoot
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.ui.PlatformBackHandler
import io.github.jdreioe.wingmate.ui.InteractionInputRoot
import io.github.jdreioe.wingmate.ui.rememberReactiveSettings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.SettingsStateManager
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

enum class Screen { Welcome, Phrases, BoardSets }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val settingsRepository = koinInject<SettingsRepository>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val backupManager = koinInject<CompleteBackupManager>()
    val settingsStateManager = koinInject<SettingsStateManager>()
    val restoreRevision by backupManager.restoreRevision.collectAsState()
    val reactiveSettings by rememberReactiveSettings()

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            var welcomeCompleted by remember { mutableStateOf<Boolean?>(null) }
            var currentScreen by remember { mutableStateOf<Screen?>(null) }
            var startupLoadState by remember { mutableStateOf<StartupLoadState>(StartupLoadState.Loading) }
            var startupRetryKey by remember { mutableIntStateOf(0) }
            var createBoardSetOnLaunch by remember { mutableStateOf(false) }
            var startupBoardSetId by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            fun routeFor(mode: StartupMode): Screen = when (mode) {
                StartupMode.Keyboard -> Screen.Phrases
                StartupMode.Screens -> Screen.BoardSets
            }

            fun completeWelcomeAndNavigate(mode: StartupMode, createScreen: Boolean, analyticsEnabled: Boolean) {
                scope.launch {
                    val savedSettings = try {
                        withContext(Dispatchers.Default) {
                            settingsRepository.update(
                                settingsStateManager.getCurrentSettings().copy(
                            welcomeFlowCompleted = true,
                            startupMode = mode,
                            featureUsageReportingEnabled = analyticsEnabled
                        )
                            )
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        startupLoadState = StartupLoadState.Failed
                        return@launch
                    }
                    settingsStateManager.applyLoadedSettings(savedSettings)
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
            }

            fun showWelcomeFlow() {
                createBoardSetOnLaunch = false
                startupBoardSetId = null
                currentScreen = Screen.Welcome
                featureUsageReporter.reportEvent(FeatureUsageEvents.WELCOME_REOPENED)
            }

            fun navigateAfterBackupRestore(settings: Settings, hasSelectedVoice: Boolean) {
                createBoardSetOnLaunch = false
                startupBoardSetId = settings.startupBoardSetId
                welcomeCompleted = settings.welcomeFlowCompleted
                featureUsageReporter.setEnabled(settings.featureUsageReportingEnabled)
                if (hasSelectedVoice) {
                    currentScreen = routeFor(settings.startupMode)
                }
            }

            LaunchedEffect(startupRetryKey) {
                startupLoadState = StartupLoadState.Loading
                val loaded = withContext(Dispatchers.Default) {
                    loadStartupState(settingsRepository::get, voiceUseCase::selected)
                }
                startupLoadState = loaded
                if (loaded is StartupLoadState.Ready) {
                    val settings = loaded.settings
                    settingsStateManager.applyLoadedSettings(settings)
                    val hasSelectedVoice = loaded.selectedVoice != null
                    welcomeCompleted = settings.welcomeFlowCompleted
                    startupBoardSetId = settings.startupBoardSetId
                    currentScreen = if (hasSelectedVoice) routeFor(settings.startupMode) else Screen.Welcome
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.APP_STARTED,
                        "welcome_completed" to welcomeCompleted.toString(),
                        "voice_selected" to hasSelectedVoice.toString()
                    )
                }
            }

            LaunchedEffect(restoreRevision) {
                if (restoreRevision == 0L) return@LaunchedEffect
                startupLoadState = StartupLoadState.Loading
                val loaded = withContext(Dispatchers.Default) {
                    loadStartupState(settingsRepository::get, voiceUseCase::selected)
                }
                startupLoadState = loaded
                if (loaded is StartupLoadState.Ready) {
                    settingsStateManager.applyLoadedSettings(loaded.settings)
                    createBoardSetOnLaunch = false
                    startupBoardSetId = loaded.settings.startupBoardSetId
                    welcomeCompleted = loaded.settings.welcomeFlowCompleted
                    featureUsageReporter.setEnabled(loaded.settings.featureUsageReportingEnabled)
                    currentScreen = if (loaded.selectedVoice != null) {
                        routeFor(loaded.settings.startupMode)
                    } else {
                        Screen.Welcome
                    }
                }
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

            // Draw the Surface behind the system UI, while keeping all routed
            // screen content and controls clear of bars, gestures, and cutouts.
            InteractionInputRoot(
                settings = reactiveSettings,
                enabled = currentScreen == Screen.Phrases || currentScreen == Screen.BoardSets,
            ) {
                Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    key(restoreRevision) {
                        when {
                            startupLoadState is StartupLoadState.Failed -> {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(24.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = androidx.compose.ui.res.stringResource(com.hojmoseit.wingmate.R.string.startup_load_failed),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = { startupRetryKey++ }) {
                                        Text(androidx.compose.ui.res.stringResource(com.hojmoseit.wingmate.R.string.common_retry))
                                    }
                                }
                            }
                            startupLoadState is StartupLoadState.Loading -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                                    )
                                }
                            }
                            else -> when (currentScreen) {
                            Screen.Welcome -> {
                                WelcomeScreen(
                                    onComplete = ::completeWelcomeAndNavigate,
                                    onBackupRestored = ::navigateAfterBackupRestore
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
                                BoardSetManagerRoot(
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
        }
    }
}
