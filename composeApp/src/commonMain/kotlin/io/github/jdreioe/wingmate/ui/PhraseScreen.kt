package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.*
import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.TextPredictionService
import org.koin.core.context.GlobalContext
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhraseScreen(onBackToWelcome: (() -> Unit)? = null) {
    // Ensure Koin is initialized
    require(GlobalContext.getOrNull() != null) { "Koin not initialized. Call initKoin() before starting the app." }
    val bloc = remember { GlobalContext.get().get<PhraseBloc>() }
    val state by bloc.state.collectAsState()

    // Ensure initial list loads on first composition
    LaunchedEffect(bloc) {
        bloc.dispatch(PhraseEvent.Load)
    }

    // Load settings for UI scaling using reactive state manager
    val settings by rememberReactiveSettings()
    
    val uiSettingsUseCase = remember {
        org.koin.core.context.GlobalContext.getOrNull()?.let { koin ->
            runCatching { koin.get<io.github.jdreioe.wingmate.application.SettingsUseCase>() }.getOrNull()
        }
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelection by remember { mutableStateOf(false) }
    var showUiLanguageDialog by remember { mutableStateOf(false) }
    var showDictionaryScreen by remember { mutableStateOf(false) }
    var showSsmlDialog by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    // fullscreen state managed via DisplayWindowBus; no local state needed

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Load persisted primary language for display in top bar
        val settingsUseCase = remember { runCatching { GlobalContext.get().get<SettingsUseCase>() }.getOrNull() }
        val primaryLanguageState = produceState(initialValue = "en-US", key1 = settingsUseCase) {
            val s = settingsUseCase?.let { runCatching { it.get() }.getOrNull() }
            value = s?.primaryLanguage ?: "en-US"
        }

            // Input state (hoisted so topBar History button can access it)
            var input by remember { mutableStateOf(TextFieldValue("")) }
            var secondaryLanguageRanges by remember { mutableStateOf<List<TextRange>>(emptyList()) }
            val textFieldFocusRequester = remember { FocusRequester() }
            val refocusInput = remember(textFieldFocusRequester) {
                { textFieldFocusRequester.requestFocus() }
            }

            // Dependencies for playback controls
            val speechService = remember { GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SpeechService>() }
            val saidRepo = remember { GlobalContext.get().get<io.github.jdreioe.wingmate.domain.SaidTextRepository>() }
            val voiceUseCase = remember { GlobalContext.get().get<VoiceUseCase>() }
            
            // Text prediction service (optional - may not be available on all platforms)
            val predictionService = remember { 
                runCatching { GlobalContext.get().get<TextPredictionService>() }.getOrNull() 
            }
            var predictions by remember { mutableStateOf(PredictionResult()) }

            // Speech service state tracking
            var isSpeechPaused by remember { mutableStateOf(false) }

            // Update speech state periodically
            LaunchedEffect(Unit) {
                while (true) {
                    isSpeechPaused = speechService.isPaused()
                    kotlinx.coroutines.delay(500) // Check every 500ms
                }
            }

            // selected voice / available languages for language selection
            val selectedVoiceState = produceState<io.github.jdreioe.wingmate.domain.Voice?>(initialValue = null, key1 = voiceUseCase) {
                value = runCatching { voiceUseCase.selected() }.getOrNull()
            }
            val uiScope = rememberCoroutineScope()
            var historyItems by remember { mutableStateOf<List<io.github.jdreioe.wingmate.domain.SaidText>>(emptyList()) }

            // Load history on start so the History category appears if there are existing items
            // Also train the prediction model on the history
            LaunchedEffect(saidRepo) {
                try {
                    val list = saidRepo.list()
                    println("DEBUG: Loaded ${list.size} history entries for prediction training")
                    historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L }
                    // Train prediction model on history
                    if (predictionService != null) {
                        predictionService.train(list)
                        println("DEBUG: Prediction model trained, isTrained=${predictionService.isTrained()}")
                    } else {
                        println("DEBUG: No prediction service available")
                    }
                } catch (e: Throwable) {
                    println("DEBUG: Error loading history: ${e.message}")
                    historyItems = emptyList()
                }
            }
            
            // Update predictions as user types
            LaunchedEffect(input.text) {
                if (predictionService == null || !predictionService.isTrained()) {
                    predictions = PredictionResult()
                    return@LaunchedEffect
                }
                // Small debounce to avoid excessive calls
                delay(100)
                predictions = predictionService.predict(input.text, maxWords = 5, maxLetters = 4)
            }


            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text("Wingmate", style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                        )) },
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
                                // === VOICE & SPEECH ===
                                DropdownMenuItem(
                                    text = { Text("Language: " + (selectedVoiceState.value?.selectedLanguage?.takeIf { it.isNotBlank() }
                                        ?: primaryLanguageState.value), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { showOverflow = false; showUiLanguageDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Voice settings", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { showOverflow = false; showVoiceSelection = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Pronunciation dictionary", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { showOverflow = false; showDictionaryScreen = true }
                                )
                                
                                Divider()
                                
                                // === AUDIO FILES ===
                                DropdownMenuItem(
                                    text = { Text("Copy last soundfile", style = MaterialTheme.typography.bodyMedium) },
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
                                DropdownMenuItem(
                                    text = { Text("Share last soundfile", style = MaterialTheme.typography.bodyMedium) },
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
                                
                                Divider()
                                
                                // === APP SETTINGS ===
                                DropdownMenuItem(
                                    text = { Text("App settings", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { showOverflow = false; showSettingsDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Check for updates", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { 
                                        showOverflow = false
                                        val updateService = runCatching { 
                                            GlobalContext.get().get<io.github.jdreioe.wingmate.domain.UpdateService>() 
                                        }.getOrNull()
                                        if (updateService != null) {
                                            uiScope.launch(Dispatchers.IO) {
                                                runCatching { updateService.checkForUpdates() }
                                            }
                                        }
                                    }
                                )
                                if (onBackToWelcome != null) {
                                    DropdownMenuItem(
                                        text = { Text("Welcome screen", style = MaterialTheme.typography.bodyMedium) },
                                        onClick = { showOverflow = false; onBackToWelcome.invoke() }
                                    )
                                }

                            }
                        }
                    )
                },
                bottomBar = {
                    // Make the playback bar less obvious by removing elevation and background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .platformImePadding()
                            // omit imePadding in common to avoid ambiguity across targets
                            .padding(16.dp)
                    ) {
                        val normalizedSelection = normalizeRange(input.selection, input.text.length)
                        val selectionHasLength = normalizedSelection.spanLength() > 0
                        val selectionAlreadySecondary = selectionHasLength && isRangeFullySecondary(normalizedSelection, secondaryLanguageRanges)
                        PlaybackControls(
                            onPlay = {
                                if (input.text.isBlank()) {
                                    refocusInput()
                                    return@PlaybackControls
                                }
                                uiScope.launch(Dispatchers.IO) {
                                    try {
                                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                        val secondaryLang = settingsUseCase?.let { runCatching { it.get() }.getOrNull()?.secondaryLanguage }
                                        
                                        val hasSSML = input.text.contains("<") && input.text.contains(">")
                                        
                                        // When SSML is present, bypass segmentation and speak directly
                                        if (hasSSML) {
                                            speechService.speak(input.text, selected, selected?.pitch, selected?.rate)
                                        } else {
                                            val segments = if (secondaryLanguageRanges.isNotEmpty()) {
                                                buildLanguageAwareSegments(input.text, secondaryLanguageRanges, secondaryLang)
                                            } else emptyList()
                                            if (segments.isNotEmpty()) {
                                                speechService.speakSegments(segments, selected, selected?.pitch, selected?.rate)
                                            } else {
                                                speechService.speak(input.text, selected, selected?.pitch, selected?.rate)
                                            }
                                        }
                                        
                                        // Refresh history from repo so the History chip appears after first save
                                        // Also retrain prediction model with new data
                                        try {
                                            val list = saidRepo.list()
                                            uiScope.launch { historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                                            predictionService?.train(list)
                                        } catch (_: Throwable) {}
                                    } catch (t: Throwable) {
                                        // swallow for UI; diagnostics logged by service
                                    }
                                }
                                refocusInput()
                            },
                            onPause = {
                                uiScope.launch { speechService.pause() }
                                refocusInput()
                            },
                            onStop = { 
                                uiScope.launch { speechService.stop() }
                                refocusInput()
                            },
                            onResume = {
                                uiScope.launch { speechService.resume() }
                                refocusInput()
                            },
                            isPaused = isSpeechPaused,
                            onPlaySecondary = {
                                if (!selectionHasLength) {
                                    refocusInput()
                                    return@PlaybackControls
                                }
                                secondaryLanguageRanges = toggleSecondaryRange(
                                    secondaryLanguageRanges,
                                    normalizedSelection,
                                    input.text.length
                                )
                                refocusInput()
                            },
                            isSecondarySelectionActive = selectionAlreadySecondary,
                            isSecondaryActionEnabled = selectionHasLength
                        )
                    }
                }
            ) { innerPadding ->
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    val isWide = maxWidth >= 900.dp
                    Row(Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    if (state.loading) Text("Loading...", style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                    ))
                    state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                    )) }

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

                    val secondaryHighlightColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                    SecondaryLanguageTextField(
                        value = input,
                        onValueChange = { newValue ->
                            val previous = input
                            secondaryLanguageRanges = adjustRangesAfterEdit(previous.text, newValue.text, secondaryLanguageRanges)
                            input = newValue
                            io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(newValue.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = (120.dp * settings.inputFieldScale), max = (180.dp * settings.inputFieldScale)),
                        focusRequester = textFieldFocusRequester,
                        highlightRanges = secondaryLanguageRanges,
                        highlightColor = secondaryHighlightColor,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        minLines = 4,
                        maxLines = 6,
                        placeholder = {
                            Text(
                                "Enter text to speak",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    )
                    
                    // On narrow screens, if keyboard is active, show prediction bar instead of SSML button
                    val isKeyboardVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
                    
                    if (!isWide && isKeyboardVisible && (predictions.words.isNotEmpty() || predictions.letters.isNotEmpty())) {
                         PredictionBar(
                            predictions = predictions,
                            onWordSelected = { word ->
                                val fv = input
                                val text = fv.text
                                val cursorPos = fv.selection.start.coerceIn(0, text.length)
                                val wordStart = text.lastIndexOf(' ', cursorPos - 1) + 1
                                val partialWord = text.substring(wordStart, cursorPos)
                                val newText = if (partialWord.isNotEmpty() && word.lowercase().startsWith(partialWord.lowercase())) {
                                    text.substring(0, wordStart) + word + " " + text.substring(cursorPos)
                                } else {
                                    text.substring(0, cursorPos) + (if (cursorPos > 0 && text[cursorPos - 1] != ' ') " " else "") + word + " " + text.substring(cursorPos)
                                }
                                val newCursor = if (partialWord.isNotEmpty() && word.lowercase().startsWith(partialWord.lowercase())) {
                                    wordStart + word.length + 1
                                } else {
                                    cursorPos + (if (cursorPos > 0 && text[cursorPos - 1] != ' ') 1 else 0) + word.length + 1
                                }
                                secondaryLanguageRanges = adjustRangesAfterEdit(text, newText, secondaryLanguageRanges)
                                input = TextFieldValue(newText, selection = TextRange(newCursor.coerceAtMost(newText.length)))
                                io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(newText)
                            },
                            onLetterSelected = { letter ->
                                val fv = input
                                val pos = fv.selection.start.coerceIn(0, fv.text.length)
                                val newText = fv.text.substring(0, pos) + letter + fv.text.substring(pos)
                                val newCursor = pos + 1
                                secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, newText, secondaryLanguageRanges)
                                input = TextFieldValue(newText, selection = TextRange(newCursor))
                                io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(newText)
                            },
                            fontSizeScale = settings.fontSizeScale,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else if (!isWide) {
                        OutlinedButton(
                            onClick = { showSsmlDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Text("SSML Controls", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    
                    // Word and letter prediction bar in original position (only if keyboard is NOT active on narrow screens, or always on wide screens)
                    if ((isWide || !isKeyboardVisible) && (predictions.words.isNotEmpty() || predictions.letters.isNotEmpty())) {
                        PredictionBar(
                            predictions = predictions,
                            onWordSelected = { word ->
                                val fv = input
                                val text = fv.text
                                val cursorPos = fv.selection.start.coerceIn(0, text.length)
                                
                                // Find start of current word being typed
                                val wordStart = text.lastIndexOf(' ', cursorPos - 1) + 1
                                val partialWord = text.substring(wordStart, cursorPos)
                                
                                // If we have a partial word and the suggestion starts with it, complete it
                                val newText = if (partialWord.isNotEmpty() && word.lowercase().startsWith(partialWord.lowercase())) {
                                    text.substring(0, wordStart) + word + " " + text.substring(cursorPos)
                                } else {
                                    // Otherwise, insert word at cursor with space
                                    text.substring(0, cursorPos) + (if (cursorPos > 0 && text[cursorPos - 1] != ' ') " " else "") + word + " " + text.substring(cursorPos)
                                }
                                val newCursor = if (partialWord.isNotEmpty() && word.lowercase().startsWith(partialWord.lowercase())) {
                                    wordStart + word.length + 1
                                } else {
                                    cursorPos + (if (cursorPos > 0 && text[cursorPos - 1] != ' ') 1 else 0) + word.length + 1
                                }
                                secondaryLanguageRanges = adjustRangesAfterEdit(text, newText, secondaryLanguageRanges)
                                input = TextFieldValue(newText, selection = TextRange(newCursor.coerceAtMost(newText.length)))
                                io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(newText)
                            },
                            onLetterSelected = { letter ->
                                val fv = input
                                val pos = fv.selection.start.coerceIn(0, fv.text.length)
                                val newText = fv.text.substring(0, pos) + letter + fv.text.substring(pos)
                                val newCursor = pos + 1
                                secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, newText, secondaryLanguageRanges)
                                input = TextFieldValue(newText, selection = TextRange(newCursor))
                                io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(newText)
                            },
                            fontSizeScale = settings.fontSizeScale
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    if (categoryUseCaseState.value == null) {
                        Text("(Loading categories backend...)", style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * settings.fontSizeScale
                        ), color = MaterialTheme.colorScheme.outline)
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
                                label = { Text("All", style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                )) }
                            )
                        }
                        
                        // Category chips
                        itemsIndexed(categories) { index, category ->
                            var showCategoryMenu by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    selected = selectedCategory?.id == category.id,
                                    onClick = { selectedCategory = category },
                                    label = { Text(category.name ?: "All", style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) },
                                    modifier = Modifier.combinedClickable(
                                        onClick = { selectedCategory = category },
                                        onLongClick = { showCategoryMenu = true }
                                    )
                                )
                                DropdownMenu(expanded = showCategoryMenu, onDismissRequest = { showCategoryMenu = false }) {
                                    DropdownMenuItem(text = { Text("Move left", style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) }, enabled = index > 0, onClick = {
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
                                    DropdownMenuItem(text = { Text("Move right", style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) }, enabled = index < categories.lastIndex, onClick = {
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
                                    DropdownMenuItem(text = { Text("Delete (with phrases)", style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) }, onClick = {
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
                                    label = { Text("History", style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) }
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
                                            modifier = Modifier.size((16.dp * settings.playbackIconScale))
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add", style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                        ))
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
                            title = { Text("Add Category", style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                            )) },
                            text = {
                                OutlinedTextField(
                                    value = categoryName,
                                    onValueChange = { categoryName = it },
                                    placeholder = { Text("Category name", style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) },
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
                                    Text("Add", style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                    ))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { 
                                    showAddCategoryDialog = false
                                    categoryName = ""
                                }) {
                                    Text("Cancel", style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                    ))
                                }
                            }
                        )
                    }

                    // Confirm delete category cascade
                    if (confirmDeleteCategory != null) {
                        AlertDialog(
                            onDismissRequest = { confirmDeleteCategory = null },
                            title = { Text("Delete Category", style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                            )) },
                            text = { Text("Delete '${confirmDeleteCategory?.name}' and all phrases inside?", style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                            )) },
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
                                }) { Text("Delete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                )) }
                            },
                            dismissButton = { TextButton(onClick = { confirmDeleteCategory = null }) { Text("Cancel", style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                            )) } }
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
                            secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, newText, secondaryLanguageRanges)
                            input = TextFieldValue(newText, selection = TextRange(newCursor))
                            io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(newText)
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
                        phraseFontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
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
                        if (isWide) {
                            SsmlSidebar(
                                modifier = Modifier.width(320.dp).fillMaxHeight().padding(12.dp),
                                inputText = input.text,
                                inputSelection = input.selection,
                                onInsertSsml = { ssmlMarkup ->
                                    val fv = input
                                    val pos = fv.selection.start.coerceIn(0, fv.text.length)
                                    val newText = fv.text.substring(0, pos) + ssmlMarkup + fv.text.substring(pos)
                                    val newCursor = pos + ssmlMarkup.length
                                    secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, newText, secondaryLanguageRanges)
                                    input = TextFieldValue(newText, selection = TextRange(newCursor))
                                    io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(newText)
                                }
                            )
                        }
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
            if (showDictionaryScreen) {
                val dictionaryRepo = remember { GlobalContext.get().get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>() }
                val scope = rememberCoroutineScope()
                var entries by remember { mutableStateOf<List<io.github.jdreioe.wingmate.domain.PronunciationEntry>>(emptyList()) }
                
                LaunchedEffect(showDictionaryScreen) {
                    if (showDictionaryScreen) {
                        entries = dictionaryRepo.getAll()
                    }
                }
                
                DictionaryScreen(
                    entries = entries,
                    onAddEntry = { word, phoneme, alphabet ->
                        scope.launch {
                            dictionaryRepo.add(io.github.jdreioe.wingmate.domain.PronunciationEntry(word, phoneme, alphabet))
                            entries = dictionaryRepo.getAll()
                        }
                    },
                    onDeleteEntry = { entry ->
                        scope.launch {
                            dictionaryRepo.delete(entry.word)
                            entries = dictionaryRepo.getAll()
                        }
                    },
                    onTestEntry = { word, phoneme, alphabet ->
                        scope.launch {
                            val testSsml = "<phoneme alphabet=\"$alphabet\" ph=\"$phoneme\">$word</phoneme>"
                            val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                            speechService.speak(testSsml, selected, selected?.pitch, selected?.rate)
                        }
                    },
                    onGuessPronunciation = { word ->
                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                        val lang = selected?.primaryLanguage ?: "en"
                        speechService.guessPronunciation(word, lang)
                    },
                    onBack = { showDictionaryScreen = false }
                )
            }
            
            if (showSsmlDialog) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showSsmlDialog = false }
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.85f),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column {
                            // Dialog title bar with close button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "SSML Controls",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { showSsmlDialog = false }) {
                                    Icon(Icons.Default.ArrowBack, "Close")
                                }
                            }
                            
                            Divider()
                            
                            // SSML Sidebar content
                            SsmlSidebar(
                                modifier = Modifier.weight(1f),
                                inputText = input.text,
                                inputSelection = input.selection,
                                onInsertSsml = { ssmlText ->
                                    val cursorPos = input.selection.start.coerceIn(0, input.text.length)
                                    val newText = input.text.substring(0, cursorPos) + ssmlText + input.text.substring(cursorPos)
                                    val newCursor = cursorPos + ssmlText.length
                                    secondaryLanguageRanges = adjustRangesAfterEdit(input.text, newText, secondaryLanguageRanges)
                                    input = TextFieldValue(newText, selection = TextRange(newCursor))
                                    io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(newText)
                                }
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun SecondaryLanguageTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    highlightRanges: List<TextRange> = emptyList(),
    highlightColor: Color,
    textStyle: TextStyle,
    placeholder: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE
) {
    val annotated: AnnotatedString = remember(value.text, highlightRanges, highlightColor) {
        buildAnnotatedString {
            append(value.text)
            highlightRanges.sortedBy { it.start }.forEach { range ->
                val start = range.start.coerceIn(0, value.text.length)
                val end = range.end.coerceIn(0, value.text.length)
                if (start < end) {
                    addStyle(SpanStyle(background = highlightColor), start, end)
                }
            }
        }
    }

    // Wrap the plain TextFieldValue with our annotated string for display
    val styledValue = value.copy(annotatedString = annotated)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            if (value.text.isEmpty()) {
                placeholder?.invoke()
            }

            val inputModifier = if (focusRequester != null) {
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            } else {
                Modifier.fillMaxWidth()
            }

            BasicTextField(
                value = styledValue,
                onValueChange = { 
                    // Pass the plain text back to the parent to keep the logic simple there
                    onValueChange(it.copy(annotatedString = AnnotatedString(it.text)))
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = inputModifier,
                minLines = minLines,
                maxLines = maxLines
            )
        }
    }
}

