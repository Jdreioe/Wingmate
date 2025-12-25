package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(
    entries: List<PronunciationEntry>,
    onAddEntry: (String, String, String) -> Unit,
    onDeleteEntry: (PronunciationEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pronunciation Dictionary") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Add entry")
            }
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                "Global pronunciation rules applied to all speech",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No dictionary entries",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Tap + to add pronunciation rules",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.word }) { entry ->
                        DictionaryEntryCard(
                            entry = entry,
                            onDelete = { onDeleteEntry(entry) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddDictionaryEntryDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { word, phoneme, alphabet ->
                onAddEntry(word, phoneme, alphabet)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun DictionaryEntryCard(
    entry: PronunciationEntry,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.word,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = entry.phoneme,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Alphabet: ${entry.alphabet}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddDictionaryEntryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var word by remember { mutableStateOf("") }
    var phoneme by remember { mutableStateOf("") }
    var alphabet by remember { mutableStateOf("ipa") }
    var expandedAlphabet by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Pronunciation Entry") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word") },
                    placeholder = { Text("e.g., tomato") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = phoneme,
                    onValueChange = { phoneme = it },
                    label = { Text("Phoneme") },
                    placeholder = { Text("e.g., /təˈmɑːtoʊ/") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("Alphabet", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick = { expandedAlphabet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(alphabet)
                    }
                    DropdownMenu(
                        expanded = expandedAlphabet,
                        onDismissRequest = { expandedAlphabet = false }
                    ) {
                        listOf("ipa", "x-sampa", "sapi", "ups").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    alphabet = option
                                    expandedAlphabet = false
                                }
                            )
                        }
                    }
                }

                Text(
                    "Global entries apply to all speech synthesis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(word.trim(), phoneme.trim(), alphabet) },
                enabled = word.isNotBlank() && phoneme.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
