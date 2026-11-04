package io.github.jdreioe.wingmate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
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
import io.github.jdreioe.wingmate.domain.OperationalLogger
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

/** Why setup is shown: decides whether it can be left and what its Start page confirms. */
enum class WelcomeEntry {
    /** First run, or an unfinished setup; it cannot be left. */
    Required,
    /** Opened again from a workspace; it can be left at any time. */
    Reopened,
    /** A restored backup still needs setup; Start confirms the restore. */
    AfterRestore,
}

/** What App shows. Setup carries its entry, so no separate flags can disagree with it. */
sealed interface Screen {
    val analyticsName: String

    data class Welcome(val entry: WelcomeEntry) : Screen {
        override val analyticsName get() = "Welcome"
    }
    data object Phrases : Screen {
        override val analyticsName get() = "Phrases"
    }
    data object BoardSets : Screen {
        override val analyticsName get() = "BoardSets"
    }
}

/** Keeps the shown screen across activity recreation (e.g. a font size change). */
private val screenSaver = Saver<Screen?, String>(
    save = { screen ->
        when (screen) {
            is Screen.Welcome -> "Welcome:${screen.entry.name}"
            Screen.Phrases -> "Phrases"
            Screen.BoardSets -> "BoardSets"
            null -> ""
        }
    },
    restore = { saved ->
        when {
            saved.startsWith("Welcome:") -> Screen.Welcome(WelcomeEntry.valueOf(saved.removePrefix("Welcome:")))
            saved == "Phrases" -> Screen.Phrases
            saved == "BoardSets" -> Screen.BoardSets
            else -> null
        }
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val settingsRepository = koinInject<SettingsRepository>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val voiceUseCase = koinInject<VoiceUseCase>()
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
            // Saveable, so recreation keeps the Communicator where they were instead of
            // re-routing from startup.
            var currentScreen by rememberSaveable(stateSaver = screenSaver) { mutableStateOf<Screen?>(null) }
            // The restore already routed; recreation must not route it again.
            var handledRestoreRevision by rememberSaveable { mutableLongStateOf(0L) }
            var startupLoadState by remember { mutableStateOf<StartupLoadState>(StartupLoadState.Loading) }
            var startupRetryKey by remember { mutableIntStateOf(0) }
            var startupBoardSetId by remember { mutableStateOf<String?>(null) }

            fun routeFor(mode: StartupMode): Screen = when (mode) {
                StartupMode.Keyboard -> Screen.Phrases
                StartupMode.Screens -> Screen.BoardSets
            }

            /**
             * Saves setup choices and opens the chosen workspace. Returns false, leaving the
             * Communicator on Review with their choices intact, if the settings could not be
             * saved. The write is idempotent, so a retry is safe. Setup saves its Phrase first.
             */
            suspend fun completeWelcomeAndNavigate(choices: WelcomeChoices): Boolean {
                val mode = choices.startupMode
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
                    "target" to target.analyticsName,
                    "startup_mode" to mode.name.lowercase()
                )
                return true
            }

            fun showWelcomeFlow() {
                startupBoardSetId = null
                currentScreen = Screen.Welcome(WelcomeEntry.Reopened)
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
                    if (currentScreen == null) {
                        startupBoardSetId = settings.startupBoardSetId
                        currentScreen = if (needsWelcome(settings, hasSelectedVoice)) {
                            Screen.Welcome(WelcomeEntry.Required)
                        } else {
                            routeFor(settings.startupMode)
                        }
                    }
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.APP_STARTED,
                        "welcome_completed" to settings.welcomeFlowCompleted.toString(),
                        "voice_selected" to hasSelectedVoice.toString()
                    )
                }
            }

            LaunchedEffect(restoreRevision) {
                if (restoreRevision == 0L || restoreRevision == handledRestoreRevision) return@LaunchedEffect
                // Handled up front: if loading fails, Retry runs startup, which routes when no
                // screen is set, so the restored data is still routed.
                handledRestoreRevision = restoreRevision
                currentScreen = null
                startupLoadState = StartupLoadState.Loading
                communicationSession.reloadAfterRestore()
                val loaded = withContext(Dispatchers.Default) {
                    loadStartupState(settingsRepository::get, voiceUseCase::selected)
                }
                startupLoadState = loaded
                if (loaded is StartupLoadState.Ready) {
                    settingsStateManager.applyLoadedSettings(loaded.settings)
                    startupBoardSetId = loaded.settings.startupBoardSetId
                    featureUsageReporter.setEnabled(loaded.settings.featureUsageReportingEnabled)
                    currentScreen = if (needsWelcome(loaded.settings, loaded.selectedVoice != null)) {
                        Screen.Welcome(WelcomeEntry.AfterRestore)
                    } else {
                        routeFor(loaded.settings.startupMode)
                    }
                }
            }

            LaunchedEffect(currentScreen) {
                val screen = currentScreen ?: return@LaunchedEffect
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.SCREEN_VIEW,
                    "screen" to screen.analyticsName
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
                                else -> when (val screen = currentScreen) {
                                is Screen.Welcome -> {
                                    WelcomeScreen(
                                        initialChoices = remember { WelcomeChoices.fromSettings(settingsStateManager.getCurrentSettings()) },
                                        onComplete = ::completeWelcomeAndNavigate,
                                        restoredFromBackup = screen.entry == WelcomeEntry.AfterRestore,
                                        onExit = if (screen.entry == WelcomeEntry.Reopened) {
                                            { currentScreen = routeFor(settingsStateManager.getCurrentSettings().startupMode) }
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