private fun normalizeRange(range: TextRange, maxLength: Int): TextRange {
    val start = range.start.coerceIn(0, maxLength)
    val end = range.end.coerceIn(0, maxLength)
    return if (start <= end) TextRange(start, end) else TextRange(end, start)
}

private fun TextRange.spanLength(): Int = (end - start).coerceAtLeast(0)

private fun isRangeFullySecondary(selection: TextRange, ranges: List<TextRange>): Boolean {
    if (selection.spanLength() == 0) return false
    var cursor = selection.start
    val sorted = ranges.sortedBy { it.start }
    var index = 0
    while (cursor < selection.end) {
        while (index < sorted.size && sorted[index].end <= cursor) {
            index++
        }
        if (index >= sorted.size) return false
        val range = sorted[index]
        if (cursor < range.start) return false
        cursor = minOf(selection.end, range.end)
    }
    return true
}

private fun toggleSecondaryRange(
    ranges: List<TextRange>,
    selection: TextRange,
    textLength: Int
): List<TextRange> {
    val normalized = normalizeRange(selection, textLength)
    if (normalized.spanLength() == 0) return ranges
    val cleaned = clampRanges(ranges, textLength)
    return if (isRangeFullySecondary(normalized, cleaned)) {
        subtractRange(cleaned, normalized)
    } else {
        mergeRanges(cleaned + normalized)
    }
}

