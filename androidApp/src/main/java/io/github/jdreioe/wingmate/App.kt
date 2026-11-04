package io.github.jdreioe.wingmate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jdreioe.wingmate.ui.CommunicationSessionStatus
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.ui.WelcomeChoices
import io.github.jdreioe.wingmate.ui.WelcomeScreen
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.ui.PhraseScreen
import io.github.jdreioe.wingmate.ui.BoardSetManagerRoot
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.ui.InteractionInputRoot
import io.github.jdreioe.wingmate.ui.rememberReactiveSettings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.SettingsStateManager
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

enum class Screen { Welcome, Phrases, BoardSets }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val settingsRepository = koinInject<SettingsRepository>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val phraseUseCase = koinInject<PhraseUseCase>()
    val backupManager = koinInject<CompleteBackupManager>()
    val settingsStateManager = koinInject<SettingsStateManager>()
    val communicationSession = koinInject<CommunicationSession>()
    val communicationState by communicationSession.state.collectAsStateWithLifecycle()
    val restoreRevision by backupManager.restoreRevision.collectAsState()
    val reactiveSettings by rememberReactiveSettings()

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // True while setup was opened from a workspace; only then may it be left unfinished.
            var welcomeReopened by rememberSaveable { mutableStateOf(false) }
            // The setup Phrase already added in this session, so retrying Review cannot add it twice.
            var savedSetupPhrase by rememberSaveable { mutableStateOf<String?>(null) }
            var currentScreen by remember { mutableStateOf<Screen?>(null) }
            var startupLoadState by remember { mutableStateOf<StartupLoadState>(StartupLoadState.Loading) }
            var startupRetryKey by remember { mutableIntStateOf(0) }
            var startupBoardSetId by remember { mutableStateOf<String?>(null) }

            fun routeFor(mode: StartupMode): Screen = when (mode) {
                StartupMode.Keyboard -> Screen.Phrases
                StartupMode.Screens -> Screen.BoardSets
            }

            /**
             * Saves setup choices and opens the chosen workspace. Returns false, leaving the
             * Communicator on Review with their choices intact, if anything could not be saved.
             * The Phrase is saved first so setup only counts as finished once it exists; a retry
             * skips a Phrase already added and repeats the idempotent settings write.
             */
            suspend fun completeWelcomeAndNavigate(choices: WelcomeChoices): Boolean {
                val mode = choices.startupMode
                choices.phraseToSave?.takeIf { it != savedSetupPhrase }?.let { text ->
                    try {
                        withContext(Dispatchers.Default) {
                            phraseUseCase.add(Phrase(id = "", text = text, createdAt = 0))
                        }
                        savedSetupPhrase = text
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        OperationalLogger.warn("welcome.complete", "phrase_save_failed", exceptionClass = failure.loggingClassName())
                        return false
                    }
                }
                val savedSettings = try {
                    withContext(Dispatchers.Default) {
                        // Read fresh: the voice pages saved engine and language during setup.
                        settingsRepository.update(
                            settingsRepository.get().copy(
                                welcomeFlowCompleted = true,
                                startupMode = mode,
                                historyVisible = choices.historyVisible,
                                featureUsageReportingEnabled = choices.featureUsageReportingEnabled,
                            )
                        )
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    OperationalLogger.warn("welcome.complete", "settings_save_failed", exceptionClass = failure.loggingClassName())
                    return false
                }
                settingsStateManager.applyLoadedSettings(savedSettings)
                savedSetupPhrase = null
                welcomeReopened = false
                startupBoardSetId = null
                featureUsageReporter.setEnabled(choices.featureUsageReportingEnabled)
                if (choices.featureUsageReportingEnabled) {
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
                return true
            }

            fun showWelcomeFlow() {
                startupBoardSetId = null
                welcomeReopened = true
                currentScreen = Screen.Welcome
                featureUsageReporter.reportEvent(FeatureUsageEvents.WELCOME_REOPENED)
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
                    welcomeReopened = false
                    startupBoardSetId = settings.startupBoardSetId
                    currentScreen = if (needsWelcome(settings, hasSelectedVoice)) Screen.Welcome else routeFor(settings.startupMode)
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.APP_STARTED,
                        "welcome_completed" to settings.welcomeFlowCompleted.toString(),
                        "voice_selected" to hasSelectedVoice.toString()
                    )
                }
            }

            LaunchedEffect(restoreRevision) {
                if (restoreRevision == 0L) return@LaunchedEffect
                startupLoadState = StartupLoadState.Loading
                communicationSession.reloadAfterRestore()
                val loaded = withContext(Dispatchers.Default) {
                    loadStartupState(settingsRepository::get, voiceUseCase::selected)
                }
                startupLoadState = loaded
                if (loaded is StartupLoadState.Ready) {
                    settingsStateManager.applyLoadedSettings(loaded.settings)
                    startupBoardSetId = loaded.settings.startupBoardSetId
                    welcomeReopened = false
                    featureUsageReporter.setEnabled(loaded.settings.featureUsageReportingEnabled)
                    currentScreen = if (needsWelcome(loaded.settings, loaded.selectedVoice != null)) {
                        Screen.Welcome
                    } else {
                        routeFor(loaded.settings.startupMode)
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

            BackHandler(enabled = currentScreen == Screen.BoardSets) {
                startupBoardSetId = null
                currentScreen = Screen.Phrases
            }

            // Draw the Surface behind the system UI, while keeping all routed
            // screen content and controls clear of bars, gestures, and cutouts.
            InteractionInputRoot(
                settings = reactiveSettings,
                enabled = currentScreen == Screen.Phrases || currentScreen == Screen.BoardSets,
            ) {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    CommunicationSessionStatus(communicationState, communicationSession::accept)
                    Box(Modifier.weight(1f)) {
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
                                startupLoadState is StartupLoadState.Loading || !communicationState.isInitialized -> {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                                        )
                                    }
                                }
                                else -> when (currentScreen) {
                                Screen.Welcome -> {
                                    WelcomeScreen(
                                        initialChoices = remember { WelcomeChoices.fromSettings(settingsStateManager.getCurrentSettings()) },
                                        onComplete = ::completeWelcomeAndNavigate,
                                        onExit = if (welcomeReopened) {
                                            {
                                                welcomeReopened = false
                                                currentScreen = routeFor(settingsStateManager.getCurrentSettings().startupMode)
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                                Screen.Phrases -> {
                                    PhraseScreen(
                                        onBackToWelcome = ::showWelcomeFlow,
                                        onOpenBoardSetManager = {
                                            startupBoardSetId = null
                                            currentScreen = Screen.BoardSets
                                        },
                                    )
                                }
                                Screen.BoardSets -> {
                                    BoardSetManagerRoot(
                                        onBackToWelcome = ::showWelcomeFlow,
                                        onBack = {
                                            currentScreen = Screen.Phrases
                                        },
                                        initialBoardSetId = startupBoardSetId,
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
}
