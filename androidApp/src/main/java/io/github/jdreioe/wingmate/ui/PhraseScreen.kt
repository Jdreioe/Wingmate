package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Settings

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.input.TextFieldValue
import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import org.koin.core.context.GlobalContext
import io.github.jdreioe.wingmate.application.VoiceUseCase
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import io.github.jdreioe.wingmate.application.CategoryUseCase
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhraseScreen() {
    // Ensure Koin is initialized
    require(GlobalContext.getOrNull() != null) { "Koin not initialized. Call initKoin() before starting the app." }
    val bloc = remember { GlobalContext.get().get<PhraseBloc>() }
    val state by bloc.state.collectAsState()

    LaunchedEffect(Unit) { bloc.dispatch(PhraseEvent.Load) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            var showVoiceSelection by remember { mutableStateOf(false) }
            var showLangDialog by remember { mutableStateOf(false) }

            // settings primary language state (move outside actions so dialog can access)
            val settingsUseCase = remember {
                runCatching {
                    GlobalContext.get()
                        .get<io.github.jdreioe.wingmate.application.SettingsUseCase>()
                }.getOrNull()
            }
            val primaryLanguageState =
                produceState(initialValue = "en-US", key1 = settingsUseCase) {
                    val s = settingsUseCase?.let { runCatching { it.get() }.getOrNull() }
                    value = s?.primaryLanguage ?: "en-US"
                }

            // selected voice / available languages for language selection
            val selectedVoiceState = produceState<io.github.jdreioe.wingmate.domain.Voice?>(initialValue = null, key1 = remember { runCatching { GlobalContext.get().get<VoiceUseCase>() }.getOrNull() }) {
                val voiceUseCase = runCatching { GlobalContext.get().get<VoiceUseCase>() }.getOrNull()
                value = runCatching { voiceUseCase?.selected() }.getOrNull()
            }
            val availableLangs = selectedVoiceState.value?.supportedLanguages ?: emptyList()
            val topBarScope = rememberCoroutineScope()

            LargeTopAppBar(title = {
                Text(
                    "Wingmate",
                    style = MaterialTheme.typography.headlineSmall
                )
            }, actions = {
                // primary UI language display / selector
                ElevatedButton(onClick = { showLangDialog = true }) {
                    Text(primaryLanguageState.value)
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    showVoiceSelection = true
                }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Voice settings"
                    )
                }
            }, colors = TopAppBarDefaults.largeTopAppBarColors())

            if (showVoiceSelection) {
                VoiceSelectionDialog(show = true, onDismiss = { showVoiceSelection = false })
            }

            // Language selection dialog (opened from top app bar)
            if (showLangDialog) {
                LanguageSelectionDialog(show = true, languages = availableLangs.ifEmpty { listOf(primaryLanguageState.value) }, selected = primaryLanguageState.value, onDismiss = { showLangDialog = false }, onSelect = { sel ->
                    topBarScope.launch {
                        try {
                            settingsUseCase?.let {
                                val current = runCatching { it.get() }.getOrNull()
                                if (current != null) {
                                    it.update(current.copy(primaryLanguage = sel))
                                }
                            }
                        } catch (_: Throwable) {}
                        showLangDialog = false
                    }
                })
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Text("Wingmate", color = MaterialTheme.colorScheme.onBackground)
                // Debug info: show which theme path is selected at runtime
                val context = LocalContext.current
                val configuration = context.resources.configuration
                val uiModeNight =
                    configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val runtimeMode = when (uiModeNight) {
                    android.content.res.Configuration.UI_MODE_NIGHT_YES -> "NIGHT_YES"
                    android.content.res.Configuration.UI_MODE_NIGHT_NO -> "NIGHT_NO"
                    else -> "UNKNOWN"
                }
                val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = "info")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Mode: $runtimeMode, dynamicColors=${dynamicSupported}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (state.loading) Text("Loading...")
                state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

                // Load categories from CategoryUseCase if available; fallback to phrases with isCategory flag
                val categoryUseCase = remember { runCatching { GlobalContext.get().get<CategoryUseCase>() }.getOrNull() }
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

                var selectedCategory by remember { mutableStateOf<CategoryItem?>(categories.firstOrNull()) }
                var showCategoryDialog by remember { mutableStateOf(false) }
                val uiScope = rememberCoroutineScope()
                Button(onClick = {
                    showCategoryDialog = true
                }) { Text(if (selectedCategory != null) "Category: ${selectedCategory?.name}" else "Select Category") }
                if (showCategoryDialog) {
                    val selectedVoiceState = produceState<io.github.jdreioe.wingmate.domain.Voice?>(initialValue = null, key1 = remember { runCatching { GlobalContext.get().get<VoiceUseCase>() }.getOrNull() }) {
                        val voiceUseCase = runCatching { GlobalContext.get().get<VoiceUseCase>() }.getOrNull()
                        value = runCatching { voiceUseCase?.selected() }.getOrNull()
                    }
                    val selectedVoice = selectedVoiceState.value
                    val availableLangs = selectedVoice?.supportedLanguages ?: emptyList()

                    // use persisted primary language label when possible
                    val settingsUseCase = remember { runCatching { GlobalContext.get().get<io.github.jdreioe.wingmate.application.SettingsUseCase>() }.getOrNull() }
                    val primaryLanguageState = produceState(initialValue = "en-US", key1 = settingsUseCase) {
                        val s = settingsUseCase?.let { runCatching { it.get() }.getOrNull() }
                        value = s?.primaryLanguage ?: "en-US"
                    }

                    CategorySelectorDialog(
                        languageLabel = primaryLanguageState.value,
                        categories = categories,
                        selected = selectedCategory,
                        onDismiss = { showCategoryDialog = false },
                        onCategorySelected = { c -> selectedCategory = c; showCategoryDialog = false },
                        onUpdateCategory = { updated ->
                            android.util.Log.i("PhraseScreen", "onUpdateCategory called for id='${updated.id}' name='${updated.name}' language='${updated.selectedLanguage}'")
                            if (categoryUseCase != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val saved = categoryUseCase.update(updated)
                                        val newList = runCatching { categoryUseCase.list() }.getOrNull() ?: emptyList()
                                        coroutineScope.launch { categories = newList; selectedCategory = newList.find { it.id == saved.id } }
                                        android.util.Log.i("PhraseScreen", "category updated id='${saved.id}' name='${saved.name}'")
                                    } catch (t: Throwable) { android.util.Log.i("PhraseScreen", "category update failed: ${t.localizedMessage}") }
                                }
                            } else {
                                android.util.Log.i("PhraseScreen", "no CategoryUseCase available to update category id='${updated.id}'")
                            }
                        },
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

                val speechService = remember {
                    GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SpeechService>()
                }
                val voiceUseCase = remember {
                    GlobalContext.get().get<io.github.jdreioe.wingmate.application.VoiceUseCase>()
                }

                // simple inline add now supports cursor insertion using TextFieldValue
                var input by remember { mutableStateOf(TextFieldValue("")) }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type text to speak") })
                Spacer(modifier = Modifier.height(8.dp))

                PhraseGrid(
                    phrases = state.items.filter { !it.isCategory },
                    onPlay = { phrase ->
                        uiScope.launch(Dispatchers.IO) {
                            try {
                                val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                speechService.speak(phrase.text ?: "", selected)
                            } catch (_: Throwable) {
                            }
                        }
                    },
                    onLongPress = { phrase ->
                        // open edit dialog
                        /* handled in PhraseGrid -> AddPhraseDialog integration */
                    },
                    onInsert = { phrase ->
                        val fv = input
                        val pos = fv.selection.start.coerceIn(0, fv.text.length)
                        val insertText = phrase.text ?: ""
                        val newText =
                            fv.text.substring(0, pos) + insertText + fv.text.substring(pos)
                        val newCursor = pos + insertText.length
                        input = TextFieldValue(
                            newText,
                            selection = androidx.compose.ui.text.TextRange(newCursor)
                        )
                    },
                    onMove = { from, to -> bloc.dispatch(PhraseEvent.Move(from, to)) },
                    onSavePhrase = { phrase -> bloc.dispatch(PhraseEvent.Add(phrase)) },
                    categories = categories
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Playback controls (connect to real player when available)
                PlaybackControls(onPlay = {
                    // play current input text
                    uiScope.launch(Dispatchers.IO) {
                        try {
                            // get selection and speak
                            val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                            speechService.speak(input.text, selected)
                        } catch (_: Throwable) {
                        }
                    }
                }, onPause = {
                    uiScope.launch { speechService.pause() }
                }, onStop = {
                    uiScope.launch { speechService.stop() }
                })


                // simple inline add (uses the same TextFieldValue `input` above)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val p = Phrase(
                            id = "",
                            text = input.text,
                            name = null,
                            backgroundColor = null,
                            parentId = null,
                            isCategory = false,
                            createdAt = 0L
                        )
                        bloc.dispatch(PhraseEvent.Add(p))
                        // clear the TextFieldValue while preserving stable selection state
                        input = TextFieldValue("")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Add", color = MaterialTheme.colorScheme.onPrimary)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Playback controls: platform-specific if implemented
            }
        }
    }
}