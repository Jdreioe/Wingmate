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

/**
 * The answers collected during setup. Voice and language choices are saved by their own
 * pages as before; everything here is saved only when the Communicator presses
 * "Start communicating". Start from [fromSettings] so re-running setup keeps current choices.
 */
data class WelcomeChoices(
    val startupMode: StartupMode = StartupMode.Keyboard,
    /** The sentence on Hear your voice, or null until one is typed or kept. Kept across side pages. */
    val sentence: String? = null,
    /** Whether to keep [sentence] as a Phrase. */
    val keepSentence: Boolean = false,
    val historyVisible: Boolean = true,
    val featureUsageReportingEnabled: Boolean = false,
) {
    /** The Phrase setup adds, or null. */
    val phraseToSave: String? get() = sentence?.trim()?.takeIf { keepSentence && it.isNotEmpty() }

    companion object {
        fun fromSettings(settings: Settings) = WelcomeChoices(
            startupMode = settings.startupMode,
            historyVisible = settings.historyVisible,
            featureUsageReportingEnabled = settings.featureUsageReportingEnabled,
        )
    }
}

/** Keeps the draft answers across activity recreation. */
private val welcomeChoicesSaver = listSaver<WelcomeChoices, Any?>(
    save = { listOf(it.startupMode.name, it.sentence, it.keepSentence, it.historyVisible, it.featureUsageReportingEnabled) },
    restore = { values ->
        WelcomeChoices(
            startupMode = StartupMode.valueOf(values[0] as String),
            sentence = values[1] as String?,
            keepSentence = values[2] as Boolean,
            historyVisible = values[3] as Boolean,
            featureUsageReportingEnabled = values[4] as Boolean,
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
 * Voice and language are saved as they are chosen, as in Settings. The chosen engine is
 * held here and saved together with the voice, so as long as both saves succeed, no way out
 * of setup leaves an engine paired with another engine's voice or without credentials.
 */
@Composable
fun WelcomeScreen(
    initialChoices: WelcomeChoices,
    /** Saves [WelcomeChoices] and opens the workspace; returns false if saving failed. */
    onComplete: suspend (WelcomeChoices) -> Boolean,
    /**
     * Non-null when setup was reopened by someone who already communicates with Wingmate.
     * Leaving keeps voice changes already made and discards the other answers. A Phrase
     * already saved by a finish whose settings save failed is kept: it was asked for.
     */
    onExit: (() -> Unit)?,
    /** True when a restored backup still needs setup, so Start can confirm the restore. */
    restoredFromBackup: Boolean = false,
) {
    val koin = getKoin()
    val featureUsageReporter = remember(koin) { koin.getOrNull<FeatureUsageReporter>() }
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val configRepository = remember(koin) { koin.getOrNull<ConfigRepository>() }
    val speechService = remember(koin) { koin.getOrNull<SpeechService>() }
    val phraseUseCase = remember(koin) { koin.getOrNull<PhraseUseCase>() }
    val voiceUseCase = remember(koin) { koin.getOrNull<VoiceUseCase>() }
    val scope = rememberCoroutineScope()

    var nav by rememberSaveable(stateSaver = WelcomeNavigation.Saver) { mutableStateOf(WelcomeNavigation()) }
    var choices by rememberSaveable(stateSaver = welcomeChoicesSaver) { mutableStateOf(initialChoices) }
    // The Phrase this setup already added. Owned here, so leaving setup forgets it and a
    // later setup can never edit or delete a Phrase the Communicator has been using.
    var savedPhraseId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedPhraseText by rememberSaveable { mutableStateOf<String?>(null) }
    /** The engine whose voices the Voice page lists; saved only together with a voice. */
    var chosenEngine by rememberSaveable { mutableStateOf<TtsEngine?>(null) }
    var startFailed by remember { mutableStateOf(false) }
    var engineCheckFailed by remember { mutableStateOf(false) }
    /** True while checking for a saved key, so a repeated activation cannot start a second check. */
    var checkingEngine by remember { mutableStateOf(false) }
    val step = nav.step
    // The saved engine and voice, read while the Voice page is not shown so what it offers to
    // keep never appears or moves under someone using dwell or a switch.
    var savedVoice by remember { mutableStateOf<SavedVoice?>(null) }
    LaunchedEffect(step == WelcomeStep.Voice) {
        // Loaded once even on the Voice page, for setup restored straight onto it.
        if (step == WelcomeStep.Voice && savedVoice != null) return@LaunchedEffect
        savedVoice = try {
            withContext(Dispatchers.Default) {
                val engine = settingsUseCase?.get()?.ttsEngine
                val voice = voiceUseCase?.selected()
                if (engine != null && voice != null) SavedVoice(engine, voice.displayName ?: voice.name.orEmpty()) else null
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OperationalLogger.warn("welcome.voice", "saved_voice_load_failed", exceptionClass = failure.loggingClassName())
            null
        }
    }
    /** The saved voice, when it belongs to the engine being set up and so can be kept as it is. */
    // The engine the Voice page is about: the one just chosen, or, when Back from Review has
    // returned here after the choice was cleared, the saved one.
    val voiceEngine = chosenEngine ?: savedVoice?.engine
    val keepableVoice = savedVoice?.takeIf { it.engine == voiceEngine }?.name

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
            nav = nav.advance(WelcomeStep.Start, WelcomeStep.VoiceEngine)
        }
    }

    /** Lists [engine]'s voices next; only one activation counts if [from] is no longer shown. */
    fun useEngine(engine: TtsEngine, from: WelcomeStep) {
        if (nav.step != from) return
        chosenEngine = engine
        nav = nav.toVoiceFrom(from)
    }

    /** A cloud engine goes through its provider page unless its credentials are already saved. */
    fun chooseEngine(engine: TtsEngine) {
        if (nav.step != WelcomeStep.VoiceEngine || checkingEngine) return
        engineCheckFailed = false
        featureUsageReporter?.reportEvent(FeatureUsageEvents.VOICE_ENGINE_SELECTED, "engine" to engine.name.lowercase())
        val providerStep = engine.providerStep() ?: return useEngine(engine, WelcomeStep.VoiceEngine)
        checkingEngine = true
        scope.launch {
            val configured: Boolean = try {
                withContext(Dispatchers.Default) {
                    val config = checkNotNull(configRepository)
                    when (providerStep) {
                        WelcomeStep.AzureSetup -> config.getSpeechConfigStatus().credentialConfigured
                        WelcomeStep.GoogleSetup -> config.getGoogleSpeechConfigStatus().credentialConfigured
                        else -> config.getElevenLabsSpeechConfigStatus().credentialConfigured
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                OperationalLogger.warn("welcome.engine", "status_failed", exceptionClass = failure.loggingClassName())
                // Unknown: neither skip a working key nor ask for one without saying why.
                engineCheckFailed = true
                checkingEngine = false
                return@launch
            }
            checkingEngine = false
            if (configured) {
                useEngine(engine, WelcomeStep.VoiceEngine)
            } else {
                // Remembered so the engine page still shows this choice if they come back.
                chosenEngine = engine
                nav = nav.advance(WelcomeStep.VoiceEngine, providerStep)
            }
        }
    }

    /**
     * Brings the setup Phrase in line with Review before setup is marked finished: a retry
     * after a failed finish edits or removes the Phrase already added instead of adding
     * another. Returns false if it could not be saved. (If the activity is recreated while
     * an add is still running, its id can be lost and a retry adds a second copy; rare.)
     */
    suspend fun syncSetupPhrase(wanted: String?): Boolean {
        if (wanted == savedPhraseText) return true
        return try {
            val previousId = savedPhraseId
            savedPhraseId = withContext(Dispatchers.Default) {
                val phrases = checkNotNull(phraseUseCase)
                val previous = previousId?.let { id -> phrases.list().firstOrNull { it.id == id } }
                when {
                    wanted == null -> {
                        if (previous != null) phrases.delete(previous.id)
                        null
                    }
                    previous != null -> phrases.update(previous.copy(text = wanted)).id
                    else -> phrases.add(Phrase(id = "", text = wanted, createdAt = 0)).id
                }
            }
            savedPhraseText = wanted
            true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OperationalLogger.warn("welcome.complete", "phrase_save_failed", exceptionClass = failure.loggingClassName())
            false
        }
    }

    /** Leaves reopened setup, stopping a voice preview first so it does not play on. */
    fun leave() {
        val exit = onExit ?: return
        scope.launch {
            try {
                speechService?.stop()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                OperationalLogger.warn("speech.stop", "failed", exceptionClass = failure.loggingClassName())
            }
            exit()
        }
    }

    LaunchedEffect(Unit) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.WELCOME_STARTED)
    }
    LaunchedEffect(step) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.WELCOME_STEP_VIEWED, "step" to step.analyticsName)
        // Back on Review, an engine picked but not saved with a voice is forgotten, so the
        // engine page shows what is actually in use next time.
        if (step == WelcomeStep.Review) chosenEngine = null
    }
    BackHandler(enabled = nav.canGoBack || onExit != null) {
        if (nav.canGoBack) nav = nav.back(nav.step) else leave()
    }

    val pageModifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)

    Column(Modifier.fillMaxSize()) {
        // Start's Back leaves setup. Review has a save in progress to protect, so leaving is one Back away, on Privacy.
        if (onExit != null && step != WelcomeStep.Start && step != WelcomeStep.Review) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                SetupQuietButton(label = stringResource(R.string.welcome_leave_setup), onClick = ::leave)
            }
        }
        Box(Modifier.weight(1f)) {
            WelcomeStepContent(
                step = step,
                choices = choices,
                onChoicesChange = { choices = it },
                startFailed = startFailed,
                engineCheckFailed = engineCheckFailed,
                checkingEngine = checkingEngine,
                keepableVoice = keepableVoice,
                voiceEngine = voiceEngine,
                restoredFromBackup = restoredFromBackup,
                chosenEngine = chosenEngine,
                onExit = onExit?.let { ::leave },
                pageModifier = pageModifier,
                onStartSetup = ::startSetup,
                onEngineChosen = ::chooseEngine,
                onUseEngine = ::useEngine,
                onAdvance = { from, to -> nav = nav.advance(from, to) },
                // Bound to the page shown now, so a late second tap is ignored once it has left.
                onBack = { nav = nav.back(step) },
                onFinishSection = { from, next -> nav = nav.finishSection(from, next) },
                onChange = { target -> nav = nav.change(target) },
                onFinish = { syncSetupPhrase(choices.phraseToSave) && onComplete(choices) },
                onWorkspaceChosen = {
                    if (nav.step == WelcomeStep.Workspace) {
                        featureUsageReporter?.reportEvent(
                            FeatureUsageEvents.STARTUP_MODE_SELECTED,
                            "mode" to choices.startupMode.name.lowercase()
                        )
                        nav = nav.finishSection(WelcomeStep.Workspace, WelcomeStep.Privacy)
                    }
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
    engineCheckFailed: Boolean,
    checkingEngine: Boolean,
    keepableVoice: String?,
    voiceEngine: TtsEngine?,
    restoredFromBackup: Boolean,
    chosenEngine: TtsEngine?,
    onExit: (() -> Unit)?,
    pageModifier: Modifier,
    onStartSetup: () -> Unit,
    onEngineChosen: (TtsEngine) -> Unit,
    onUseEngine: (TtsEngine, WelcomeStep) -> Unit,
    onAdvance: (from: WelcomeStep, to: WelcomeStep) -> Unit,
    onBack: () -> Unit,
    onFinishSection: (from: WelcomeStep, next: WelcomeStep) -> Unit,
    onChange: (WelcomeStep) -> Unit,
    onFinish: suspend () -> Boolean,
    onWorkspaceChosen: () -> Unit,
) {
    when (step) {
        WelcomeStep.Start -> StartPage(
            onSetUp = onStartSetup,
            onExit = onExit,
            startFailed = startFailed,
            restoredFromBackup = restoredFromBackup,
        )
        WelcomeStep.VoiceEngine -> EnginePage(
            onEngineChosen = onEngineChosen,
            onBack = onBack,
            checkFailed = engineCheckFailed,
            checking = checkingEngine,
            chosenEngine = chosenEngine,
        )
        // Provider pages save credentials only; the engine is saved together with the voice.
        WelcomeStep.AzureSetup -> F0SetupScreen(
            onDone = { onUseEngine(TtsEngine.AZURE_USER_RESOURCE, WelcomeStep.AzureSetup) },
            onBack = onBack,
        )
        WelcomeStep.GoogleSetup -> GoogleTtsSetupScreen(
            onDone = { onUseEngine(TtsEngine.GOOGLE_CLOUD, WelcomeStep.GoogleSetup) },
            onBack = onBack,
        )
        WelcomeStep.ElevenLabsSetup -> ElevenLabsSetupScreen(
            onDone = { onUseEngine(TtsEngine.ELEVENLABS, WelcomeStep.ElevenLabsSetup) },
            onBack = onBack,
        )
        WelcomeStep.Voice -> PageWithBack(
            title = stringResource(R.string.welcome_voice_title),
            onBack = onBack,
            // A saved key can stop working (e.g. restored from a backup); setup is the only
            // place to replace it before Settings is reachable.
            secondaryLabel = voiceEngine?.takeIf { it != TtsEngine.SYSTEM }
                ?.let { stringResource(R.string.welcome_reenter_key) },
            onSecondary = { voiceEngine?.providerStep()?.let { onAdvance(WelcomeStep.Voice, it) } },
            // Keeping the voice already in use must not mean finding it again in a long list.
            subtitle = keepableVoice?.let { stringResource(R.string.welcome_current_voice, it) },
            primaryLabel = if (keepableVoice != null) stringResource(R.string.welcome_keep_voice) else null,
            onPrimary = { onAdvance(WelcomeStep.Voice, WelcomeStep.TestVoice) },
        ) {
            VoiceSelectionPage(
                onBack = onBack,
                onVoiceSelected = { onAdvance(WelcomeStep.Voice, WelcomeStep.TestVoice) },
                engineToSave = voiceEngine,
                modifier = pageModifier,
            )
        }
        // Language choices save as they are tapped; Continue returns to the page that opened it.
        WelcomeStep.Language -> PageWithBack(
            title = stringResource(R.string.welcome_language_title),
            onBack = onBack,
            primaryLabel = stringResource(R.string.common_continue),
            onPrimary = onBack,
        ) {
            LanguageSelectionPage(onBack = onBack, modifier = pageModifier)
        }
        WelcomeStep.TestVoice -> TestVoiceScreen(
            // Held by setup, so a side trip to Change language keeps what was typed.
            sentence = choices.sentence,
            keepAsPhrase = choices.keepSentence,
            onSentenceChange = { onChoicesChange(choices.copy(sentence = it)) },
            onKeepAsPhraseChange = { keep, text -> onChoicesChange(choices.copy(keepSentence = keep, sentence = text)) },
            onChangeLanguage = { onAdvance(WelcomeStep.TestVoice, WelcomeStep.Language) },
            onNext = { onFinishSection(WelcomeStep.TestVoice, WelcomeStep.Workspace) },
            onBack = onBack,
        )
        WelcomeStep.Workspace -> WorkspacePage(
            choices = choices,
            onChoicesChange = onChoicesChange,
            onBack = onBack,
            onContinue = onWorkspaceChosen,
        )
        WelcomeStep.Privacy -> PrivacyPage(
            choices = choices,
            onChoicesChange = onChoicesChange,
            onBack = onBack,
            onContinue = { onFinishSection(WelcomeStep.Privacy, WelcomeStep.Review) },
        )
        WelcomeStep.Review -> ReviewPage(
            choices = choices,
            onChangeVoice = { onChange(WelcomeStep.VoiceEngine) },
            onChangeLanguage = { onChange(WelcomeStep.Language) },
            onChangePhrase = { onChange(WelcomeStep.TestVoice) },
            onChangeWorkspace = { onChange(WelcomeStep.Workspace) },
            onChangePrivacy = { onChange(WelcomeStep.Privacy) },
            onBack = onBack,
            onFinish = onFinish,
        )
    }
}

// ─── Shared layout ───────────────────────────────────────────────────────────

/**
 * Wraps a page shared with Settings in setup's frame: a heading, and Back, an optional
 * second action, and an optional primary action pinned where every setup page has them.
 */
@Composable
private fun PageWithBack(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp).semantics { heading() },
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) { content() }
        secondaryLabel?.let {
            SetupActionButton(label = it, onClick = onSecondary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp))
        }
        SetupBottomBar(onBack = onBack, primaryLabel = primaryLabel, onPrimary = onPrimary)
    }
}

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
    /** Shows progress in the primary button, announced as [primaryLabel] still. */
    primaryBusy: Boolean = false,
    /** An optional quiet action under the bottom bar (e.g. Restore a backup). */
    quietLabel: String? = null,
    quietEnabled: Boolean = true,
    onQuiet: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
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
            SetupBottomBar(
                onBack = onBack,
                primaryLabel = primaryLabel,
                onPrimary = onPrimary,
                primaryEnabled = primaryEnabled,
                primaryBusy = primaryBusy,
            )
            quietLabel?.let {
                SetupQuietButton(
                    label = it,
                    onClick = onQuiet,
                    enabled = quietEnabled,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                )
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
private data class ReviewVoice(val name: String, val speech: String, val languages: String, val multilingual: Boolean)

private data class SavedVoice(val engine: TtsEngine, val name: String)

private data class EngineOption(val engine: TtsEngine, val title: Int, val description: Int)

/**
 * Choosing what speaks: the device's own engine or a cloud provider. Saves nothing; setup
 * saves the engine together with the voice. Starts on the engine already in use.
 */
@Composable
private fun EnginePage(
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

/** The setup page that saves this engine's credentials, or null for the device's own engine. */
private fun TtsEngine.providerStep(): WelcomeStep? = when (this) {
    TtsEngine.SYSTEM -> null
    TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> WelcomeStep.AzureSetup
    TtsEngine.GOOGLE_CLOUD -> WelcomeStep.GoogleSetup
    TtsEngine.ELEVENLABS -> WelcomeStep.ElevenLabsSetup
}

/** Wingmate's privacy policy, kept in the repository next to the code it describes. */
internal const val PRIVACY_POLICY_URL = "https://github.com/Jdreioe/Wingmate/blob/main/PRIVACY_POLICY.md"

/** A language tag such as "da-DK" as people read it ("Danish (Denmark)") in the app's language. */
internal fun languageName(tag: String): String =
    java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.getDefault()).ifBlank { tag }

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

/** One answer on Review. [detail] is a quieter second line; no [onChange] means nothing to choose. */
@Composable
private fun ReviewRow(label: String, value: String, enabled: Boolean, onChange: (() -> Unit)?, detail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onChange != null) {
            val changeLabel = stringResource(R.string.welcome_review_change_label, label)
            FilledTonalButton(
                onClick = onChange,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = changeLabel },
            ) {
                Text(stringResource(R.string.welcome_review_change))
            }
        }
    }
}

@Composable
private fun ReviewPage(
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
                val multilingual = (voice?.supportedLanguages?.size ?: 0) > 1
                // Only what this voice can speak: a second language stored for an earlier voice is not shown.
                val languages = settings?.let { if (multilingual) listOf(it.primaryLanguage, it.secondaryLanguage) else listOf(it.primaryLanguage) }
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