private fun adjustRangesAfterEdit(oldText: String, newText: String, ranges: List<TextRange>): List<TextRange> {
    if (ranges.isEmpty()) return emptyList()
    if (oldText == newText) return clampRanges(ranges, newText.length)
    val prefix = commonPrefixLength(oldText, newText)
    val suffix = commonSuffixLength(oldText, newText, prefix)
    val oldChangedEnd = oldText.length - suffix
    val newChangedEnd = newText.length - suffix
    val delta = newChangedEnd - oldChangedEnd

    val updated = mutableListOf<TextRange>()
    ranges.sortedBy { it.start }.forEach { range ->
        when {
            range.end <= prefix -> updated.add(range)
            range.start >= oldChangedEnd -> updated.add(TextRange(range.start + delta, range.end + delta))
            else -> {
                if (range.start < prefix) {
                    updated.add(TextRange(range.start, prefix))
                }
                if (range.end > oldChangedEnd) {
                    updated.add(TextRange(oldChangedEnd + delta, range.end + delta))
                }
            }
        }
    }
    return clampRanges(updated, newText.length)
}

private fun clampRanges(ranges: List<TextRange>, maxLength: Int): List<TextRange> {
    if (ranges.isEmpty()) return emptyList()
    return ranges.mapNotNull { range ->
        val start = range.start.coerceIn(0, maxLength)
        val end = range.end.coerceIn(0, maxLength)
        if (start < end) TextRange(start, end) else null
    }.sortedBy { it.start }
}

