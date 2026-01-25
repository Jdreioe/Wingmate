package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.launch

@Composable
fun SsmlSidebar(
    modifier: Modifier = Modifier,
    inputText: String = "",
    inputSelection: androidx.compose.ui.text.TextRange = androidx.compose.ui.text.TextRange(0),
    onInsertSsml: ((String) -> Unit)? = null
) {
    val voiceUseCase = remember { GlobalContext.get().get<VoiceUseCase>() }
    val settingsUseCase = remember { GlobalContext.get().get<SettingsUseCase>() }
    val scope = rememberCoroutineScope()

    var selectedVoice by remember { mutableStateOf<Voice?>(null) }
    var availableLangs by remember { mutableStateOf(listOf("en-US")) }
    var primaryLang by remember { mutableStateOf("en-US") }
    var secondaryLang by remember { mutableStateOf("en-US") }
    var pitch by remember { mutableStateOf(1.0) }
    var rate by remember { mutableStateOf(1.0) }
    var volume by remember { mutableStateOf(1.0) }
    var useSystemTts by remember { mutableStateOf(false) }
    var emphasis by remember { mutableStateOf("strong") }
    var breakDuration by remember { mutableStateOf(500) }
    var phonemeEntry by remember { mutableStateOf("") }
    var sayAsType by remember { mutableStateOf("characters") }

    // Track selected text for context-aware actions
    val selectionStart = inputSelection.start
    val selectionEnd = inputSelection.end
    val hasSelection = selectionStart < selectionEnd
    val selectedText = if (hasSelection) inputText.substring(selectionStart, selectionEnd) else ""

    LaunchedEffect(Unit) {
        val v = runCatching { voiceUseCase.selected() }.getOrNull()
        selectedVoice = v
        availableLangs = v?.supportedLanguages?.takeIf { it.isNotEmpty() } ?: listOf("en-US")
        pitch = v?.pitch ?: 1.0
        rate = v?.rate ?: 1.0
        val s = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
        primaryLang = s.primaryLanguage
        secondaryLang = s.secondaryLanguage
        useSystemTts = s.useSystemTts
    }

    // Helper to insert SSML with selected text or placeholder
    val insertWithSelection: (String, String, String) -> Unit = { prefix, suffix, fallback ->
        val content = if (hasSelection) selectedText else fallback
        onInsertSsml?.invoke("$prefix$content$suffix")
    }

    Surface(modifier = modifier, tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("SSML Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // ===== Voice & Engine =====
            Text("Voice & Engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Engine: ${if (useSystemTts) "System" else "Azure"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = useSystemTts, onCheckedChange = { checked ->
                    useSystemTts = checked
                    scope.launch {
                        val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                        settingsUseCase.updateWithNotification(current.copy(useSystemTts = checked))
                    }
                }, modifier = Modifier.scale(0.8f))
            }

            Text(
                text = "Voice: ${selectedVoice?.displayName ?: selectedVoice?.name ?: "(none)"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )

            Divider()

            // ===== Languages =====
            Text("Languages", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            
            Text("Primary", style = MaterialTheme.typography.labelSmall)
            LanguageMenu(available = availableLangs, filter = "", selected = primaryLang) { sel ->
                primaryLang = sel
                scope.launch {
                    val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                    settingsUseCase.updateWithNotification(current.copy(primaryLanguage = sel))
                    selectedVoice?.let { v ->
                        runCatching { voiceUseCase.select(v.copy(selectedLanguage = sel)) }
                    }
                }
            }

            Text("Secondary", style = MaterialTheme.typography.labelSmall)
            LanguageMenu(available = availableLangs, filter = "", selected = secondaryLang) { sel ->
                secondaryLang = sel
                scope.launch {
                    val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                    settingsUseCase.updateWithNotification(current.copy(secondaryLanguage = sel))
                }
            }

            Divider()

            // ===== Prosody (Pitch, Rate, Volume) =====
            Text("Prosody", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            
            Text("Pitch: ${String.format("%.2f", pitch)}", style = MaterialTheme.typography.labelSmall)
            Slider(value = pitch.toFloat(), onValueChange = { v ->
                pitch = v.toDouble()
            }, valueRange = 0.5f..2.0f, modifier = Modifier.height(32.dp))
            
            Text("Rate: ${String.format("%.2f", rate)}", style = MaterialTheme.typography.labelSmall)
            Slider(value = rate.toFloat(), onValueChange = { v ->
                rate = v.toDouble()
            }, valueRange = 0.5f..2.0f, modifier = Modifier.height(32.dp))
            
            Text("Volume: ${String.format("%.2f", volume)}", style = MaterialTheme.typography.labelSmall)
            Slider(value = volume.toFloat(), onValueChange = { v ->
                volume = v.toDouble()
            }, valueRange = 0.5f..2.0f, modifier = Modifier.height(32.dp))
            
            Button(onClick = {
                selectedVoice?.let { base ->
                    val updated = base.copy(pitch = pitch, rate = rate, pitchForSSML = null, rateForSSML = null)
                    scope.launch { runCatching { voiceUseCase.select(updated) } }
                    selectedVoice = updated
                }
            }, enabled = selectedVoice != null, modifier = Modifier.fillMaxWidth()) { 
                Text("Apply Prosody") 
            }

            Divider()

            // ===== Emphasis =====
            Text("Emphasis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            
            var expandedEmphasis by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expandedEmphasis = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(emphasis, style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = expandedEmphasis, onDismissRequest = { expandedEmphasis = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                    listOf("none", "reduced", "moderate", "strong").forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level, style = MaterialTheme.typography.labelSmall) },
                            onClick = { emphasis = level; expandedEmphasis = false }
                        )
                    }
                }
            }
            
            Button(
                onClick = { insertWithSelection("<emphasis level=\"$emphasis\">", "</emphasis>", "text") },
                enabled = onInsertSsml != null && emphasis != "none",
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Emphasize ${if (hasSelection) "Selection" else "Text"}", style = MaterialTheme.typography.labelSmall)
            }

            Divider()

            // ===== Break/Silence =====
            Text("Break (ms)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            
            OutlinedTextField(
                value = breakDuration.toString(),
                onValueChange = { v ->
                    runCatching { breakDuration = v.toInt().coerceIn(0, 5000) }.onFailure { breakDuration = 500 }
                },
                label = { Text("Duration (ms)", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true
            )
            
            Button(
                onClick = { onInsertSsml?.invoke(" <break time=\"${breakDuration}ms\"/> ") },
                enabled = onInsertSsml != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text("Add ${breakDuration}ms Break", style = MaterialTheme.typography.labelSmall)
            }

            Divider()

            // ===== Phoneme/Dictionary =====
            Text("Dictionary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            
            Text("Phoneme IPA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            OutlinedTextField(
                value = phonemeEntry,
                onValueChange = { phonemeEntry = it },
                placeholder = { Text("e.g., /təˈmɑːtoʊ/", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true
            )
            Text("IPA phonemes for custom pronunciation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            
            Button(
                onClick = { insertWithSelection("<phoneme alphabet=\"ipa\" ph=\"$phonemeEntry\">", "</phoneme>", "text") },
                enabled = onInsertSsml != null && phonemeEntry.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text("Set Pronunciation", style = MaterialTheme.typography.labelSmall)
            }

            Divider()

            // ===== Say-As =====
            Text("Say-As", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            
            var expandedSayAs by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expandedSayAs = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(sayAsType, style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = expandedSayAs, onDismissRequest = { expandedSayAs = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                    listOf(
                        "characters", "spell-out", "cardinal", "number",
                        "ordinal", "digits", "fraction", "unit",
                        "date", "time", "telephone", "address",
                        "currency", "name"
                    ).forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type, style = MaterialTheme.typography.labelSmall) },
                            onClick = { sayAsType = type; expandedSayAs = false }
                        )
                    }
                }
            }
            Text("Interprets text as specific types (e.g., dates, numbers)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            
            Button(
                onClick = { insertWithSelection("<say-as interpret-as=\"$sayAsType\">", "</say-as>", "text") },
                enabled = onInsertSsml != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Read as ${sayAsType.replace("-", " ").capitalize()}", style = MaterialTheme.typography.labelSmall)
            }

            Divider()

            // ===== Info =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Tip: Use these controls to fine-tune speech output with SSML markup.", 
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Changes apply to new speech generation.", 
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

// Helper composable for consistent scaling
fun Modifier.scale(factor: Float): Modifier = this.then(
    Modifier.graphicsLayer(scaleX = factor, scaleY = factor)
)
