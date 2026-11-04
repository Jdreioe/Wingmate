package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.domain.TtsEngine
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.application.VoiceUseCase
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
/**
 * "Hear your voice": try the selected voice and set its speed. Used by setup and by Settings
 * after a voice is picked. In setup the Communicator may keep the sentence they tested (for
 * example how they introduce themselves) as a Phrase; the sentence and that choice are held
 * by the caller, so they survive side pages. Previews are not recorded in History or cached,
 * and stop when the page leaves.
 */
@Composable
fun TestVoiceScreen(
    /** The sentence shown, or null until one is typed or kept (then the default is shown). */
    sentence: String?,
    onSentenceChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    primaryLabel: String = stringResource(R.string.common_continue),
    keepAsPhrase: Boolean = false,
    /**
     * Receives the choice and the sentence it applies to (the default text when none was
     * typed). Null hides the switch (Settings has no setup to finish and save it with).
     */
    onKeepAsPhraseChange: ((keep: Boolean, sentence: String) -> Unit)? = null,
    /** A speed not yet confirmed saved, held by setup so it survives this page going away. */
    unsavedSpeed: UnsavedSpeed? = null,
    onSpeedUnsaved: (UnsavedSpeed) -> Unit = {},
    /** That speed is now saved; may arrive after this page has gone (see [SpeedSaver]). */
    onSpeedSaved: (UnsavedSpeed) -> Unit = {},
    /** Opens language choice for a voice that speaks more than one language; null hides it. */
    onChangeLanguage: (() -> Unit)? = null,
    /** False inside Settings, whose top bar already shows the title and handles insets. */
    standalone: Boolean = true,
) {
    val speechService = koinInject<SpeechService>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val settingsUseCase = koinInject<SettingsUseCase>()
    val defaultTestText = stringResource(R.string.test_voice_default_text)
    
    var selectedVoice by remember { mutableStateOf<Voice?>(null) }
    var engine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    var playbackFailed by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    val testText = sentence ?: defaultTestText
    /** The speech request of a preview this page started, so leaving stops only that one. */
    var previewRequest by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Without the saved engine, a preview could play through the wrong one; block it instead.
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Speed is saved once it settles; SpeedSaver owns the saves (one at a time, on the app's
    // scope on the main thread) and tells setup which speed is not yet confirmed saved.
    val appScope = koinInject<CoroutineScope>()
    val reportUnsaved by rememberUpdatedState(onSpeedUnsaved)
    val reportSaved by rememberUpdatedState(onSpeedSaved)
    val speedSaver = remember {
        SpeedSaver(
            scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
            save = { voice ->
                withContext(Dispatchers.IO) {
                    runCatching { voiceUseCase.select(voice) }
                        .onFailure { OperationalLogger.warn("voice_rate.save", "failed", exceptionClass = it.loggingClassName()) }
                        .isSuccess
                }
            },
            onUnsaved = { reportUnsaved(it) },
            onSaved = { reportSaved(it) },
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            // Leaving (Change language, recreation) must not leave a preview talking with no Stop.
            // Only this page's request is stopped, never newer speech that started meanwhile.
            // Decided by what the speech service is doing, not this page's UI flag.
            val ownRequest = previewRequest
            if (ownRequest != null) {
                appScope.launch(Dispatchers.IO) {
                    if (speechService.isPlaying() && speechService.playbackState().requestId == ownRequest) {
                        runCatching { speechService.stop() }
                    }
                }
            }
            // Leaving another way (Leave setup, recreation) with a speed not yet saved: finish
            // saving it on the app's scope. Setup still holds it if that fails, so the next
            // Hear your voice shows it and saves again; leaving setup forgets it with setup.
            speedSaver.leaving()
        }
    }

    /**
     * Leaves the page once the speed is saved, stopping a preview so it does not play on over
     * the next page. One leave at a time: Back, Continue and Change language are ignored while
     * a save is pending (started undispatched, so a second tap in the same frame sees it).
     */
    fun leaveAfterSave(go: () -> Unit) {
        if (speedSaver.flushing) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!speedSaver.flush()) return@launch
            try {
                speechService.stop()
            } catch (e: CancellationException) {
                // The page has left; a cancelled tap must not move setup on again.
                throw e
            } catch (e: Exception) {
                OperationalLogger.warn("speech.stop", "failed", exceptionClass = e.loggingClassName())
            }
            go()
        }
    }
    // Takes precedence over setup's own Back handling while this page is shown.
    BackHandler { leaveAfterSave(onBack) }

    // Load selected voice
    LaunchedEffect(voiceUseCase, loadAttempt) {
        loading = true
        loadFailed = false
        try {
            val (voice, savedEngine) = withContext(Dispatchers.IO) {
                voiceUseCase.selected() to settingsUseCase.get().ttsEngine
            }
            engine = savedEngine
            // A speed setup still holds as unsaved (e.g. after a font-size change recreated this
            // page) is shown at once and saved again; on failure the error shows and Continue retries.
            val unsaved = unsavedSpeed?.takeIf { voice != null && it.voiceName == voice.name.orEmpty() }
            selectedVoice = voice
            if (voice != null) speedSaver.loaded(voice)
            if (voice != null && unsaved != null) {
                selectedVoice = voice.copy(rate = unsaved.rate, rateForSSML = null).also(speedSaver::restored)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationalLogger.warn("voice_selection.load", "failed", exceptionClass = e.loggingClassName())
            loadFailed = true
        } finally {
            loading = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize().then(if (standalone) Modifier.safeDrawingPadding() else Modifier)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            if (standalone) {
                Text(
                    stringResource(R.string.welcome_test_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 16.dp).semantics { heading() }
                )
            }
        
        if (loading) {
            CircularProgressIndicator()
        } else if (loadFailed) {
            Text(
                stringResource(R.string.voice_load_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(modifier = Modifier.height(16.dp))
            SetupActionButton(label = stringResource(R.string.common_retry), onClick = { loadAttempt++ })
        } else {
            // Voice info card
            // Information, not an action: a calm card, so Play and Continue lead.
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        selectedVoice?.displayName ?: selectedVoice?.name ?: stringResource(R.string.test_voice_unknown_voice),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        (selectedVoice?.selectedLanguage?.ifBlank { null } ?: selectedVoice?.primaryLanguage)
                            ?.let(::languageName)
                            ?: stringResource(R.string.test_voice_unknown_language),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (onChangeLanguage != null && selectedVoice.isMultilingual()) {
                Spacer(modifier = Modifier.height(16.dp))
                SetupActionButton(
                    label = stringResource(R.string.welcome_change_language),
                    onClick = { leaveAfterSave(onChangeLanguage) },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // Test text input
            val showKeyboard = Modifier.showKeyboardOnFocus()
            OutlinedTextField(
                value = testText,
                onValueChange = onSentenceChange,
                label = { Text(stringResource(R.string.welcome_test_sentence)) },
                modifier = Modifier.fillMaxWidth().then(showKeyboard),
                minLines = 2,
                maxLines = 4
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Play button: an action, not the page's primary (Continue is).
            SetupActionButton(
                label = stringResource(if (isPlaying) R.string.test_voice_stop else R.string.welcome_test_play),
                icon = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                enabled = testText.isNotBlank(),
                onClick = {
                    if (isPlaying) {
                        // Stop current playback
                        scope.launch {
                            try {
                                speechService.stop()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                OperationalLogger.warn("speech.stop", "failed", exceptionClass = e.loggingClassName())
                            } finally {
                                isPlaying = false
                            }
                        }
                    } else {
                        // Start playback
                        scope.launch {
                            try {
                                isPlaying = true
                                playbackFailed = false
                                // A preview: kept out of History and the audio cache, and played
                                // with exactly the chosen engine so a failing cloud voice is not
                                // silently replaced by the system voice.
                                speechService.speakPreview(testText, selectedVoice, engine, onStarted = { previewRequest = it })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                playbackFailed = true
                                OperationalLogger.warn("speech.test", "failed", exceptionClass = e.loggingClassName())
                            } finally {
                                isPlaying = false
                            }
                        }
                    }
                },
            )

            if (playbackFailed) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    // A cloud voice usually fails because of the connection or the saved key.
                    stringResource(if (engine == TtsEngine.SYSTEM) R.string.test_voice_failed else R.string.test_voice_failed_cloud),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // Speaking rate, right under Play so trying a speed and listening again is one short
            // loop; saved on the voice, as Settings does.
            selectedVoice?.let { voice ->
                val range = if (engine == TtsEngine.ELEVENLABS) 0.7f..1.2f else 0.5f..2.0f
                var rate by remember(voice.name) { mutableFloatStateOf((voice.rate ?: 1.0).toFloat().coerceIn(range)) }
                fun commitRate(value: Float) {
                    val tenths = (value * 10).roundToInt()
                    rate = tenths / 10f
                    // From whole tenths, so 1.1 is stored as 1.1 rather than float noise.
                    selectedVoice = voice.copy(rate = tenths / 10.0, rateForSSML = null)
                    selectedVoice?.let(speedSaver::changed)
                }
                Spacer(modifier = Modifier.height(24.dp))
                val rateValue = stringResource(R.string.welcome_test_rate_value, rate)
                Text(stringResource(R.string.welcome_test_rate, rate), style = MaterialTheme.typography.titleMedium)
                val speedLabel = stringResource(R.string.welcome_test_speed)
                // -/+ beside the slider: dragging a thumb is hard with tremor or dwell.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val slower = stringResource(R.string.welcome_test_slower)
                    FilledTonalIconButton(
                        onClick = { commitRate((rate - 0.1f).coerceIn(range)) },
                        enabled = rate > range.start,
                        modifier = Modifier.size(56.dp).semantics {
                            contentDescription = slower
                            stateDescription = rateValue
                        },
                    ) { Icon(Icons.Default.Remove, contentDescription = null) }
                    Slider(
                        value = rate,
                        onValueChange = { rate = it },
                        onValueChangeFinished = { commitRate(rate) },
                        valueRange = range,
                        // 0.1 steps, so TalkBack and switch users move it one notch at a time.
                        steps = ((range.endInclusive - range.start) * 10).roundToInt() - 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .heightIn(min = 56.dp)
                            .semantics {
                                contentDescription = speedLabel
                                stateDescription = rateValue
                            },
                    )
                    val faster = stringResource(R.string.welcome_test_faster)
                    FilledTonalIconButton(
                        onClick = { commitRate((rate + 0.1f).coerceIn(range)) },
                        enabled = rate < range.endInclusive,
                        modifier = Modifier.size(56.dp).semantics {
                            contentDescription = faster
                            stateDescription = rateValue
                        },
                    ) { Icon(Icons.Default.Add, contentDescription = null) }
                }
                if (speedSaver.failed) {
                    Text(
                        stringResource(R.string.welcome_test_rate_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            
            if (onKeepAsPhraseChange != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = keepAsPhrase, role = Role.Switch, onValueChange = { onKeepAsPhraseChange(it, testText) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.test_voice_keep_as_phrase),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = keepAsPhrase, onCheckedChange = null)
                }
            }

        }
        }
        SetupBottomBar(
            onBack = { leaveAfterSave(onBack) },
            backEnabled = !speedSaver.flushing,
            primaryLabel = primaryLabel,
            primaryEnabled = !loading && !loadFailed,
            // Waiting on a speed save before moving on: show it, so the tap is not silent.
            primaryBusy = speedSaver.flushing,
            onPrimary = { leaveAfterSave(onNext) },
        )
        }
    }
}