private fun subtractRange(ranges: List<TextRange>, removal: TextRange): List<TextRange> {
    if (ranges.isEmpty()) return emptyList()
    val result = mutableListOf<TextRange>()
    ranges.forEach { range ->
        if (removal.end <= range.start || removal.start >= range.end) {
            result.add(range)
        } else {
            if (removal.start > range.start) {
                result.add(TextRange(range.start, removal.start))
            }
            if (removal.end < range.end) {
                result.add(TextRange(removal.end, range.end))
            }
        }
    }
    return result.filter { it.spanLength() > 0 }
}

private fun mergeRanges(ranges: List<TextRange>): List<TextRange> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.start }
    val merged = mutableListOf<TextRange>()
    var current = sorted.first()
    for (index in 1 until sorted.size) {
        val candidate = sorted[index]
        if (candidate.start <= current.end) {
            current = TextRange(current.start, maxOf(current.end, candidate.end))
        } else {
            if (current.spanLength() > 0) merged.add(current)
            current = candidate
        }
    }
    if (current.spanLength() > 0) merged.add(current)
    return merged
}

private fun commonPrefixLength(a: String, b: String): Int {
    val minLen = minOf(a.length, b.length)
    var idx = 0
    while (idx < minLen && a[idx] == b[idx]) idx++
    return idx
}

