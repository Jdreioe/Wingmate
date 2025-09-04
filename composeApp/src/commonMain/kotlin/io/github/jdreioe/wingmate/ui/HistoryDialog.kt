package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.application.VoiceUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

@Composable
fun HistoryDialog(
    show: Boolean,
    items: List<SaidText>,
    onDismiss: () -> Unit,
    onInsert: ((String) -> Unit)? = null,
) {
    if (!show) return

    val speechService = remember { GlobalContext.get().get<SpeechService>() }
    val voiceUseCase = remember { GlobalContext.get().get<VoiceUseCase>() }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("History") },
        text = {
            if (items.isEmpty()) {
                Text("No items yet. Speak something to build your history.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items) { it ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(
                                    it.saidText ?: "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AssistChip(onClick = {
                                        val text = it.saidText ?: return@AssistChip
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { speechService.speak(text, runCatching { voiceUseCase.selected() }.getOrNull()) }
                                        }
                                    }, label = { Text("Speak") })

                                    AssistChip(onClick = {
                                        val text = it.saidText ?: return@AssistChip
                                        onInsert?.invoke(text)
                                        onDismiss()
                                    }, label = { Text("Insert") })

                                    val meta = buildString {
                                        it.primaryLanguage?.let { l -> append(l) }
                                        if (!isEmpty()) append(" • ")
                                        it.voiceName?.let { v -> append(v) }
                                    }
                                    if (meta.isNotBlank()) {
                                        Spacer(Modifier.weight(1f))
                                        Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
