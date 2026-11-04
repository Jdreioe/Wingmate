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
fun TestVoiceScreen(initialPhraseToSave: String?, onNext: (phraseToSave: String?) -> Unit, onBack: () -> Unit) {
    val speechService = koinInject<SpeechService>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val settingsUseCase = koinInject<SettingsUseCase>()
    val defaultTestText = stringResource(R.string.test_voice_default_text)
    
    var selectedVoice by remember { mutableStateOf<Voice?>(null) }
    var engine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    var playbackFailed by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var testText by rememberSaveable(defaultTestText) { mutableStateOf(initialPhraseToSave ?: defaultTestText) }
    var keepAsPhrase by rememberSaveable { mutableStateOf(initialPhraseToSave != null) }
    var loading by remember { mutableStateOf(true) }
    // Without the saved engine, a preview could play through the wrong one; block it instead.
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    /** Stops a preview before leaving, so it does not keep playing over the previous page. */
    fun stopThenBack() {
        scope.launch {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.test_voice_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 16.dp)
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
            Button(onClick = { loadAttempt++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.common_retry))
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = ::stopThenBack, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.common_back))
            }
        } else {
            // Voice info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.test_voice_selected_voice),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        selectedVoice?.displayName ?: selectedVoice?.name ?: stringResource(R.string.test_voice_unknown_voice),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        selectedVoice?.primaryLanguage ?: stringResource(R.string.test_voice_unknown_language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Test text input
            val showKeyboard = Modifier.showKeyboardOnFocus()
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text(stringResource(R.string.test_voice_message_label)) },
                modifier = Modifier.fillMaxWidth().then(showKeyboard),
                minLines = 2,
                maxLines = 4
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Play button
            Button(
                onClick = {
                    if (isPlaying) {
                        // Stop current playback
                        scope.launch {
                            try {
                                    speechService.stop()
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
                                withContext(Dispatchers.IO) {
                                    speechService.speakWithoutHistory(
                                        text = testText,
                                        voice = selectedVoice,
                                        pitch = selectedVoice?.pitch,
                                        rate = selectedVoice?.rate,
                                        cacheAudio = false,
                                        engine = engine,
                                    )
                                }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                    enabled = testText.isNotBlank()
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) {
                        stringResource(R.string.test_voice_stop)
                    } else {
                        stringResource(R.string.test_voice_play)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isPlaying) {
                        stringResource(R.string.test_voice_stop)
                    } else {
                        stringResource(R.string.test_voice_test_button)
                    }
                )
            }
            
            if (playbackFailed) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.test_voice_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
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

            Spacer(modifier = Modifier.height(16.dp))

            // Info text
            Text(
                stringResource(R.string.test_voice_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = ::stopThenBack,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) {
                    Text(stringResource(R.string.common_back))
                }
                
                Button(
                    onClick = {
                        // Stop any ongoing speech before proceeding
                        scope.launch {
                            try {
                                    speechService.stop()
                            } catch (e: Exception) {
                                OperationalLogger.warn("speech.stop", "failed", exceptionClass = e.loggingClassName())
                            }
                            onNext(testText.trim().takeIf { keepAsPhrase && it.isNotEmpty() })
                        }
                    },
                    modifier = Modifier.weight(2f).heightIn(min = 56.dp)
                ) {
                    Text(stringResource(R.string.common_continue))
                }
            }
        }
        }
    }
}