private fun commonSuffixLength(a: String, b: String, prefix: Int): Int {
    val aRemaining = a.length - prefix
    val bRemaining = b.length - prefix
    val maxLen = minOf(aRemaining, bRemaining)
    var count = 0
    while (count < maxLen && a[a.length - 1 - count] == b[b.length - 1 - count]) {
        count++
    }
    return count
}

private val PauseTagRegex = Regex("""<(?:pause|break)(?:\\s+(?:duration|time)=["']([^"']+)["'])?[^>]*/>""", RegexOption.IGNORE_CASE)

private fun buildLanguageAwareSegments(
    rawText: String,
    markedRanges: List<TextRange>,
    secondaryLanguage: String?
): List<SpeechSegment> {
    if (rawText.isBlank()) return emptyList()
    if (markedRanges.isEmpty()) return SpeechTextProcessor.processText(rawText)

    val normalizedRanges = clampRanges(markedRanges, rawText.length)
    val segments = mutableListOf<SpeechSegment>()
    var cursor = 0

    PauseTagRegex.findAll(rawText).forEach { match ->
        val before = rawText.substring(cursor, match.range.first)
        segments += chunkWithLanguage(before, cursor, normalizedRanges, secondaryLanguage)
        val duration = parseDuration(match.groupValues.getOrNull(1))
        segments += SpeechSegment(text = "", pauseDurationMs = duration)
        cursor = match.range.last + 1
    }

    val tail = rawText.substring(cursor)
    segments += chunkWithLanguage(tail, cursor, normalizedRanges, secondaryLanguage)

    return segments.filter { it.text.isNotBlank() || it.pauseDurationMs > 0 }
}

