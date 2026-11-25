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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
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
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SpeechService
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

@Composable
internal fun StartPage(
    onSetUp: () -> Unit,
    onExit: (() -> Unit)?,
    startFailed: Boolean,
    restoredFromBackup: Boolean,
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
        primaryLabel = stringResource(if (restoredFromBackup) R.string.welcome_finish_setup else R.string.welcome_set_up),
        // Leaving the page would cancel a restore in progress.
        primaryEnabled = !isRestoring,
        onPrimary = onSetUp,
        onBack = onExit?.takeIf { !isRestoring },
        // Below Set up, so it is read and scanned after the main action, not before it.
        quietLabel = stringResource(if (isRestoring) R.string.welcome_restoring else R.string.welcome_restore_backup),
        quietEnabled = !isRestoring && backupManager != null && backupFilePicker != null,
        onQuiet = {
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
        },
    ) {
        if (restoredFromBackup) {
            Text(
                stringResource(R.string.welcome_restored_needs_setup),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
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

/** What Review shows about the voice. */
internal data class ReviewVoice(val name: String, val speech: String, val languages: String, val multilingual: Boolean)

internal data class SavedVoice(val engine: TtsEngine, val name: String)

internal data class EngineOption(val engine: TtsEngine, val title: Int, val description: Int)

/**
 * Choosing what speaks: the device's own engine or a cloud provider. Saves nothing; setup
 * saves the engine together with the voice. Starts on the engine already in use.
 */
@Composable
internal fun EnginePage(
    onEngineChosen: (TtsEngine) -> Unit,
    onBack: () -> Unit,
    checkFailed: Boolean,
    checking: Boolean,
    /** The engine picked earlier in this setup, kept when coming back to this page. */
    chosenEngine: TtsEngine?,
) {
    val koin = getKoin()
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    var selected by rememberSaveable { mutableStateOf(chosenEngine) }
    LaunchedEffect(settingsUseCase) {
        if (selected != null) return@LaunchedEffect
        selected = try {
            withContext(Dispatchers.Default) { settingsUseCase?.get()?.ttsEngine }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OperationalLogger.warn("welcome.engine", "settings_load_failed", exceptionClass = failure.loggingClassName())
            null
        } ?: TtsEngine.SYSTEM
    }
    val current = selected ?: TtsEngine.SYSTEM
    val options = listOf(
        EngineOption(TtsEngine.SYSTEM, R.string.welcome_engine_device, R.string.welcome_engine_device_desc),
        EngineOption(TtsEngine.AZURE_USER_RESOURCE, R.string.welcome_engine_azure, R.string.welcome_engine_azure_desc),
        EngineOption(TtsEngine.GOOGLE_CLOUD, R.string.welcome_engine_google, R.string.welcome_engine_google_desc),
        EngineOption(TtsEngine.ELEVENLABS, R.string.welcome_engine_elevenlabs, R.string.welcome_engine_elevenlabs_desc),
    )
    WelcomePage(
        title = stringResource(R.string.welcome_engine_title),
        subtitle = null,
        primaryLabel = stringResource(R.string.common_continue),
        primaryEnabled = !checking,
        primaryBusy = checking,
        onPrimary = { onEngineChosen(current) },
        onBack = onBack,
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            options.forEach { (engine, title, description) ->
                ChoiceCard(
                    title = stringResource(title),
                    description = stringResource(description),
                    icon = if (engine == TtsEngine.SYSTEM) Icons.Default.PhoneAndroid else Icons.Default.Cloud,
                    selected = current == engine ||
                        (engine == TtsEngine.AZURE_USER_RESOURCE && current == TtsEngine.AZURE_MANAGED),
                    onSelect = { selected = engine },
                )
            }
        }
        if (checkFailed) {
            Text(
                stringResource(R.string.welcome_engine_check_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
internal fun WorkspacePage(
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

@Composable
internal fun PrivacyPage(
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
        subtitle = null,
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
                SetupActionButton(label = stringResource(R.string.common_retry), onClick = { retryKey++ })
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
        val uriHandler = LocalUriHandler.current
        SetupActionButton(
            label = stringResource(R.string.welcome_privacy_policy),
            onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
            opensBrowser = true,
        )
    }
}

@Composable
internal fun ReviewPage(
    choices: WelcomeChoices,
    onChangeVoice: () -> Unit,
    onChangeLanguage: () -> Unit,
    onChangePhrase: () -> Unit,
    onChangeWorkspace: () -> Unit,
    onChangePrivacy: () -> Unit,
    onBack: () -> Unit,
    onFinish: suspend () -> Boolean,
) {
    val koin = getKoin()
    val voiceUseCase = remember(koin) { koin.getOrNull<VoiceUseCase>() }
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val unknown = stringResource(R.string.test_voice_unknown_voice)
    val deviceEngine = stringResource(R.string.welcome_privacy_device_engine)
    val sentOnlineTemplate = stringResource(R.string.welcome_review_speech_online)
    val speedTemplate = stringResource(R.string.welcome_review_speed)
    // Null while loading, so nothing misleading is shown or announced before the values arrive.
    val voiceAndLanguage by produceState<ReviewVoice?>(null, voiceUseCase, settingsUseCase) {
        value = try {
            withContext(Dispatchers.IO) {
                val voice = voiceUseCase?.selected()
                val name = voice?.let { it.displayName ?: it.name } ?: unknown
                val engine = settingsUseCase?.get()?.ttsEngine
                // ElevenLabs only speaks 0.7–1.2x; show the speed it will actually use.
                val rate = (voice?.rate ?: 1.0).let { if (engine == TtsEngine.ELEVENLABS) it.coerceIn(0.7, 1.2) else it }
                val speed = speedTemplate.format(rate)
                val settings = settingsUseCase?.get()
                val speech = when (val engine = settings?.ttsEngine) {
                    null -> unknown
                    else -> engine.cloudProviderName()?.let { sentOnlineTemplate.format(it) } ?: deviceEngine
                }
                val multilingual = voice.isMultilingual()
                // The same rule as Typing: only a second language this voice can actually speak.
                val languages = settings?.let { listOfNotNull(it.primaryLanguage, it.secondaryLanguageFor(voice)) }
                    ?.filter { it.isNotBlank() }
                    ?.joinToString(" · ") { languageName(it) }
                    ?.ifBlank { null } ?: unknown
                ReviewVoice(name, "$speech · $speed", languages, multilingual)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OperationalLogger.warn("welcome.review", "voice_load_failed", exceptionClass = failure.loggingClassName())
            ReviewVoice(unknown, unknown, unknown, multilingual = false)
        }
    }
    val loading = stringResource(R.string.welcome_review_loading)
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
        subtitle = null,
        primaryLabel = stringResource(R.string.welcome_review_finish),
        primaryEnabled = !finishing,
        primaryBusy = finishing,
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
        if (finishing) {
            Text(
                stringResource(R.string.welcome_review_saving),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (saveFailed) {
            Text(
                stringResource(R.string.welcome_review_save_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ReviewRow(
            stringResource(R.string.welcome_review_voice),
            voiceAndLanguage?.name ?: loading,
            !finishing,
            onChangeVoice,
            detail = voiceAndLanguage?.speech,
        )
        HorizontalDivider()
        ReviewRow(
            stringResource(R.string.welcome_review_language),
            voiceAndLanguage?.languages ?: loading,
            !finishing,
            // A voice that speaks one language leaves nothing to choose.
            onChangeLanguage.takeIf { voiceAndLanguage?.multilingual == true },
        )
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
