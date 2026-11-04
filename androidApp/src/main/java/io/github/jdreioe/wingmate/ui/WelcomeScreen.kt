package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.platform.FilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

/**
 * The answers collected during setup. Voice and language choices are saved by their own
 * pages as before; everything here is saved only when the Communicator presses
 * "Start communicating". Start from [fromSettings] so re-running setup keeps current choices.
 */
data class WelcomeChoices(
    val startupMode: StartupMode = StartupMode.Keyboard,
    /** A test sentence the Communicator chose to keep as a Phrase, or null. */
    val phraseToSave: String? = null,
    val historyVisible: Boolean = true,
    val featureUsageReportingEnabled: Boolean = false,
) {
    companion object {
        fun fromSettings(settings: Settings) = WelcomeChoices(
            startupMode = settings.startupMode,
            historyVisible = settings.historyVisible,
            featureUsageReportingEnabled = settings.featureUsageReportingEnabled,
        )
    }
}

/** One page of setup. [analyticsName] is stable across R8 renaming and is also the saved-state key. */
private sealed class WelcomeStep(val analyticsName: String) {
    data object Start : WelcomeStep("start")
    data object VoiceEngine : WelcomeStep("voice_engine")
    data object AzureSetup : WelcomeStep("azure_setup")
    data object GoogleSetup : WelcomeStep("google_setup")
    data object ElevenLabsSetup : WelcomeStep("elevenlabs_setup")
    data object Voice : WelcomeStep("voice")
    data object Language : WelcomeStep("language")
    data object TestVoice : WelcomeStep("test_voice")
    data object Workspace : WelcomeStep("workspace")
    data object Privacy : WelcomeStep("privacy")
    data object Review : WelcomeStep("review")
}

private val allWelcomeSteps = listOf(
    WelcomeStep.Start, WelcomeStep.VoiceEngine, WelcomeStep.AzureSetup, WelcomeStep.GoogleSetup,
    WelcomeStep.ElevenLabsSetup, WelcomeStep.Voice, WelcomeStep.Language, WelcomeStep.TestVoice,
    WelcomeStep.Workspace, WelcomeStep.Privacy, WelcomeStep.Review,
)

/** Keeps the back stack across activity recreation. */
private val welcomePathSaver = listSaver<List<WelcomeStep>, String>(
    save = { path -> path.map { it.analyticsName } },
    restore = { names ->
        names.mapNotNull { name -> allWelcomeSteps.firstOrNull { it.analyticsName == name } }
            .ifEmpty { listOf(WelcomeStep.Start) }
    },
)

/** Keeps the draft answers across activity recreation. */
private val welcomeChoicesSaver = listSaver<WelcomeChoices, Any?>(
    save = { listOf(it.startupMode.name, it.phraseToSave, it.historyVisible, it.featureUsageReportingEnabled) },
    restore = { values ->
        WelcomeChoices(
            startupMode = StartupMode.valueOf(values[0] as String),
            phraseToSave = values[1] as String?,
            historyVisible = values[2] as Boolean,
            featureUsageReportingEnabled = values[3] as Boolean,
        )
    },
)

/** Voice settings when setup was reopened, put back if the Communicator leaves without finishing. */
private data class VoiceSnapshot(
    val engine: TtsEngine,
    val primaryLanguage: String,
    val secondaryLanguage: String,
    val voice: Voice?,
)

/**
 * First-run setup: voice first (so the Communicator hears themselves early), then how
 * they communicate, then what stays on the device, then a review with one finish button.
 * Navigation is a back stack, so system Back and on-screen Back always return to the
 * page that was actually shown before. A backup restored from the Start page is routed
 * by App's restore handling, which rebuilds this screen.
 */