private fun chunkWithLanguage(
    chunk: String,
    offset: Int,
    ranges: List<TextRange>,
    secondaryLanguage: String?
): List<SpeechSegment> {
    if (chunk.isEmpty()) return emptyList()
    val result = mutableListOf<SpeechSegment>()
    var buffer = StringBuilder()
    var currentState: Boolean? = null
    var rangeIndex = 0
    var activeRange = ranges.getOrNull(rangeIndex)

    fun flush(state: Boolean?) {
        if (buffer.isEmpty()) return
        val textPart = buffer.toString()
        val processed = SpeechTextProcessor.processText(textPart)
        val lang = if (state == true) secondaryLanguage else null
        processed.forEach { segment ->
            val languageOverride = segment.languageTag ?: lang
            result.add(segment.copy(languageTag = languageOverride))
        }
        buffer = StringBuilder()
    }

    chunk.forEachIndexed { index, c ->
        val absoluteIndex = offset + index
        while (activeRange != null && absoluteIndex >= activeRange!!.end) {
            rangeIndex++
            activeRange = ranges.getOrNull(rangeIndex)
        }
        val isSecondary = activeRange?.let { absoluteIndex >= it.start && absoluteIndex < it.end } ?: false
        if (currentState == null) currentState = isSecondary
        if (isSecondary != currentState) {
            flush(currentState)
            currentState = isSecondary
        }
        buffer.append(c)
    }

    flush(currentState)
    return result
}

private fun parseDuration(durationStr: String?): Long {
    if (durationStr.isNullOrBlank()) return 500L
    val clean = durationStr.trim().lowercase()
    return when {
        clean.endsWith("ms") -> clean.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: 500L
        clean.endsWith("s") -> {
            val seconds = clean.removeSuffix("s").toDoubleOrNull() ?: 0.5
            (seconds * 1000).toLong()
        }
        else -> clean.toDoubleOrNull()?.toLong() ?: 500L
    }
}