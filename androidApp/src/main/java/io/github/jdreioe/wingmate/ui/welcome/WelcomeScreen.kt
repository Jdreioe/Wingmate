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
    /** A speed set on Hear your voice and not yet confirmed saved; cleared when a save succeeds. */
    val unsavedSpeed: UnsavedSpeed? = null,
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

/** A speed for [voiceName] that setup has not yet confirmed saved. */
data class UnsavedSpeed(val voiceName: String, val rate: Double)

/** Keeps the draft answers across activity recreation. */
internal val welcomeChoicesSaver = listSaver<WelcomeChoices, Any?>(
    save = {
        listOf(
            it.startupMode.name, it.sentence, it.keepSentence, it.historyVisible, it.featureUsageReportingEnabled,
            it.unsavedSpeed?.voiceName, it.unsavedSpeed?.rate,
        )
    },
    restore = { values ->
        WelcomeChoices(
            startupMode = StartupMode.valueOf(values[0] as String),
            sentence = values[1] as String?,
            keepSentence = values[2] as Boolean,
            historyVisible = values[3] as Boolean,
            featureUsageReportingEnabled = values[4] as Boolean,
            unsavedSpeed = (values[5] as String?)?.let { UnsavedSpeed(it, values[6] as Double) },
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

    // Wide windows show setup as a guided two-pane page: where the Communicator is on the
    // left, the current page on the right at a readable width. The page itself is the same.
    val windowWidth = currentWindowWidth()
    Row(Modifier.fillMaxSize()) {
        if (windowWidth == WindowWidth.Expanded) {
            SetupProgressPane(step = step, modifier = Modifier.weight(0.38f).fillMaxHeight())
        }
        Box(
            Modifier.weight(if (windowWidth == WindowWidth.Expanded) 0.62f else 1f).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.then(if (windowWidth.isLarge) Modifier.widthIn(max = SetupPageMaxWidth) else Modifier).fillMaxSize()) {
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
                        onSpeedUnsaved = { choices = choices.copy(unsavedSpeed = it) },
                        // Only that exact speed: a saver from before recreation may finish late.
                        onSpeedSaved = { if (choices.unsavedSpeed == it) choices = choices.copy(unsavedSpeed = null) },
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
    }
}

@Composable
internal fun WelcomeStepContent(
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
    /** Applied to the current draft (it may arrive after a save, not on a fresh composition). */
    onSpeedUnsaved: (UnsavedSpeed) -> Unit,
    onSpeedSaved: (UnsavedSpeed) -> Unit,
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
            // A voice is already selected: name it, so Continue (which keeps it) needs no search
            // through a long list. Tapping another voice chooses that one instead.
            subtitle = keepableVoice?.let { stringResource(R.string.welcome_selected_voice, it) },
            primaryLabel = if (keepableVoice != null) stringResource(R.string.common_continue) else null,
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
            unsavedSpeed = choices.unsavedSpeed,
            onSpeedUnsaved = onSpeedUnsaved,
            onSpeedSaved = onSpeedSaved,
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

/** How wide a setup page grows on a large window: past this, lines and buttons get hard to scan. */
internal val SetupPageMaxWidth = 680.dp

/** The parts of setup, in order, as the progress pane names them. */
internal enum class SetupSection(val label: Int) {
    Voice(R.string.setup_section_voice),
    Communicate(R.string.setup_section_communicate),
    Privacy(R.string.setup_section_privacy),
    Review(R.string.setup_section_review),
}

internal val WelcomeStep.section: SetupSection?
    get() = when (this) {
        WelcomeStep.Start -> null
        WelcomeStep.VoiceEngine, WelcomeStep.AzureSetup, WelcomeStep.GoogleSetup, WelcomeStep.ElevenLabsSetup,
        WelcomeStep.Voice, WelcomeStep.TestVoice, WelcomeStep.Language -> SetupSection.Voice
        WelcomeStep.Workspace -> SetupSection.Communicate
        WelcomeStep.Privacy -> SetupSection.Privacy
        WelcomeStep.Review -> SetupSection.Review
    }

/** The setup page that saves this engine's credentials, or null for the device's own engine. */
internal fun TtsEngine.providerStep(): WelcomeStep? = when (this) {
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
internal sealed interface EngineLoad {
    data object Loading : EngineLoad
    data object Failed : EngineLoad
    data class Ready(val engine: TtsEngine) : EngineLoad
}

/** The cloud provider that receives spoken text, or null for the device's own engine. Brand names stay untranslated. */
internal fun TtsEngine.cloudProviderName(): String? = when (this) {
    TtsEngine.SYSTEM -> null
    TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> "Microsoft Azure"
    TtsEngine.GOOGLE_CLOUD -> "Google Cloud"
    TtsEngine.ELEVENLABS -> "ElevenLabs"
}