@Composable
fun WelcomeScreen(
    initialChoices: WelcomeChoices,
    /** Saves [WelcomeChoices] and opens the workspace; returns false if saving failed. */
    onComplete: suspend (WelcomeChoices) -> Boolean,
    /**
     * Non-null when setup was reopened by someone who already communicates with Wingmate.
     * Leaving puts back the voice, engine, and languages that the voice pages changed.
     */
    onExit: (() -> Unit)?,
) {
    val koin = getKoin()
    val featureUsageReporter = remember(koin) { koin.getOrNull<FeatureUsageReporter>() }
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val voiceUseCase = remember(koin) { koin.getOrNull<VoiceUseCase>() }
    val scope = rememberCoroutineScope()

    var path by rememberSaveable(stateSaver = welcomePathSaver) { mutableStateOf(listOf<WelcomeStep>(WelcomeStep.Start)) }
    var choices by rememberSaveable(stateSaver = welcomeChoicesSaver) { mutableStateOf(initialChoices) }
    var exitFailed by remember { mutableStateOf(false) }
    val step = path.last()

    val snapshot by produceState<VoiceSnapshot?>(null, onExit != null) {
        if (onExit == null) return@produceState
        value = try {
            withContext(Dispatchers.Default) {
                val settings = settingsUseCase?.get() ?: return@withContext null
                VoiceSnapshot(settings.ttsEngine, settings.primaryLanguage, settings.secondaryLanguage, voiceUseCase?.selected())
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OperationalLogger.warn("welcome.exit", "snapshot_failed", exceptionClass = failure.loggingClassName())
            null
        }
    }

    fun push(next: WelcomeStep) { path = path + next }
    fun pop() { if (path.size > 1) path = path.dropLast(1) }
    /** Return to an earlier page from Review; following pages are rebuilt on the way forward. */
    fun revisit(target: WelcomeStep) { path = path.takeWhile { it != target } + target }

    fun startSetup() {
        scope.launch {
            // Saved before moving on, so the voice pages' own settings writes cannot race it.
            // Reopened setup is not marked: leaving it must never make setup mandatory.
            if (onExit == null) {
                try {
                    withContext(Dispatchers.Default) {
                        settingsUseCase?.let { it.update(it.get().copy(welcomeFlowStarted = true)) }
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    // Only resuming after an interruption is lost; setup itself can continue.
                    OperationalLogger.warn("welcome.start", "mark_started_failed", exceptionClass = failure.loggingClassName())
                }
            }
            if (path == listOf(WelcomeStep.Start)) push(WelcomeStep.VoiceEngine)
        }
    }

    fun exit() {
        val leave = onExit ?: return
        scope.launch {
            exitFailed = false
            try {
                snapshot?.let { previous ->
                    withContext(Dispatchers.Default) {
                        settingsUseCase?.let {
                            it.update(
                                it.get().copy(
                                    ttsEngine = previous.engine,
                                    primaryLanguage = previous.primaryLanguage,
                                    secondaryLanguage = previous.secondaryLanguage,
                                )
                            )
                        }
                        previous.voice?.let { voiceUseCase?.select(it) }
                    }
                }
                leave()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                OperationalLogger.warn("welcome.exit", "restore_voice_failed", exceptionClass = failure.loggingClassName())
                exitFailed = true
            }
        }
    }

    LaunchedEffect(Unit) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.WELCOME_STARTED)
    }
    LaunchedEffect(step) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.WELCOME_STEP_VIEWED, "step" to step.analyticsName)
    }
    BackHandler(enabled = path.size > 1 || onExit != null) {
        if (path.size > 1) pop() else exit()
    }

    val pageModifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)

    when (step) {
        WelcomeStep.Start -> StartPage(
            onSetUp = ::startSetup,
            onExit = onExit?.let { ::exit },
            exitFailed = exitFailed,
        )
        WelcomeStep.VoiceEngine -> VoiceEngineSelectorScreen(
            onNext = {
                featureUsageReporter?.reportEvent(FeatureUsageEvents.VOICE_ENGINE_SELECTED, "engine" to "system")
                push(WelcomeStep.Voice)
            },
            onCancel = ::pop,
            onAzureSelected = {
                featureUsageReporter?.reportEvent(FeatureUsageEvents.VOICE_ENGINE_SELECTED, "engine" to "azure")
                push(WelcomeStep.AzureSetup)
            },
            onGoogleSelected = {
                featureUsageReporter?.reportEvent(FeatureUsageEvents.VOICE_ENGINE_SELECTED, "engine" to "google")
                push(WelcomeStep.GoogleSetup)
            },
            onElevenLabsSelected = {
                featureUsageReporter?.reportEvent(FeatureUsageEvents.VOICE_ENGINE_SELECTED, "engine" to "elevenlabs")
                push(WelcomeStep.ElevenLabsSetup)
            },
        )
        WelcomeStep.AzureSetup -> F0SetupScreen(onDone = { push(WelcomeStep.Voice) }, onBack = ::pop)
        WelcomeStep.GoogleSetup -> GoogleTtsSetupScreen(onDone = { push(WelcomeStep.Voice) }, onBack = ::pop)
        WelcomeStep.ElevenLabsSetup -> ElevenLabsSetupScreen(onDone = { push(WelcomeStep.Voice) }, onBack = ::pop)
        WelcomeStep.Voice -> VoiceSelectionPage(
            onBack = ::pop,
            onVoiceSelected = { push(WelcomeStep.Language) },
            modifier = pageModifier,
        )
        WelcomeStep.Language -> LanguageSelectionPage(
            onBack = ::pop,
            onContinue = { push(WelcomeStep.TestVoice) },
            modifier = pageModifier,
        )
        WelcomeStep.TestVoice -> TestVoiceScreen(
            initialPhraseToSave = choices.phraseToSave,
            onNext = { phraseToSave ->
                choices = choices.copy(phraseToSave = phraseToSave)
                push(WelcomeStep.Workspace)
            },
            onBack = ::pop,
        )
        WelcomeStep.Workspace -> WorkspacePage(
            choices = choices,
            onChoicesChange = { choices = it },
            onBack = ::pop,
            onContinue = {
                featureUsageReporter?.reportEvent(
                    FeatureUsageEvents.STARTUP_MODE_SELECTED,
                    "mode" to choices.startupMode.name.lowercase()
                )
                push(WelcomeStep.Privacy)
            },
        )
        WelcomeStep.Privacy -> PrivacyPage(
            choices = choices,
            onChoicesChange = { choices = it },
            onBack = ::pop,
            onContinue = { push(WelcomeStep.Review) },
        )
        WelcomeStep.Review -> ReviewPage(
            choices = choices,
            onChangeVoice = { revisit(WelcomeStep.VoiceEngine) },
            onChangePhrase = { revisit(WelcomeStep.TestVoice) },
            onChangeWorkspace = { revisit(WelcomeStep.Workspace) },
            onChangePrivacy = { revisit(WelcomeStep.Privacy) },
            onBack = ::pop,
            onFinish = { onComplete(choices) },
        )
    }
}

