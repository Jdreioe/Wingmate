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

/** One page of setup. [analyticsName] is stable across R8 renaming. */
private enum class WelcomeStep(val analyticsName: String) {
    Start("start"),
    VoiceEngine("voice_engine"),
    AzureSetup("azure_setup"),
    GoogleSetup("google_setup"),
    ElevenLabsSetup("elevenlabs_setup"),
    Voice("voice"),
    Language("language"),
    TestVoice("test_voice"),
    Workspace("workspace"),
    Privacy("privacy"),
    Review("review"),
}

/** Keeps a list of steps across activity recreation. */
private val stepListSaver = listSaver<List<WelcomeStep>, String>(
    save = { steps -> steps.map { it.name } },
    restore = { names -> names.map(WelcomeStep::valueOf) },
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

/**
 * First-run setup: voice first (so the Communicator hears themselves early), then how
 * they communicate, then what stays on the device, then a review with one finish button.
 * Navigation is a back stack, so system Back and on-screen Back always return to the
 * page that was actually shown before; a page opened with Change returns to Review.
 * A backup restored from the Start page is routed by App's restore handling.
 *
 * Voice, engine, and language are saved as they are chosen, exactly as in Settings; a
 * cloud engine is saved only once its provider is set up, so no exit point can leave an
 * engine without credentials.
 */
@Composable
fun WelcomeScreen(
    initialChoices: WelcomeChoices,
    /** Saves [WelcomeChoices] and opens the workspace; returns false if saving failed. */
    onComplete: suspend (WelcomeChoices) -> Boolean,
    /**
     * Non-null when setup was reopened by someone who already communicates with Wingmate.
     * Leaving keeps voice changes already made and discards the other answers.
     */
    onExit: (() -> Unit)?,
) {
    val koin = getKoin()
    val featureUsageReporter = remember(koin) { koin.getOrNull<FeatureUsageReporter>() }
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val scope = rememberCoroutineScope()

    var path by rememberSaveable(stateSaver = stepListSaver) { mutableStateOf(listOf(WelcomeStep.Start)) }
    /** The path to Review while a page opened with Change is shown; empty otherwise. */
    var reviewPath by rememberSaveable(stateSaver = stepListSaver) { mutableStateOf(emptyList<WelcomeStep>()) }
    var choices by rememberSaveable(stateSaver = welcomeChoicesSaver) { mutableStateOf(initialChoices) }
    var startFailed by remember { mutableStateOf(false) }
    var engineSaveFailed by remember { mutableStateOf(false) }
    val step = path.last()

    fun push(next: WelcomeStep) { path = path + next }
    fun pop() { if (path.size > 1) path = path.dropLast(1) }
    /** Opens an earlier page from Review; Back and finishing that page return to Review. */
    fun change(target: WelcomeStep) {
        reviewPath = path
        path = path + target
    }
    /** Moves on from the last page of a section, or back to Review after a Change. */
    fun finishSection(next: WelcomeStep) {
        if (reviewPath.isNotEmpty()) {
            path = reviewPath
            reviewPath = emptyList()
        } else {
            push(next)
        }
    }

    fun startSetup() {
        scope.launch {
            startFailed = false
            // Reopened setup is not marked: leaving it must never make setup mandatory.
            if (onExit == null) {
                try {
                    withContext(Dispatchers.Default) {
                        val useCase = checkNotNull(settingsUseCase)
                        useCase.update(useCase.get().copy(welcomeFlowStarted = true))
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    // Without the flag, an interrupted setup would later be skipped; stay here.
                    OperationalLogger.warn("welcome.start", "mark_started_failed", exceptionClass = failure.loggingClassName())
                    startFailed = true
                    return@launch
                }
            }
            if (path == listOf(WelcomeStep.Start)) push(WelcomeStep.VoiceEngine)
        }
    }

    /** Saves [engine] once it can speak, then opens the voice list for it. */
    fun useEngine(engine: TtsEngine) {
        scope.launch {
            engineSaveFailed = false
            try {
                withContext(Dispatchers.Default) {
                    val useCase = checkNotNull(settingsUseCase)
                    useCase.update(useCase.get().copy(ttsEngine = engine))
                }
                push(WelcomeStep.Voice)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                OperationalLogger.warn("welcome.engine", "save_failed", exceptionClass = failure.loggingClassName())
                engineSaveFailed = true
                path = path.takeWhile { it != WelcomeStep.VoiceEngine } + WelcomeStep.VoiceEngine
            }
        }
    }

    LaunchedEffect(Unit) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.WELCOME_STARTED)
    }
    LaunchedEffect(step) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.WELCOME_STEP_VIEWED, "step" to step.analyticsName)
        if (step == WelcomeStep.Review) reviewPath = emptyList()
    }
    BackHandler(enabled = path.size > 1 || onExit != null) {
        if (path.size > 1) pop() else onExit?.invoke()
    }

    val pageModifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)

    Column(Modifier.fillMaxSize()) {
        // Start and Review have their own way out (Back) and their own busy states.
        if (onExit != null && step != WelcomeStep.Start && step != WelcomeStep.Review) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onExit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.welcome_leave_setup))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            WelcomeStepContent(
                step = step,
                choices = choices,
                onChoicesChange = { choices = it },
                startFailed = startFailed,
                engineSaveFailed = engineSaveFailed,
                onExit = onExit,
                pageModifier = pageModifier,
                onStartSetup = ::startSetup,
                onEngineChosen = { engine ->
                    featureUsageReporter?.reportEvent(
                        FeatureUsageEvents.VOICE_ENGINE_SELECTED,
                        "engine" to engine.name.lowercase()
                    )
                    when (engine) {
                        TtsEngine.SYSTEM -> useEngine(engine)
                        TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> push(WelcomeStep.AzureSetup)
                        TtsEngine.GOOGLE_CLOUD -> push(WelcomeStep.GoogleSetup)
                        TtsEngine.ELEVENLABS -> push(WelcomeStep.ElevenLabsSetup)
                    }
                },
                onUseEngine = ::useEngine,
                onPush = ::push,
                onPop = ::pop,
                onFinishSection = ::finishSection,
                onChange = ::change,
                onFinish = { onComplete(choices) },
                onWorkspaceChosen = {
                    featureUsageReporter?.reportEvent(
                        FeatureUsageEvents.STARTUP_MODE_SELECTED,
                        "mode" to choices.startupMode.name.lowercase()
                    )
                    finishSection(WelcomeStep.Privacy)
                },
            )
        }
    }
}

