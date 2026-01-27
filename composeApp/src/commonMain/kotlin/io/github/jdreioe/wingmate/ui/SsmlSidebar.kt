package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ArrowDropDown
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
    // secondaryLang retained for logic if needed, but simplified UI might hide it or integrate it differently
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

    Surface(modifier = modifier.padding(8.dp), tonalElevation = 1.dp, shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Speech Controls", 
                style = MaterialTheme.typography.titleMedium, 
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // ===== Voice & Engine =====
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("Engine", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            Text(if (useSystemTts) "System (Offline)" else "Azure (Premium)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Switch(
                            checked = useSystemTts, 
                            onCheckedChange = { checked ->
                                useSystemTts = checked
                                scope.launch {
                                    val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                                    settingsUseCase.updateWithNotification(current.copy(useSystemTts = checked))
                                }
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (useSystemTts) Icons.Filled.Settings else Icons.Filled.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Voice: ${selectedVoice?.displayName ?: selectedVoice?.name ?: "(none)"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ===== Tone & Speed (Prosody) =====
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tone & Speed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    
                    // Pitch
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Pitch", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
                        Slider(
                            value = pitch.toFloat(), 
                            onValueChange = { pitch = it.toDouble() }, 
                            valueRange = 0.5f..2.0f, 
                            modifier = Modifier.weight(1f)
                        )
                        Text(String.format("%.1f", pitch), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(32.dp))
                    }
                    
                    // Rate
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Speed", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
                        Slider(
                            value = rate.toFloat(), 
                            onValueChange = { rate = it.toDouble() }, 
                            valueRange = 0.5f..2.0f, 
                            modifier = Modifier.weight(1f)
                        )
                        Text(String.format("%.1f", rate), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(32.dp))
                    }
                    
                    FilledTonalButton(
                        onClick = {
                             selectedVoice?.let { base ->
                                val updated = base.copy(pitch = pitch, rate = rate, pitchForSSML = null, rateForSSML = null)
                                scope.launch { runCatching { voiceUseCase.select(updated) } }
                                selectedVoice = updated
                            }
                        }, 
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        enabled = selectedVoice != null
                    ) { 
                        Text("Apply Settings") 
                    }
                }
            }

            // ===== Pauses =====
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add Pause", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = false,
                            onClick = { onInsertSsml?.invoke(" [0.5s] ") },
                            label = { Text("0.5s") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = false,
                            onClick = { onInsertSsml?.invoke(" [1.0s] ") },
                            label = { Text("1.0s") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = false,
                            onClick = { onInsertSsml?.invoke(" [2.0s] ") },
                            label = { Text("2.0s") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            // ===== Emphasis =====
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Emphasis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Highlight the ${if(hasSelection) "selected text" else "next word"} with:", style = MaterialTheme.typography.bodySmall)
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("reduced", "moderate", "strong").forEach { level ->
                            FilterChip(
                                selected = emphasis == level,
                                onClick = { 
                                    emphasis = level
                                    insertWithSelection("<emphasis level=\"$level\">", "</emphasis>", "text")
                                },
                                label = { Text(level.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }) }
                            )
                        }
                    }
                }
            }

            // ===== Interpretation =====
            ElevatedCard(
                 colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Read As...", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    
                    var expanded by remember { mutableStateOf(false) }
                    
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(sayAsType.replace("-", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    
                    if (expanded) {
                         DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                             listOf("spell-out", "date", "time", "telephone", "currency", "number").forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type) },
                                    onClick = { 
                                        sayAsType = type
                                        expanded = false
                                        insertWithSelection("<say-as interpret-as=\"$type\">", "</say-as>", "text")
                                    }
                                )
                             }
                         }
                    }
                }
            }
            
             // Info Tip
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Switch to Azure engine to hear the highest quality changes.", 
                    style = MaterialTheme.typography.labelSmall, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Helper composable for consistent scaling
fun Modifier.scale(factor: Float): Modifier = this.then(
    Modifier.graphicsLayer(scaleX = factor, scaleY = factor)
)