// ─── Shared layout ───────────────────────────────────────────────────────────

/**
 * Common frame for setup pages: a heading, scrollable content, and large Back and
 * primary buttons that stay in the same place on every page.
 */
@Composable
private fun WelcomePage(
    title: String,
    subtitle: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: (() -> Unit)?,
    primaryEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.common_back))
                    }
                }
                Button(
                    onClick = onPrimary,
                    enabled = primaryEnabled,
                    modifier = Modifier.weight(if (onBack != null) 2f else 1f).heightIn(min = 56.dp),
                ) {
                    Text(primaryLabel)
                }
            }
        }
    }
}

/** A large, single-choice card. Selection is shown by border, icon, and semantics, not colour alone. */
@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        border = BorderStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** A labelled switch whose whole row is the touch target. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ─── Pages ───────────────────────────────────────────────────────────────────

@Composable
private fun StartPage(
    onSetUp: () -> Unit,
    onExit: (() -> Unit)?,
    exitFailed: Boolean,
) {
    val koin = getKoin()
    val backupManager = remember(koin) { koin.getOrNull<CompleteBackupManager>() }
    val backupFilePicker = remember(koin) { koin.getOrNull<FilePicker>() }
    var pendingRestorePath by remember { mutableStateOf<String?>(null) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    val backupPickerFailed = stringResource(R.string.backup_picker_failed)
    val backupRestoreUnavailable = stringResource(R.string.backup_restore_unavailable)
    val backupRestoreTitle = stringResource(R.string.backup_restore)
    val scope = rememberCoroutineScope()
    // Leaving the page would cancel a restore in progress; registered after the
    // screen-level handler, so it takes precedence while active.
    BackHandler(enabled = isRestoring) {}

    WelcomePage(
        title = stringResource(R.string.welcome_title),
        subtitle = stringResource(R.string.welcome_subtitle),
        primaryLabel = stringResource(R.string.welcome_set_up),
        // Leaving the page would cancel a restore in progress.
        primaryEnabled = !isRestoring,
        onPrimary = onSetUp,
        onBack = onExit?.takeIf { !isRestoring },
    ) {
        Text(stringResource(R.string.welcome_supporter_note), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            enabled = !isRestoring && backupManager != null && backupFilePicker != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = {
                scope.launch {
                    restoreStatus = null
                    try {
                        pendingRestorePath = backupFilePicker?.pickFile(
                            title = backupRestoreTitle,
                            extensions = listOf("wingmate-backup", "zip")
                        )
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        restoreStatus = backupPickerFailed
                    }
                }
            }
        ) {
            if (isRestoring) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.welcome_restore_backup))
        }
        val error = restoreStatus ?: if (exitFailed) stringResource(R.string.welcome_exit_failed) else null
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    val restorePath = pendingRestorePath
    if (restorePath != null) {
        AlertDialog(
            onDismissRequest = { pendingRestorePath = null },
            title = { Text(stringResource(R.string.backup_replace_title)) },
            text = { Text(stringResource(R.string.backup_replace_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestorePath = null
                    isRestoring = true
                    restoreStatus = null
                    scope.launch {
                        when (val result = backupManager?.restoreBackup(restorePath)) {
                            // App reloads and routes after a restore (restoreRevision).
                            is BackupRestoreResult.Success -> Unit
                            is BackupRestoreResult.Failure -> restoreStatus = result.message
                            null -> restoreStatus = backupRestoreUnavailable
                        }
                        isRestoring = false
                    }
                }) { Text(stringResource(R.string.backup_replace_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestorePath = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun WorkspacePage(
    choices: WelcomeChoices,
    onChoicesChange: (WelcomeChoices) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    WelcomePage(
        title = stringResource(R.string.welcome_start_mode_title),
        subtitle = stringResource(R.string.welcome_start_mode_subtitle),
        primaryLabel = stringResource(R.string.common_continue),
        onPrimary = onContinue,
        onBack = onBack,
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChoiceCard(
                title = stringResource(R.string.welcome_start_mode_keyboard_title),
                description = stringResource(R.string.welcome_start_mode_keyboard_desc),
                icon = Icons.Default.Keyboard,
                selected = choices.startupMode == StartupMode.Keyboard,
                onSelect = { onChoicesChange(choices.copy(startupMode = StartupMode.Keyboard)) },
            )
            ChoiceCard(
                title = stringResource(R.string.welcome_start_mode_screens_title),
                description = stringResource(R.string.welcome_start_mode_screens_desc),
                icon = Icons.Default.GridView,
                selected = choices.startupMode == StartupMode.Screens,
                onSelect = { onChoicesChange(choices.copy(startupMode = StartupMode.Screens)) },
            )
        }
    }
}

/** Where a privacy card's data goes. Shown as text and icon so it never relies on colour. */
private enum class DataLocation(val label: Int, val icon: ImageVector) {
    OnDevice(R.string.welcome_privacy_on_device, Icons.Default.PhoneAndroid),
    /** The installed system speech engine; Wingmate cannot promise it never uses the network. */
    DeviceEngine(R.string.welcome_privacy_device_engine, Icons.Default.PhoneAndroid),
    Online(R.string.welcome_privacy_online, Icons.Default.Cloud),
    OnlineWhenOn(R.string.welcome_privacy_online_when_on, Icons.Default.Cloud),
}

@Composable
private fun PrivacyCard(
    title: String,
    location: DataLocation,
    description: String,
    toggle: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(location.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(location.label), style = MaterialTheme.typography.labelLarge)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            toggle?.invoke()
        }
    }
}

@Composable
private fun PrivacyPage(
    choices: WelcomeChoices,
    onChoicesChange: (WelcomeChoices) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val koin = getKoin()
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    // The voice engine was saved by the voice pages; read it so the speech card names
    // the provider that actually receives text. An unknown engine is never shown as System.
    var retryKey by remember { mutableIntStateOf(0) }
    val engine by produceState<EngineLoad>(EngineLoad.Loading, settingsUseCase, retryKey) {
        value = EngineLoad.Loading
        value = try {
            val loaded = withContext(Dispatchers.Default) { settingsUseCase?.get()?.ttsEngine }
            if (loaded != null) EngineLoad.Ready(loaded) else EngineLoad.Failed
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OperationalLogger.warn("welcome.privacy", "settings_load_failed", exceptionClass = failure.loggingClassName())
            EngineLoad.Failed
        }
    }

    WelcomePage(
        title = stringResource(R.string.welcome_privacy_title),
        subtitle = stringResource(R.string.welcome_privacy_subtitle),
        primaryLabel = stringResource(R.string.common_continue),
        onPrimary = onContinue,
        onBack = onBack,
    ) {
        when (val current = engine) {
            EngineLoad.Loading -> CircularProgressIndicator()
            EngineLoad.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.welcome_privacy_speech_unknown),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { retryKey++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_retry))
                }
            }
            is EngineLoad.Ready -> if (current.engine == TtsEngine.SYSTEM) {
                PrivacyCard(
                    title = stringResource(R.string.welcome_privacy_speech_title),
                    location = DataLocation.DeviceEngine,
                    description = stringResource(R.string.welcome_privacy_speech_system),
                )
            } else {
                PrivacyCard(
                    title = stringResource(R.string.welcome_privacy_speech_title),
                    location = DataLocation.Online,
                    description = stringResource(R.string.welcome_privacy_speech_cloud, current.engine.providerName()),
                )
            }
        }
        PrivacyCard(
            title = stringResource(R.string.category_history),
            location = DataLocation.OnDevice,
            description = stringResource(R.string.welcome_privacy_history_desc),
        ) {
            SwitchRow(
                label = stringResource(R.string.ui_settings_history_visible_title),
                checked = choices.historyVisible,
                onCheckedChange = { onChoicesChange(choices.copy(historyVisible = it)) },
            )
        }
        PrivacyCard(
            title = stringResource(R.string.welcome_analytics_title),
            location = DataLocation.OnlineWhenOn,
            description = stringResource(R.string.welcome_analytics_description),
        ) {
            SwitchRow(
                label = stringResource(R.string.welcome_analytics_opt_in),
                checked = choices.featureUsageReportingEnabled,
                onCheckedChange = { onChoicesChange(choices.copy(featureUsageReportingEnabled = it)) },
            )
        }
    }
}