@Composable
private fun WelcomeStepContent(
    step: WelcomeStep,
    choices: WelcomeChoices,
    onChoicesChange: (WelcomeChoices) -> Unit,
    startFailed: Boolean,
    engineSaveFailed: Boolean,
    onExit: (() -> Unit)?,
    pageModifier: Modifier,
    onStartSetup: () -> Unit,
    onEngineChosen: (TtsEngine) -> Unit,
    onUseEngine: (TtsEngine) -> Unit,
    onPush: (WelcomeStep) -> Unit,
    onPop: () -> Unit,
    onFinishSection: (WelcomeStep) -> Unit,
    onChange: (WelcomeStep) -> Unit,
    onFinish: suspend () -> Boolean,
    onWorkspaceChosen: () -> Unit,
) {
    when (step) {
        WelcomeStep.Start -> StartPage(onSetUp = onStartSetup, onExit = onExit, startFailed = startFailed)
        WelcomeStep.VoiceEngine -> VoiceEngineSelectorScreen(
            onEngineChosen = onEngineChosen,
            onBack = onPop,
            errorMessage = if (engineSaveFailed) stringResource(R.string.welcome_engine_save_failed) else null,
        )
        // Provider pages also save credentials; the engine is saved only once they are done.
        WelcomeStep.AzureSetup -> F0SetupScreen(onDone = { onUseEngine(TtsEngine.AZURE_USER_RESOURCE) }, onBack = onPop)
        WelcomeStep.GoogleSetup -> GoogleTtsSetupScreen(onDone = { onUseEngine(TtsEngine.GOOGLE_CLOUD) }, onBack = onPop)
        WelcomeStep.ElevenLabsSetup -> ElevenLabsSetupScreen(onDone = { onUseEngine(TtsEngine.ELEVENLABS) }, onBack = onPop)
        WelcomeStep.Voice -> VoiceSelectionPage(
            onBack = onPop,
            onVoiceSelected = { onPush(WelcomeStep.Language) },
            modifier = pageModifier,
        )
        WelcomeStep.Language -> LanguageSelectionPage(
            onBack = onPop,
            onContinue = { onPush(WelcomeStep.TestVoice) },
            modifier = pageModifier,
        )
        WelcomeStep.TestVoice -> TestVoiceScreen(
            initialPhraseToSave = choices.phraseToSave,
            onNext = { phraseToSave ->
                onChoicesChange(choices.copy(phraseToSave = phraseToSave))
                onFinishSection(WelcomeStep.Workspace)
            },
            onBack = onPop,
        )
        WelcomeStep.Workspace -> WorkspacePage(
            choices = choices,
            onChoicesChange = onChoicesChange,
            onBack = onPop,
            onContinue = onWorkspaceChosen,
        )
        WelcomeStep.Privacy -> PrivacyPage(
            choices = choices,
            onChoicesChange = onChoicesChange,
            onBack = onPop,
            onContinue = { onFinishSection(WelcomeStep.Review) },
        )
        WelcomeStep.Review -> ReviewPage(
            choices = choices,
            onChangeVoice = { onChange(WelcomeStep.VoiceEngine) },
            onChangePhrase = { onChange(WelcomeStep.TestVoice) },
            onChangeWorkspace = { onChange(WelcomeStep.Workspace) },
            onChangePrivacy = { onChange(WelcomeStep.Privacy) },
            onBack = onPop,
            onFinish = onFinish,
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
    startFailed: Boolean,
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
        val error = restoreStatus ?: if (startFailed) stringResource(R.string.welcome_start_failed) else null
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
        // Continue only once the speech card can say where words go.
        primaryEnabled = engine is EngineLoad.Ready,
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
            is EngineLoad.Ready -> when (val provider = current.engine.cloudProviderName()) {
                null -> PrivacyCard(
                    title = stringResource(R.string.welcome_privacy_speech_title),
                    location = DataLocation.DeviceEngine,
                    description = stringResource(R.string.welcome_privacy_speech_system),
                )
                else -> PrivacyCard(
                    title = stringResource(R.string.welcome_privacy_speech_title),
                    location = DataLocation.Online,
                    description = stringResource(R.string.welcome_privacy_speech_cloud, provider),
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

/** Where the speech engine setting stands on the Privacy page. */
private sealed interface EngineLoad {
    data object Loading : EngineLoad
    data object Failed : EngineLoad
    data class Ready(val engine: TtsEngine) : EngineLoad
}

/** The cloud provider that receives spoken text, or null for the device's own engine. Brand names stay untranslated. */
private fun TtsEngine.cloudProviderName(): String? = when (this) {
    TtsEngine.SYSTEM -> null
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
    // Null while loading, so nothing misleading is shown or announced before the name arrives.
    val voiceName by produceState<String?>(null, voiceUseCase) {
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
        ReviewRow(stringResource(R.string.welcome_review_voice), voiceName ?: "…", !finishing, onChangeVoice)
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
