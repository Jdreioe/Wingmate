package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_add
import wingmatekmp.composeapp.generated.resources.common_cancel
import wingmatekmp.composeapp.generated.resources.dictionary_add_entry_cd
import wingmatekmp.composeapp.generated.resources.dictionary_add_entry_title
import wingmatekmp.composeapp.generated.resources.dictionary_advanced_options
import wingmatekmp.composeapp.generated.resources.dictionary_alphabet_format
import wingmatekmp.composeapp.generated.resources.dictionary_alphabet_label
import wingmatekmp.composeapp.generated.resources.dictionary_back_cd
import wingmatekmp.composeapp.generated.resources.dictionary_common_ipa
import wingmatekmp.composeapp.generated.resources.dictionary_delete_cd
import wingmatekmp.composeapp.generated.resources.dictionary_description
import wingmatekmp.composeapp.generated.resources.dictionary_easy_help
import wingmatekmp.composeapp.generated.resources.dictionary_empty_subtitle
import wingmatekmp.composeapp.generated.resources.dictionary_empty_title
import wingmatekmp.composeapp.generated.resources.dictionary_global_entries_note
import wingmatekmp.composeapp.generated.resources.dictionary_guess
import wingmatekmp.composeapp.generated.resources.dictionary_guess_failed
import wingmatekmp.composeapp.generated.resources.dictionary_phoneme_label
import wingmatekmp.composeapp.generated.resources.dictionary_phoneme_placeholder
import wingmatekmp.composeapp.generated.resources.dictionary_test_cd
import wingmatekmp.composeapp.generated.resources.dictionary_test_pronunciation
import wingmatekmp.composeapp.generated.resources.dictionary_title
import wingmatekmp.composeapp.generated.resources.dictionary_word_label
import wingmatekmp.composeapp.generated.resources.dictionary_word_placeholder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(
    entries: List<PronunciationEntry>,
    onAddEntry: (String, String, String) -> Unit,
    onDeleteEntry: (PronunciationEntry) -> Unit,
    onTestEntry: (String, String, String) -> Unit,
    onGuessPronunciation: suspend (String) -> String? = { null },
    onBack: (() -> Unit)? = null,
    showTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = { Text(stringResource(Res.string.dictionary_title)) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(Res.string.dictionary_back_cd)
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, stringResource(Res.string.dictionary_add_entry_cd))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 88.dp)
        ) {
            Text(
                stringResource(Res.string.dictionary_description),
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
                            stringResource(Res.string.dictionary_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.dictionary_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(entries, key = { it.word.lowercase() }) { entry ->
                        DictionaryEntryCard(
                            entry = entry,
                            onDelete = { onDeleteEntry(entry) },
                            onTest = { onTestEntry(entry.word, entry.phoneme, entry.alphabet) }
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
            },
            onTest = onTestEntry,
            onGuess = onGuessPronunciation
        )
    }
}

@Composable
private fun DictionaryEntryCard(
    entry: PronunciationEntry,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large
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
                    text = stringResource(
                        Res.string.dictionary_alphabet_format,
                        alphabetDisplayName(entry.alphabet)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                IconButton(onClick = onTest) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(Res.string.dictionary_test_cd),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.dictionary_delete_cd),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDictionaryEntryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit,
    onGuess: suspend (String) -> String?
) {
    var word by remember { mutableStateOf("") }
    var phoneme by remember { mutableStateOf("") }
    var alphabet by remember { mutableStateOf("text") }
    var expandedAlphabet by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var isGuessing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val guessFailedMessage = stringResource(Res.string.dictionary_guess_failed)
    val scrollState = rememberScrollState()

    val commonIpa = listOf(
        "ə", "ˈ", "ˌ", "ː", "θ", "ð", "ʃ", "ʒ", "ŋ", "j",
        "æ", "ɑ", "ɔ", "ɛ", "ɪ", "ʊ", "ʌ", "u", "i"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dictionary_add_entry_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                val showKeyboard = rememberShowKeyboardOnFocus()
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text(stringResource(Res.string.dictionary_word_label)) },
                    placeholder = { Text(stringResource(Res.string.dictionary_word_placeholder)) },
                    modifier = Modifier.fillMaxWidth().then(showKeyboard),
                    singleLine = true
                )

                Text(
                    stringResource(Res.string.dictionary_easy_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = phoneme,
                    onValueChange = { phoneme = it },
                    label = { Text(stringResource(Res.string.dictionary_phoneme_label)) },
                    placeholder = { Text(stringResource(Res.string.dictionary_phoneme_placeholder)) },
                    modifier = Modifier.fillMaxWidth().then(showKeyboard),
                    singleLine = true
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isGuessing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        OutlinedButton(
                            onClick = {
                                if (word.isNotBlank()) {
                                    isGuessing = true
                                    error = null
                                    scope.launch {
                                        val guess = onGuess(word.trim())
                                        if (guess != null) {
                                            phoneme = guess
                                            alphabet = "ipa"
                                            showAdvanced = true
                                        } else {
                                            error = guessFailedMessage
                                        }
                                        isGuessing = false
                                    }
                                }
                            },
                            enabled = word.isNotBlank()
                        ) {
                            Text(stringResource(Res.string.dictionary_guess))
                        }
                    }

                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(stringResource(Res.string.dictionary_advanced_options))
                    }
                }

                if (showAdvanced) {
                    Text(
                        stringResource(Res.string.dictionary_alphabet_label),
                        style = MaterialTheme.typography.labelMedium
                    )
                    ExposedDropdownMenuBox(
                        expanded = expandedAlphabet,
                        onExpandedChange = { expandedAlphabet = it }
                    ) {
                        OutlinedTextField(
                            value = alphabetDisplayName(alphabet),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAlphabet)
                            }
                        )
                        ExposedDropdownMenu(
                            expanded = expandedAlphabet,
                            onDismissRequest = { expandedAlphabet = false }
                        ) {
                            listOf("text", "ipa", "x-sampa", "sapi", "ups").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(alphabetDisplayName(option)) },
                                    onClick = {
                                        alphabet = option
                                        expandedAlphabet = false
                                    }
                                )
                            }
                        }
                    }
                    if (alphabet == "ipa") {
                        Text(
                            stringResource(Res.string.dictionary_common_ipa),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            commonIpa.forEach { symbol ->
                                SuggestionChip(
                                    onClick = { phoneme += symbol },
                                    label = { Text(symbol) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { onTest(word.trim(), phoneme.trim(), alphabet) },
                    enabled = word.isNotBlank() && phoneme.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.dictionary_test_pronunciation))
                }

                Text(
                    stringResource(Res.string.dictionary_global_entries_note),
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
                Text(stringResource(Res.string.common_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        }
    )
}

private fun alphabetDisplayName(alphabet: String): String = when (alphabet) {
    "text" -> "Easy text"
    "ipa" -> "IPA"
    "x-sampa" -> "X-SAMPA"
    "sapi" -> "SAPI"
    "ups" -> "UPS"
    else -> alphabet
}
