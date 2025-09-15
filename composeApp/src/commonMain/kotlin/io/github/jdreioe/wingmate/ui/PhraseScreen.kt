package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import org.koin.core.context.GlobalContext
import io.github.jdreioe.wingmate.application.SettingsUseCase
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var showOverflow by remember { mutableStateOf(false) }
    // fullscreen state managed via DisplayWindowBus; no local state needed

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
            var historyItems by remember { mutableStateOf<List<io.github.jdreioe.wingmate.domain.SaidText>>(emptyList()) }

            // Load history on start so the History category appears if there are existing items
            LaunchedEffect(saidRepo) {
                try {
                    val list = saidRepo.list()
                    historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L }
                } catch (_: Throwable) {
                    historyItems = emptyList()
                }
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text("Wingmate") },
                        actions = {
                            // Fullscreen toggle: mirrors the current input text
                            val showFullscreen by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsState()
                            IconButton(onClick = {
                                // Always mirror current text first
                                io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(input.text)
                                // Toggle the window state so it can be reopened reliably
                                if (showFullscreen) io.github.jdreioe.wingmate.presentation.DisplayWindowBus.close()
                                else io.github.jdreioe.wingmate.presentation.DisplayWindowBus.open()
                            }) { Icon(imageVector = Icons.Filled.Fullscreen, contentDescription = "Toggle fullscreen") }

                            // Overflow menu for the rest of actions
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                // Language quick access
                                DropdownMenuItem(
                                    text = { Text("Language: " + (selectedVoiceState.value?.selectedLanguage?.takeIf { it.isNotBlank() }
                                        ?: primaryLanguageState.value)) },
                                    onClick = { showOverflow = false; showUiLanguageDialog = true }
                                )
                                // Voice settings
                                DropdownMenuItem(
                                    text = { Text("Voice settings") },
                                    onClick = { showOverflow = false; showVoiceSelection = true }
                                )
                                // App settings
                                DropdownMenuItem(
                                    text = { Text("App settings") },
                                    onClick = { showOverflow = false; showSettingsDialog = true }
                                )
                                // Optional: back to welcome (if supported)
                                if (onBackToWelcome != null) {
                                    DropdownMenuItem(
                                        text = { Text("Welcome screen") },
                                        onClick = { showOverflow = false; onBackToWelcome.invoke() }
                                    )
                                }
                                // Copy last played audio for current text
                                DropdownMenuItem(
                                    text = { Text("Copy last soundfile") },
                                    onClick = {
                                        showOverflow = false
                                        uiScope.launch(Dispatchers.IO) {
                                            runCatching {
                                                val repo = GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
                                                val last = repo.list()
                                                    .filter { it.saidText == input.text && !it.audioFilePath.isNullOrBlank() }
                                                    .maxByOrNull { it.date ?: it.createdAt ?: 0L }
                                                val path = last?.audioFilePath
                                                if (!path.isNullOrBlank()) {
                                                    GlobalContext.get().get<io.github.jdreioe.wingmate.platform.AudioClipboard>()
                                                        .copyAudioFile(path)
                                                }
                                            }
                                        }
                                    }
                                )
                                    // Share last soundfile for current text
                                    DropdownMenuItem(
                                        text = { Text("Share last soundfile") },
                                        onClick = {
                                            showOverflow = false
                                            uiScope.launch(Dispatchers.IO) {
                                                runCatching {
                                                    val repo = GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
                                                    val last = repo.list()
                                                        .filter { it.saidText == input.text && !it.audioFilePath.isNullOrBlank() }
                                                        .maxByOrNull { it.date ?: it.createdAt ?: 0L }
                                                    val path = last?.audioFilePath
                                                    if (!path.isNullOrBlank()) {
                                                        GlobalContext.get().get<io.github.jdreioe.wingmate.platform.ShareService>()
                                                            .shareAudio(path)
                                                    }
                                                }
                                            }
                                        }
                                    )
                            }
                        }
                    )
                },
                bottomBar = {
                    // Make the playback bar less obvious by removing elevation and background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // omit imePadding in common to avoid ambiguity across targets
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
                                        // Refresh history from repo so the History chip appears after first save
                                        try {
                                            val list = saidRepo.list()
                                            uiScope.launch { historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                                        } catch (_: Throwable) {}
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
                                        val fallbackLang = selected?.selectedLanguage ?: ""
                                        val vForSecondary = selected?.copy(selectedLanguage = secondaryLang ?: fallbackLang)
                                        speechService.speak(input.text, vForSecondary, vForSecondary?.pitch, vForSecondary?.rate)
                                        // Refresh history from repo
                                        try {
                                            val list = saidRepo.list()
                                            uiScope.launch { historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                                        } catch (_: Throwable) {}
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

                    // Dynamically resolve CategoryUseCase; it might be registered after initial composition (platform overrides)
                    val categoryUseCaseState = remember { mutableStateOf<io.github.jdreioe.wingmate.application.CategoryUseCase?>(null) }
                    LaunchedEffect(Unit) {
                        // Retry until available (or stop after some attempts if desired)
                        repeat(30) {
                            if (categoryUseCaseState.value != null) return@repeat
                            categoryUseCaseState.value = runCatching { GlobalContext.get().get<io.github.jdreioe.wingmate.application.CategoryUseCase>() }.getOrNull()
                            if (categoryUseCaseState.value == null) delay(250)
                        }
                    }
                    var categories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
                    val HistoryCategoryId = remember { "__history__" }
                    val coroutineScope = rememberCoroutineScope()

                    // load initial categories
                    LaunchedEffect(categoryUseCaseState.value) {
                        val uc = categoryUseCaseState.value
                        categories = if (uc != null) {
                            runCatching { uc.list() }.getOrNull() ?: emptyList()
                        } else {
                            emptyList()
                        }
                    }

                    // Category selector with dialog
                    var selectedCategory by remember { mutableStateOf<CategoryItem?>(null) } // Start with no category selected (show all)
                    var showAddCategoryDialog by remember { mutableStateOf(false) }
                    var confirmDeleteCategory by remember { mutableStateOf<CategoryItem?>(null) }

                    // Large text input field that expands to fill available space
                    OutlinedTextField(
                        value = input, 
                        onValueChange = {
                            input = it
                            io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(it.text)
                        }, 
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 180.dp), // Fixed size based on 4-6 lines
                        placeholder = { Text("Enter text to speak") },
                        minLines = 4,
                        maxLines = 6
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    if (categoryUseCaseState.value == null) {
                        Text("(Loading categories backend...)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }

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
                        itemsIndexed(categories) { index, category ->
                            var showCategoryMenu by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    selected = selectedCategory?.id == category.id,
                                    onClick = { selectedCategory = category },
                                    label = { Text(category.name ?: "All") },
                                    modifier = Modifier.combinedClickable(
                                        onClick = { selectedCategory = category },
                                        onLongClick = { showCategoryMenu = true }
                                    )
                                )
                                DropdownMenu(expanded = showCategoryMenu, onDismissRequest = { showCategoryMenu = false }) {
                                    DropdownMenuItem(text = { Text("Move left") }, enabled = index > 0, onClick = {
                                        showCategoryMenu = false
                                        val uc = categoryUseCaseState.value
                                        if (index > 0 && uc != null) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                runCatching { uc.move(index, index - 1) }
                                                val updated = runCatching { uc.list() }.getOrNull() ?: emptyList()
                                                coroutineScope.launch { categories = updated }
                                            }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Move right") }, enabled = index < categories.lastIndex, onClick = {
                                        showCategoryMenu = false
                                        val uc = categoryUseCaseState.value
                                        if (index < categories.lastIndex && uc != null) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                runCatching { uc.move(index, index + 1) }
                                                val updated = runCatching { uc.list() }.getOrNull() ?: emptyList()
                                                coroutineScope.launch { categories = updated }
                                            }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Delete (with phrases)") }, onClick = {
                                        showCategoryMenu = false
                                        // Confirm dialog
                                        confirmDeleteCategory = category
                                    })
                                }
                            }
                        }
                        // History chip: appears only when there are items; placed immediately after user categories
                        if (historyItems.isNotEmpty()) {
                            item {
                                FilterChip(
                                    selected = selectedCategory?.id == HistoryCategoryId,
                                    onClick = { selectedCategory = CategoryItem(id = HistoryCategoryId, name = "History") },
                                    label = { Text("History") }
                                )
                            }
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

                        // Note: History chip is added above, before the Add chip
                    }

                    // Refresh history from repo when switching to History
                    LaunchedEffect(selectedCategory?.id) {
                        if (selectedCategory?.id == HistoryCategoryId) {
                            try {
                                val list = saidRepo.list()
                                historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L }
                            } catch (_: Throwable) {}
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
                                            val ucImmediate = categoryUseCaseState.value ?: runCatching { GlobalContext.get().get<io.github.jdreioe.wingmate.application.CategoryUseCase>() }.getOrNull()?.also { categoryUseCaseState.value = it }
                                            // Always create an ephemeral chip so user sees immediate feedback
                                            val temp = io.github.jdreioe.wingmate.domain.CategoryItem(id = "temp_${name}_${System.currentTimeMillis()}", name = name, selectedLanguage = primaryLanguageState.value)
                                            categories = categories + temp
                                            selectedCategory = temp
                                            coroutineScope.launch(Dispatchers.IO) {
                                                // Wait for a real use case if not yet available
                                                var uc = ucImmediate
                                                var attempts = 0
                                                while (uc == null && attempts < 40) { // up to ~10s
                                                    kotlinx.coroutines.delay(250)
                                                    uc = categoryUseCaseState.value ?: runCatching { GlobalContext.get().get<io.github.jdreioe.wingmate.application.CategoryUseCase>() }.getOrNull()?.also { categoryUseCaseState.value = it }
                                                    attempts++
                                                }
                                                if (uc != null) {
                                                    try {
                                                        val added = uc.add(temp.copy(id = ""))
                                                        val newList = runCatching { uc.list() }.getOrNull() ?: emptyList()
                                                        coroutineScope.launch {
                                                            categories = newList
                                                            selectedCategory = newList.find { it.id == added.id } ?: added
                                                        }
                                                    } catch (t: Throwable) {
                                                        // Roll back ephemeral on failure
                                                        coroutineScope.launch { categories = categories.filterNot { it.id == temp.id } }
                                                    }
                                                } else {
                                                    // Could not persist; mark temp visually by leaving it (user session only)
                                                }
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

                    // Confirm delete category cascade
                    if (confirmDeleteCategory != null) {
                        AlertDialog(
                            onDismissRequest = { confirmDeleteCategory = null },
                            title = { Text("Delete Category") },
                            text = { Text("Delete '${confirmDeleteCategory?.name}' and all phrases inside?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    val cat = confirmDeleteCategory
                                    confirmDeleteCategory = null
                                    if (cat != null) {
                                        val uc = categoryUseCaseState.value
                                        if (uc != null) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                // Delete phrases under this category (PhraseRepo)
                                                val phraseRepo = runCatching { GlobalContext.get().get<io.github.jdreioe.wingmate.domain.PhraseRepository>() }.getOrNull()
                                                val allPhrases = runCatching { phraseRepo?.getAll() }.getOrNull().orEmpty()
                                                val toDelete = allPhrases.filter { it.parentId == cat.id }
                                                toDelete.forEach { runCatching { phraseRepo?.delete(it.id) } }
                                                runCatching { uc.delete(cat.id) }
                                                val updated = runCatching { uc.list() }.getOrNull() ?: emptyList()
                                                coroutineScope.launch {
                                                    categories = updated
                                                    if (selectedCategory?.id == cat.id) selectedCategory = null
                                                }
                                            }
                                        }
                                    }
                                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = { TextButton(onClick = { confirmDeleteCategory = null }) { Text("Cancel") } }
                        )
                    }

                    // phrase grid below everything
                    var showEditDialog by remember { mutableStateOf(false) }
                    var editingPhrase by remember { mutableStateOf<Phrase?>(null) }
                    // Show only actual phrase items (not category markers), filtered by selected category
                    val isHistory = selectedCategory?.id == HistoryCategoryId
                    val visiblePhrases = if (isHistory) {
                        // Map history items to ephemeral Phrase objects to reuse the grid UI; hide Add tile for this view
            historyItems.mapIndexed { idx, s ->
                            Phrase(
                                id = "history_$idx",
                                text = s.saidText ?: "",
                                name = s.voiceName,
                                backgroundColor = null,
                                parentId = HistoryCategoryId,
                                isCategory = false,
                createdAt = s.date ?: s.createdAt ?: 0L,
                recordingPath = s.audioFilePath
                            )
                        }
                    } else {
                        state.items.filter { selectedCategory?.id == null || it.parentId == selectedCategory?.id }
                    }
                    PhraseGrid(
                        phrases = visiblePhrases,
                        onInsert = { phrase ->
                            // insert phrase.text at current cursor position
                            val fv = input
                            val pos = fv.selection.start.coerceIn(0, fv.text.length)
                            val insertText = phrase.text
                            val newText = fv.text.substring(0, pos) + insertText + fv.text.substring(pos)
                            val newCursor = pos + insertText.length
                            input = androidx.compose.ui.text.input.TextFieldValue(newText, selection = androidx.compose.ui.text.TextRange(newCursor))
                        },
                        onPlay = { phrase ->
                            uiScope.launch(Dispatchers.IO) {
                                try {
                                    val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                    speechService.speak(phrase.text, selected)
                                    // Refresh history from repo
                                    try {
                                        val list = saidRepo.list()
                                        uiScope.launch { historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                                    } catch (_: Throwable) {}
                                } catch (_: Throwable) {}
                            }
                        },
                        onPlaySecondary = { phrase ->
                            uiScope.launch(Dispatchers.IO) {
                                try {
                                    val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                    val secondaryLang = settingsUseCase?.let { runCatching { it.get() }.getOrNull()?.secondaryLanguage } ?: selected?.primaryLanguage
                                    val fallbackLang2 = selected?.selectedLanguage ?: ""
                                    val vForSecondary = selected?.copy(selectedLanguage = secondaryLang ?: fallbackLang2)
                                    speechService.speak(phrase.text, vForSecondary)
                                    // Refresh history from repo
                                    try {
                                        val list = saidRepo.list()
                                        uiScope.launch { historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                                    } catch (_: Throwable) {}
                                } catch (_: Throwable) {}
                            }
                        },
                        onLongPress = { phrase ->
                            if (!isHistory) {
                                // open edit dialog for this phrase
                                editingPhrase = phrase
                                showEditDialog = true
                            }
                        },
                        onMove = { from, to -> bloc.dispatch(PhraseEvent.Move(from, to)) },
                        onSavePhrase = { phrase -> bloc.dispatch(PhraseEvent.Add(phrase)) },
                        onDeletePhrase = { phrase -> bloc.dispatch(PhraseEvent.Delete(phrase.id)) },
                        categories = categories,
                        defaultCategoryId = selectedCategory?.id,
                        showAddTile = !isHistory,
                        readOnly = isHistory,
                        onCopyAudio = { filePath ->
                            // Try to copy soundfile via platform clipboard
                            runCatching {
                                val ac = GlobalContext.get().get<io.github.jdreioe.wingmate.platform.AudioClipboard>()
                                ac.copyAudioFile(filePath)
                            }
                        }
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
                // Full screen handled by platform window on desktop; on mobile we could add a dedicated screen later.
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