package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import org.koin.core.context.GlobalContext
import io.github.jdreioe.wingmate.application.SettingsUseCase
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhraseScreen(onBackToWelcome: (() -> Unit)? = null) {
    // Ensure Koin is initialized
    require(GlobalContext.getOrNull() != null) { "Koin not initialized. Call initKoin() before starting the app." }
    val bloc = remember { GlobalContext.get().get<PhraseBloc>() }
    val state by bloc.state.collectAsState()

    LaunchedEffect(Unit) { bloc.dispatch(PhraseEvent.Load) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelection by remember { mutableStateOf(false) }
    var showUiLanguageDialog by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Load persisted primary language for display in top bar
            val settingsUseCase = remember { runCatching { GlobalContext.get().get<SettingsUseCase>() }.getOrNull() }
            val primaryLanguageState = produceState(initialValue = "en-US", key1 = settingsUseCase) {
                val s = settingsUseCase?.let { runCatching { it.get() }.getOrNull() }
                value = s?.primaryLanguage ?: "en-US"
            }

            // Input state (hoisted so topBar History button can access it)
            var input by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }

            // Dependencies for playback controls
            val speechService = remember { GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SpeechService>() }
            val saidRepo = remember { GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SaidTextRepository>() }
            val voiceUseCase = remember { GlobalContext.get().get<VoiceUseCase>() }
            
            // selected voice / available languages for language selection
            val selectedVoiceState = produceState<io.github.jdreioe.wingmate.domain.Voice?>(initialValue = null, key1 = voiceUseCase) {
                value = runCatching { voiceUseCase.selected() }.getOrNull()
            }
            val uiScope = rememberCoroutineScope()

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text("Wingmate", style = MaterialTheme.typography.titleLarge) },
                        actions = {
                            // Back to Welcome button
                            if (onBackToWelcome != null) {
                                IconButton(onClick = onBackToWelcome) {
                                    Icon(
                                        imageVector = Icons.Filled.Home, 
                                        contentDescription = "Back to Welcome"
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            
                            // expressive language selector - shows current voice language
                            ElevatedButton(onClick = { showUiLanguageDialog = true }) {
                                Text(selectedVoiceState.value?.selectedLanguage?.takeIf { it.isNotBlank() } 
                                     ?: primaryLanguageState.value)
                            }
                            Spacer(Modifier.width(12.dp))
                            // History button: restore the most recently saved said text into the input field
                            val topBarScope = rememberCoroutineScope()
                            IconButton(onClick = {
                                topBarScope.launch(Dispatchers.IO) {
                                    val newest = runCatching { saidRepo.list().firstOrNull() }.getOrNull()
                                    if (newest?.saidText != null) {
                                        val text = newest.saidText ?: ""
                                        // switch to main to update state
                                        topBarScope.launch {
                                            input = androidx.compose.ui.text.input.TextFieldValue(text, selection = androidx.compose.ui.text.TextRange(text.length))
                                        }
                                    }
                                }
                            }) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "History")
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { showVoiceSelection = true }) {
                                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Voice settings")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors()
                    )
                },
                bottomBar = {
                    // Make the playback bar less obvious by removing elevation and background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(16.dp)
                    ) {
                        PlaybackControls(
                            onPlay = {
                                if (input.text.isBlank()) return@PlaybackControls
                                uiScope.launch(Dispatchers.IO) {
                                    try {
                                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                        speechService.speak(input.text, selected, selected?.pitch, selected?.rate)
                                        // Save a history record
                                        runCatching {
                                            val now = System.currentTimeMillis()
                                            saidRepo.add(
                                                io.github.jdreioe.wingmate.domain.SaidText(
                                                    date = now,
                                                    saidText = input.text,
                                                    voiceName = selected?.name,
                                                    pitch = selected?.pitch,
                                                    speed = selected?.rate,
                                                        createdAt = now,
                                                        position = 0,
                                                        primaryLanguage = selected?.selectedLanguage?.takeIf { it.isNotBlank() } ?: selected?.primaryLanguage
                                                    )
                                                )
                                            }
                                        } catch (t: Throwable) {
                                            // swallow for UI; diagnostics logged by service
                                        }
                                    }
                                },
                            onPause = {
                                uiScope.launch { speechService.pause() }
                            },
                            onStop = { 
                                uiScope.launch { speechService.stop() } 
                            },
                            onPlaySecondary = {
                                if (input.text.isBlank()) return@PlaybackControls
                                uiScope.launch(Dispatchers.IO) {
                                    try {
                                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                        val secondaryLang = settingsUseCase?.let { runCatching { it.get() }.getOrNull()?.secondaryLanguage } ?: selected?.primaryLanguage
                                        val vForSecondary = selected?.copy(selectedLanguage = secondaryLang ?: selected?.selectedLanguage ?: "")
                                        speechService.speak(input.text, vForSecondary, vForSecondary?.pitch, vForSecondary?.rate)
                                        // Save a history record for secondary as well
                                        runCatching {
                                            val now = System.currentTimeMillis()
                                            saidRepo.add(
                                                io.github.jdreioe.wingmate.domain.SaidText(
                                                    date = now,
                                                    saidText = input.text,
                                                    voiceName = vForSecondary?.name,
                                                    pitch = vForSecondary?.pitch,
                                                    speed = vForSecondary?.rate,
                                                    createdAt = now,
                                                    position = 0,
                                                    primaryLanguage = vForSecondary?.selectedLanguage?.takeIf { it.isNotBlank() } ?: vForSecondary?.primaryLanguage
                                                )
                                            )
                                        }
                                    } catch (t: Throwable) {
                                    }
                                }
                            }
                        )
                    }
                }
            ) { innerPadding ->
                Column(modifier = Modifier.padding(16.dp).padding(innerPadding)) {
                    if (state.loading) Text("Loading...")
                    state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

                    // Load categories from CategoryUseCase if available; fallback to phrases with isCategory flag
                    val categoryUseCase = remember { runCatching { GlobalContext.get().get<io.github.jdreioe.wingmate.application.CategoryUseCase>() }.getOrNull() }
                    var categories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
                    val coroutineScope = rememberCoroutineScope()

                    // load initial categories
                    LaunchedEffect(categoryUseCase, state.items) {
                        categories = if (categoryUseCase != null) {
                            runCatching { categoryUseCase.list() }.getOrNull() ?: emptyList()
                        } else {
                            state.items.filter { it.isCategory }.map { cat -> CategoryItem(id = cat.id, name = cat.name ?: cat.id) }
                        }
                    }

                    // Category selector with dialog
                    var selectedCategory by remember { mutableStateOf<CategoryItem?>(null) } // Start with no category selected (show all)
                    var showAddCategoryDialog by remember { mutableStateOf(false) }

                    // Large text input field that expands to fill available space
                    OutlinedTextField(
                        value = input, 
                        onValueChange = { input = it }, 
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 180.dp), // Fixed size based on 4-6 lines
                        placeholder = { Text("Enter text to speak") },
                        minLines = 4,
                        maxLines = 6
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Category chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        // "All" chip to show all phrases
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("All") }
                            )
                        }
                        
                        // Category chips
                        items(categories) { category ->
                            FilterChip(
                                selected = selectedCategory?.id == category.id,
                                onClick = { selectedCategory = category },
                                label = { Text(category.name ?: "All") }
                            )
                        }
                        
                        // Add category chip
                        item {
                            FilterChip(
                                selected = false,
                                onClick = { showAddCategoryDialog = true },
                                label = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = "Add category",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add")
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    // Add category dialog
                    if (showAddCategoryDialog) {
                        var categoryName by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showAddCategoryDialog = false },
                            title = { Text("Add Category") },
                            text = {
                                OutlinedTextField(
                                    value = categoryName,
                                    onValueChange = { categoryName = it },
                                    placeholder = { Text("Category name") },
                                    singleLine = true
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val name = categoryName.trim()
                                        if (name.isNotBlank() && !categories.any { it.name.equals(name, ignoreCase = true) }) {
                                            if (categoryUseCase != null) {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    try {
                                                        val toAdd = io.github.jdreioe.wingmate.domain.CategoryItem(
                                                            id = "",
                                                            name = name,
                                                            selectedLanguage = primaryLanguageState.value
                                                        )
                                                        val added = categoryUseCase.add(toAdd)
                                                        val newList = runCatching { categoryUseCase.list() }.getOrNull() ?: emptyList()
                                                        coroutineScope.launch {
                                                            categories = newList
                                                            selectedCategory = CategoryItem(id = added.id, name = added.name)
                                                        }
                                                    } catch (_: Throwable) {}
                                                }
                                            } else {
                                                // fallback: add as a phrase marker (legacy)
                                                val newPhrase = Phrase(id = "", text = "", name = name, backgroundColor = null, parentId = null, isCategory = true, createdAt = 0L)
                                                bloc.dispatch(PhraseEvent.Add(newPhrase))
                                            }
                                        }
                                        showAddCategoryDialog = false
                                        categoryName = ""
                                    }
                                ) {
                                    Text("Add")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { 
                                    showAddCategoryDialog = false
                                    categoryName = ""
                                }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    // phrase grid below everything
                    var showEditDialog by remember { mutableStateOf(false) }
                    var editingPhrase by remember { mutableStateOf<Phrase?>(null) }
                    // Show only actual phrase items (not category markers), filtered by selected category
                    val visiblePhrases = state.items.filter { !it.isCategory && (selectedCategory?.id == null || it.parentId == selectedCategory?.id) }
                    PhraseGrid(
                        phrases = visiblePhrases,
                        onInsert = { phrase ->
                            // insert phrase.text at current cursor position
                            val fv = input
                            val pos = fv.selection.start.coerceIn(0, fv.text.length)
                            val insertText = phrase.text ?: ""
                            val newText = fv.text.substring(0, pos) + insertText + fv.text.substring(pos)
                            val newCursor = pos + insertText.length
                            input = androidx.compose.ui.text.input.TextFieldValue(newText, selection = androidx.compose.ui.text.TextRange(newCursor))
                        },
                        onPlay = { phrase ->
                            uiScope.launch(Dispatchers.IO) {
                                try {
                                    val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                    speechService.speak(phrase.text ?: "", selected)
                                } catch (_: Throwable) {}
                            }
                        },
                        onPlaySecondary = { phrase ->
                            uiScope.launch(Dispatchers.IO) {
                                try {
                                    val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                    val secondaryLang = settingsUseCase?.let { runCatching { it.get() }.getOrNull()?.secondaryLanguage } ?: selected?.primaryLanguage
                                    val vForSecondary = selected?.copy(selectedLanguage = secondaryLang ?: selected?.selectedLanguage ?: "")
                                    speechService.speak(phrase.text ?: "", vForSecondary)
                                } catch (_: Throwable) {}
                            }
                        },
                        onLongPress = { phrase ->
                            // open edit dialog for this phrase
                            editingPhrase = phrase
                            showEditDialog = true
                        },
                        onMove = { from, to -> bloc.dispatch(PhraseEvent.Move(from, to)) },
                        onSavePhrase = { phrase -> bloc.dispatch(PhraseEvent.Add(phrase)) },
                        onDeletePhrase = { phrase -> bloc.dispatch(PhraseEvent.Delete(phrase.id)) },
                        categories = categories
                    )

                    if (showEditDialog && editingPhrase != null) {
                        AddPhraseDialog(
                            onDismiss = { showEditDialog = false; editingPhrase = null },
                            categories = categories,
                            initialPhrase = editingPhrase,
                            onSave = { p -> bloc.dispatch(PhraseEvent.Edit(p)); showEditDialog = false; editingPhrase = null },
                            onDelete = { id -> bloc.dispatch(PhraseEvent.Delete(id)); showEditDialog = false; editingPhrase = null }
                        )
                    }
                }
            }

            if (showSettingsDialog) {
                AzureSettingsDialog(show = true, onDismiss = { showSettingsDialog = false }, onSaved = { showSettingsDialog = false })
            }
            if (showVoiceSelection) {
                VoiceSelectionDialog(show = true, onDismiss = { showVoiceSelection = false })
            }
            if (showUiLanguageDialog) {
                UiLanguageDialog(show = true, onDismiss = { showUiLanguageDialog = false })
            }
        }
    }
}