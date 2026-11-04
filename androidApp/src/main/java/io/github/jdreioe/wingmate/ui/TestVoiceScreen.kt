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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
/**
 * Setup page for hearing the chosen voice. The Communicator may keep the sentence they
 * tested (for example how they introduce themselves) as a Phrase; [onNext] receives that
 * text, or null when it should not be saved. Previews are not recorded in History or cached.
 */
@Composable
fun TestVoiceScreen(
    initialPhraseToSave: String?,
    /** Opens language choice; offered only when the voice speaks more than one language. */
    onChangeLanguage: () -> Unit,
    onNext: (phraseToSave: String?) -> Unit,
    onBack: () -> Unit,
) {
    val speechService = koinInject<SpeechService>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val settingsUseCase = koinInject<SettingsUseCase>()
    val defaultTestText = stringResource(R.string.test_voice_default_text)
    
    var selectedVoice by remember { mutableStateOf<Voice?>(null) }
    var engine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    var playbackFailed by remember { mutableStateOf(false) }
    var rateSaveFailed by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var testText by rememberSaveable(defaultTestText) { mutableStateOf(initialPhraseToSave ?: defaultTestText) }
    var keepAsPhrase by rememberSaveable { mutableStateOf(initialPhraseToSave != null) }
    var loading by remember { mutableStateOf(true) }
    // Without the saved engine, a preview could play through the wrong one; block it instead.
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Speed is saved once it settles, not per notch: each save is a full voice selection
    // (analytics and, for cloud voices, re-caching Screens audio). Pending saves are
    // flushed before leaving the page.
    var rateSaveJob by remember { mutableStateOf<Job?>(null) }
    /** Saves the speed now; false (with the error shown) if it could not be saved. */
    suspend fun saveRateNow(): Boolean {
        val voice = selectedVoice ?: return true
        rateSaveFailed = false
        return try {
            withContext(Dispatchers.IO) { voiceUseCase.select(voice) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationalLogger.warn("voice_rate.save", "failed", exceptionClass = e.loggingClassName())
            rateSaveFailed = true
            false
        }
    }
    fun scheduleRateSave() {
        rateSaveJob?.cancel()
        rateSaveJob = scope.launch {
            delay(800)
            saveRateNow()
        }
    }
    /**
     * Saves a speed still waiting to be saved, or one whose save failed; call before leaving
     * the page. Returns false if it still could not be saved, so the page stays and says so.
     */
    suspend fun flushRate(): Boolean {
        val pending = rateSaveJob
        rateSaveJob = null
        if (pending != null && pending.isActive) {
            pending.cancelAndJoin()
            return saveRateNow()
        }
        return if (rateSaveFailed) saveRateNow() else true
    }
    // Leaving another way (e.g. Leave setup) cancels this page's scope; a speed still waiting,
    // or one whose save failed, is saved on the app's scope so it is not lost.
    val appScope = koinInject<CoroutineScope>()
    DisposableEffect(Unit) {
        onDispose {
            if (rateSaveJob?.isActive == true || rateSaveFailed) {
                val voice = selectedVoice
                rateSaveJob?.cancel()
                if (voice != null) {
                    appScope.launch(Dispatchers.IO) {
                        runCatching { voiceUseCase.select(voice) }
                            .onFailure { OperationalLogger.warn("voice_rate.save", "failed_on_leave", exceptionClass = it.loggingClassName()) }
                    }
                }
            }
        }
    }

    /** Stops a preview before leaving, so it does not keep playing over the previous page. */
    fun stopThenBack() {
        scope.launch {
            if (!flushRate()) return@launch
            try {
                speechService.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OperationalLogger.warn("speech.stop", "failed", exceptionClass = e.loggingClassName())
            }
            onBack()
        }
    }
    // Takes precedence over setup's own Back handling while this page is shown.
    BackHandler(onBack = ::stopThenBack)

    // Load selected voice
    LaunchedEffect(voiceUseCase, loadAttempt) {
        loading = true
        loadFailed = false
        try {
            val (voice, savedEngine) = withContext(Dispatchers.IO) {
                voiceUseCase.selected() to settingsUseCase.get().ttsEngine
            }
            selectedVoice = voice
            engine = savedEngine
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
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                stringResource(R.string.welcome_test_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 16.dp).semantics { heading() }
            )
        
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
            if ((selectedVoice?.supportedLanguages?.size ?: 0) > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                SetupActionButton(
                    label = stringResource(R.string.welcome_change_language),
                    onClick = { scope.launch { if (flushRate()) onChangeLanguage() } },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // Test text input
            val showKeyboard = Modifier.showKeyboardOnFocus()
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
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
                                speechService.speakPreview(testText, selectedVoice, engine)
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
                    rate = (value * 10).roundToInt() / 10f
                    selectedVoice = voice.copy(rate = rate.toDouble(), rateForSSML = null)
                    scheduleRateSave()
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
                if (rateSaveFailed) {
                    Text(
                        stringResource(R.string.welcome_test_rate_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = keepAsPhrase, role = Role.Switch, onValueChange = { keepAsPhrase = it }),
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
        SetupBottomBar(
            onBack = ::stopThenBack,
            primaryLabel = stringResource(R.string.common_continue),
            primaryEnabled = !loading && !loadFailed,
            onPrimary = {
                // Stop any ongoing speech before proceeding
                scope.launch {
                    try {
                        speechService.stop()
                    } catch (e: CancellationException) {
                        // The page has left; a cancelled tap must not move setup on again.
                        throw e
                    } catch (e: Exception) {
                        OperationalLogger.warn("speech.stop", "failed", exceptionClass = e.loggingClassName())
                    }
                    if (!flushRate()) return@launch
                    onNext(testText.trim().takeIf { keepAsPhrase && it.isNotEmpty() })
                }
            },
        )
        }
    }
}
