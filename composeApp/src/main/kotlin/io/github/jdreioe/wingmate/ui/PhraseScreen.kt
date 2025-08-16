package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.runtime.produceState
import javax.swing.UIManager
import java.awt.Color as AwtColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
fun PhraseScreen() {
    // Ensure Koin is initialized
    require(GlobalContext.getOrNull() != null) { "Koin not initialized. Call initKoin() before starting the app." }
    val bloc = remember { GlobalContext.get().get<PhraseBloc>() }
    val state by bloc.state.collectAsState()

    LaunchedEffect(Unit) { bloc.dispatch(PhraseEvent.Load) }

    // Add a small debug toggle to force light/dark for desktop testing
    var forceDark by remember { mutableStateOf<Boolean?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelection by remember { mutableStateOf(false) }
    var showUiLanguageDialog by remember { mutableStateOf(false) }

    DesktopTheme(useDark = forceDark) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Load persisted primary language for display in top bar
            val settingsUseCase = remember { runCatching { GlobalContext.get().get<SettingsUseCase>() }.getOrNull() }
            val primaryLanguageState = produceState(initialValue = "en-US", key1 = settingsUseCase) {
                val s = settingsUseCase?.let { runCatching { it.get() }.getOrNull() }
                value = s?.primaryLanguage ?: "en-US"
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    LargeTopAppBar(
                        title = { Text("Wingmate KMP Desktop", style = MaterialTheme.typography.headlineLarge) },
                        actions = {
                            // expressive language selector
                            ElevatedButton(onClick = { showUiLanguageDialog = true }) {
                                Text(primaryLanguageState.value)
                            }
                            Spacer(Modifier.width(12.dp))
                            IconButton(onClick = { showVoiceSelection = true }) {
                                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Voice settings")
                            }
                        },
                        colors = TopAppBarDefaults.largeTopAppBarColors()
                    )
                }
            ) { innerPadding ->
                Column(modifier = Modifier.padding(16.dp).padding(innerPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wingmate KMP Desktop")
                Spacer(modifier = Modifier.width(12.dp))
                // debug controls
                Button(onClick = { forceDark = null }, modifier = Modifier.padding(end = 4.dp)) { Text("System") }
                Button(onClick = { forceDark = false }, modifier = Modifier.padding(end = 4.dp)) { Text("Light") }
                Button(onClick = { forceDark = true }) { Text("Dark") }
            }
            // Desktop: detect UI background luminance via Swing UIManager and report it (helps debug theme selection)
            val runtimeMode = try {
                val awt = UIManager.getColor("Panel.background") ?: AwtColor(0xFF, 0xFF, 0xFF)
                val r = awt.red / 255f
                val g = awt.green / 255f
                val b = awt.blue / 255f
                val luminance = 0.299f * r + 0.587f * g + 0.114f * b
                if (luminance < 0.5f) "NIGHT_YES" else "NIGHT_NO"
            } catch (t: Throwable) { "UNKNOWN" }


            Spacer(modifier = Modifier.height(8.dp))
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

            // simple inline add (TextToSpeech input at top) — use TextFieldValue to track cursor/selection
            var input by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Enter text to speak") })
            Spacer(modifier = Modifier.height(8.dp))

            // Playback controls below the input
            val speechService = remember { GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SpeechService>() }
            val voiceUseCase = remember { GlobalContext.get().get<VoiceUseCase>() }
            val settingsUseCase = remember { runCatching { GlobalContext.get().get<SettingsUseCase>() }.getOrNull() }
            val uiScope = rememberCoroutineScope()
            PlaybackControls(onPlay = {
                if (input.text.isBlank()) return@PlaybackControls
                uiScope.launch(Dispatchers.IO) {
                    try {
                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                        speechService.speak(input.text, selected, selected?.pitch, selected?.rate)
                    } catch (t: Throwable) {
                        // swallow for UI; diagnostics logged by service
                    }
                }
            }, onPlaySecondary = {
                if (input.text.isBlank()) return@PlaybackControls
                uiScope.launch(Dispatchers.IO) {
                    try {
                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                        val secondaryLang = settingsUseCase?.let { runCatching { it.get() }.getOrNull()?.secondaryLanguage } ?: selected?.primaryLanguage
                        val vForSecondary = selected?.copy(selectedLanguage = secondaryLang ?: selected?.selectedLanguage ?: "")
                        speechService.speak(input.text, vForSecondary, vForSecondary?.pitch, vForSecondary?.rate)
                    } catch (t: Throwable) {
                    }
                }
            }, onPause = {
                uiScope.launch { speechService.pause() }
            }, onStop = { uiScope.launch { speechService.stop() } })

            Spacer(modifier = Modifier.height(8.dp))

            // Category selector with dialog below playback controls
            var selectedCategory by remember { mutableStateOf<CategoryItem?>(categories.firstOrNull()) }
            var showCategoryDialog by remember { mutableStateOf(false) }
            Button(onClick = { showCategoryDialog = true }) {
                if (selectedCategory != null) {
                    Text("Category: " + (selectedCategory?.name ?: ""))
                }
                else {
                    Text("Select Category")
                }
            }
            if (showCategoryDialog) {
                val selectedVoiceState = produceState<io.github.jdreioe.wingmate.domain.Voice?>(initialValue = null, key1 = voiceUseCase) {
                    value = runCatching { voiceUseCase.selected() }.getOrNull()
                }
                val selectedVoice = selectedVoiceState.value
                val availableLangs = selectedVoice?.supportedLanguages ?: emptyList()

                CategorySelectorDialog(
                    languageLabel = primaryLanguageState.value,
                    categories = categories,
                    selected = selectedCategory,
                    onDismiss = { showCategoryDialog = false },
                    onCategorySelected = { c -> selectedCategory = c; showCategoryDialog = false },
                    onAddCategory = { c ->
                        val name = c.name?.trim() ?: ""
                        if (name.isBlank()) return@CategorySelectorDialog
                        // local duplicate check
                        if (categories.any { (it.name ?: "").trim().equals(name, ignoreCase = true) }) return@CategorySelectorDialog
                        if (categoryUseCase != null) {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val toAdd = io.github.jdreioe.wingmate.domain.CategoryItem(id = "", name = name, selectedLanguage = c.selectedLanguage)
                                    val added = categoryUseCase.add(toAdd)
                                    val newList = runCatching { categoryUseCase.list() }.getOrNull() ?: emptyList()
                                    // update UI on main thread
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
                    },
                    availableLanguages = availableLangs
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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