/** Provider names are brands and stay untranslated. Only called for cloud engines. */
private sealed interface EngineLoad {
    data object Loading : EngineLoad
    data object Failed : EngineLoad
    data class Ready(val engine: TtsEngine) : EngineLoad
}

private fun TtsEngine.providerName(): String = when (this) {
    TtsEngine.SYSTEM -> ""
    TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> "Microsoft Azure"
    TtsEngine.GOOGLE_CLOUD -> "Google Cloud"
    TtsEngine.ELEVENLABS -> "ElevenLabs"
}

@Composable
private fun ReviewRow(label: String, value: String, enabled: Boolean, onChange: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        val changeLabel = stringResource(R.string.welcome_review_change_label, label)
        OutlinedButton(
            onClick = onChange,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = changeLabel },
        ) {
            Text(stringResource(R.string.welcome_review_change))
        }
    }
}

@Composable
private fun ReviewPage(
    choices: WelcomeChoices,
    onChangeVoice: () -> Unit,
    onChangePhrase: () -> Unit,
    onChangeWorkspace: () -> Unit,
    onChangePrivacy: () -> Unit,
    onBack: () -> Unit,
    onFinish: suspend () -> Boolean,
) {
    val koin = getKoin()
    val voiceUseCase = remember(koin) { koin.getOrNull<VoiceUseCase>() }
    val unknownVoice = stringResource(R.string.test_voice_unknown_voice)
    val voiceName by produceState(unknownVoice, voiceUseCase) {
        value = try {
            withContext(Dispatchers.IO) { voiceUseCase?.selected() }
                ?.let { it.displayName ?: it.name }
                ?: unknownVoice
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OperationalLogger.warn("welcome.review", "voice_load_failed", exceptionClass = failure.loggingClassName())
            unknownVoice
        }
    }
    var finishing by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Leaving Review would cancel the save part-way; takes precedence over the screen's handler.
    BackHandler(enabled = finishing) {}

    val workspace = when (choices.startupMode) {
        StartupMode.Keyboard -> stringResource(R.string.welcome_start_mode_keyboard_title)
        StartupMode.Screens -> stringResource(R.string.welcome_start_mode_screens_title)
    }
    val on = stringResource(R.string.welcome_review_on)
    val off = stringResource(R.string.welcome_review_off)
    val privacy = stringResource(
        R.string.welcome_review_privacy_value,
        if (choices.historyVisible) on else off,
        if (choices.featureUsageReportingEnabled) on else off,
    )

    WelcomePage(
        title = stringResource(R.string.welcome_review_title),
        subtitle = stringResource(R.string.welcome_review_subtitle),
        primaryLabel = stringResource(R.string.welcome_review_finish),
        primaryEnabled = !finishing,
        onPrimary = {
            finishing = true
            saveFailed = false
            scope.launch {
                // On success this page leaves composition; on failure the choices stay for a retry.
                if (!onFinish()) {
                    saveFailed = true
                    finishing = false
                }
            }
        },
        onBack = onBack.takeIf { !finishing },
    ) {
        if (saveFailed) {
            Text(
                stringResource(R.string.welcome_review_save_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ReviewRow(stringResource(R.string.welcome_review_voice), voiceName, !finishing, onChangeVoice)
        HorizontalDivider()
        ReviewRow(
            stringResource(R.string.welcome_review_phrase),
            choices.phraseToSave ?: stringResource(R.string.welcome_review_none),
            !finishing,
            onChangePhrase,
        )
        HorizontalDivider()
        ReviewRow(stringResource(R.string.welcome_review_workspace), workspace, !finishing, onChangeWorkspace)
        HorizontalDivider()
        ReviewRow(stringResource(R.string.welcome_review_privacy), privacy, !finishing, onChangePrivacy)
    }
}
