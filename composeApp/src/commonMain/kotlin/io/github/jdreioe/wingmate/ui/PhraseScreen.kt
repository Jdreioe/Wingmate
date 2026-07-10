package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_language
import wingmatekmp.composeapp.generated.resources.phrase_screen_app_settings
import wingmatekmp.composeapp.generated.resources.phrase_screen_boardset_manager
import wingmatekmp.composeapp.generated.resources.phrase_screen_check_updates
import wingmatekmp.composeapp.generated.resources.phrase_screen_close_board
import wingmatekmp.composeapp.generated.resources.phrase_screen_copy_last_soundfile
import wingmatekmp.composeapp.generated.resources.phrase_screen_enter_text_placeholder
import wingmatekmp.composeapp.generated.resources.phrase_screen_error
import wingmatekmp.composeapp.generated.resources.phrase_screen_import_board
import wingmatekmp.composeapp.generated.resources.phrase_screen_import_export_data
import wingmatekmp.composeapp.generated.resources.phrase_screen_loading
import wingmatekmp.composeapp.generated.resources.phrase_screen_load_sample_board
import wingmatekmp.composeapp.generated.resources.phrase_screen_menu_cd
import wingmatekmp.composeapp.generated.resources.phrase_screen_pronunciation_dictionary
import wingmatekmp.composeapp.generated.resources.phrase_screen_select_board_title
import wingmatekmp.composeapp.generated.resources.phrase_screen_share_last_soundfile
import wingmatekmp.composeapp.generated.resources.phrase_screen_ssml_controls
import wingmatekmp.composeapp.generated.resources.phrase_screen_toggle_fullscreen_cd
import wingmatekmp.composeapp.generated.resources.phrase_screen_voice_settings
import wingmatekmp.composeapp.generated.resources.phrase_screen_welcome_screen

private data class ThoughtDraft(
    val input: TextFieldValue,
    val secondaryLanguageRanges: List<TextRange>,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun PhraseScreen(
    onBackToWelcome: (() -> Unit)? = null,
    onOpenBoardSetManager: (() -> Unit)? = null,
    initialBoardId: String? = null
) {
    val koin = getKoin()
    val bloc = koinInject<PhraseBloc>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val state by bloc.state.collectAsStateWithLifecycle()

    // Ensure initial list loads on first composition
    LaunchedEffect(bloc) {
        bloc.dispatch(PhraseEvent.Load)
    }

    // Load settings for UI scaling using reactive state manager
    val settings by rememberReactiveSettings()

    val speechService = koinInject<io.github.jdreioe.wingmate.domain.SpeechService>()
    val saidRepo = koinInject<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val aacLogger = koinInject<io.github.jdreioe.wingmate.domain.AacLogger>()
    val boardRepo = koinInject<io.github.jdreioe.wingmate.domain.BoardRepository>()
    val obfParser = koinInject<io.github.jdreioe.wingmate.infrastructure.ObfParser>()
    val dictionaryRepo = koinInject<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>()
    val predictionService = remember(koin) { koin.getOrNull<TextPredictionService>() }
    val dictionaryLoader = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.DictionaryLoader>() }
    val updateService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.domain.UpdateService>() }
    val filePicker = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.FilePicker>() }
    val phraseRepo = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.domain.PhraseRepository>() }
    val audioClipboard = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.AudioClipboard>() }
    val shareService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.ShareService>() }
    val enableObfObzImport = !isReleaseBuild()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelection by remember { mutableStateOf(false) }
    var showUiLanguageDialog by remember { mutableStateOf(false) }
    var showDictionaryScreen by remember { mutableStateOf(false) }
    var showSettingsExportDialog by remember { mutableStateOf(false) }
    var showSsmlDialog by remember { mutableStateOf(false) }
    val showFullscreen by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsStateWithLifecycle()
    val selectBoardDialogTitle = stringResource(Res.string.phrase_screen_select_board_title)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Load persisted primary language for display in top e
        // Use the reactive settings as key to ensure this updates when settings change
        val primaryLanguageState = produceState(initialValue = settings.primaryLanguage, key1 = settings.primaryLanguage) {
            value = settings.primaryLanguage
        }

            // Input state (hoisted so topBar History button can access it)
            var input by remember { mutableStateOf(TextFieldValue("")) }
            var secondaryLanguageRanges by remember { mutableStateOf<List<TextRange>>(emptyList()) }
            var pinnedThoughtDraft by remember { mutableStateOf<ThoughtDraft?>(null) }
            var scratchThoughtDraft by remember { mutableStateOf<ThoughtDraft?>(null) }
            val textFieldFocusRequester = remember { FocusRequester() }
            val refocusInput = remember(textFieldFocusRequester) {
                { textFieldFocusRequester.requestFocus() }
            }
            val syncDisplayText = remember(showFullscreen) {
                { text: String ->
                    if (showFullscreen) {
                        io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(text)
                    }
                }
            }
            var predictions by remember { mutableStateOf(PredictionResult()) }

            // Speech service state tracking
            var isSpeechPaused by remember(speechService) { mutableStateOf(speechService.isPaused()) }

            // Polling this every 500ms caused avoidable background wakeups while typing.
            // Keep it infrequent and let control actions update state immediately.
            LaunchedEffect(speechService) {
                while (true) {
                    val paused = speechService.isPaused()
                    if (paused != isSpeechPaused) {
                        isSpeechPaused = paused
                    }
                    val pollDelay = if (speechService.isPlaying()) 1000L else 4000L
                    kotlinx.coroutines.delay(pollDelay)
                }
            }

            // selected voice / available languages for language selection
            val selectedVoiceState = produceState<io.github.jdreioe.wingmate.domain.Voice?>(initialValue = null, key1 = voiceUseCase) {
                value = runCatching { voiceUseCase.selected() }.getOrNull()
            }
            val uiScope = rememberCoroutineScope()
            var historyItems by remember { mutableStateOf<List<io.github.jdreioe.wingmate.domain.SaidText>>(emptyList()) }
            
            // OBF Board State
            var currentBoard by remember { mutableStateOf<ObfBoard?>(null) }
            // Map of all boards (ID -> Board) for linking support in OBZ files
            var boardsMap by remember { mutableStateOf<Map<String, ObfBoard>>(emptyMap()) }
            // Navigation stack for going back to previous boards
            var boardStack by remember { mutableStateOf<List<ObfBoard>>(emptyList()) }
            // Extracted images from OBZ (path -> bytes)
            var extractedImages by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
            
            // Selected buttons for Symbol Bar
            var selectedObfButtons by remember { mutableStateOf<List<Pair<ObfButton, ImageBitmap?>>>(emptyList()) }

            LaunchedEffect(initialBoardId, boardRepo) {
                if (initialBoardId.isNullOrBlank()) return@LaunchedEffect
                val board = withContext(Dispatchers.IO) { boardRepo.getBoard(initialBoardId) }
                if (board != null) {
                    currentBoard = board
                    boardsMap = mapOf(board.id to board)
                    boardStack = emptyList()
                    extractedImages = emptyMap()
                }
            }

            // Track model version to re-trigger predictions when training finishes
            var predictionModelVersion by remember { mutableStateOf(0) }

            // Load history on start so the History category appears if there are existing items
            // Also train the prediction model on the history
            LaunchedEffect(saidRepo, primaryLanguageState.value) {
                try {
                    val list = saidRepo.list()
                    historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L }
                    
                    // Train prediction model: first load base language dictionary, then user history
                    val ngramService = predictionService as? io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
                    if (ngramService != null) {
                        if (dictionaryLoader != null) {
                            val dictWords = dictionaryLoader.loadDictionary(primaryLanguageState.value)
                            if (dictWords.isNotEmpty()) {
                                ngramService.setBaseLanguage(dictWords)
                                // History trained on TOP of dictionary, so don't clear
                                ngramService.train(list, clear = false)
                            } else {
                                // No dictionary (or failed), so standard train (clears old data)
                                ngramService.train(list)
                            }
                        } else {
                            ngramService.train(list)
                        }
                        
                        predictionModelVersion++ // Trigger update
                    } else if (predictionService != null) {
                        predictionService.train(list)
                        predictionModelVersion++
                    }
                } catch (e: Throwable) {
                    historyItems = emptyList()
                }
            }
            
            // Update predictions as user types or model retrains.
            // Debounce + minimum token length avoids running n-gram inference on every keypress.
            LaunchedEffect(predictionService, predictionModelVersion) {
                if (predictionService == null || !predictionService.isTrained()) {
                    predictions = PredictionResult()
                    return@LaunchedEffect
                }

                snapshotFlow { input.text }
                    .debounce(250)
                    .distinctUntilChanged()
                    .collectLatest { currentText ->
                        val activeTokenLength = currentText
                            .trimEnd()
                            .substringAfterLast(' ', "")
                            .length

                        val shouldPredict = currentText.isNotBlank() &&
                            (currentText.lastOrNull() == ' ' || activeTokenLength >= 2)

                        if (!shouldPredict) {
                            predictions = PredictionResult()
                            return@collectLatest
                        }

                        predictions = predictionService.predict(currentText, maxWords = 5, maxLetters = 4)
                    }
            }


            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    var showOverflow by remember { mutableStateOf(false) }
                    TopAppBar(
                        title = { Text("Wingmate", style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                        )) },
                        actions = {
                            // Fullscreen toggle: mirrors the current input text
                            IconButton(onClick = {
                                // Always mirror current text first
                                io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(input.text)
                                // Toggle the window state so it can be reopened reliably
                                if (showFullscreen) io.github.jdreioe.wingmate.presentation.DisplayWindowBus.close()
                                else io.github.jdreioe.wingmate.presentation.DisplayWindowBus.open()
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.FULLSCREEN_TOGGLE,
                                    "enabled" to (!showFullscreen).toString()
                                )
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Fullscreen,
                                    contentDescription = stringResource(Res.string.phrase_screen_toggle_fullscreen_cd)
                                )
                            }

                            // Overflow menu for the rest of actions
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.phrase_screen_menu_cd))
                            }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                // === SPEECH TOOLS ===
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.phrase_screen_pronunciation_dictionary), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        showOverflow = false
                                        showDictionaryScreen = true
                                        featureUsageReporter.reportEvent(
                                            FeatureUsageEvents.SETTINGS_UPDATED,
                                            "action" to "open_pronunciation_dictionary"
                                        )
                                    }
                                )
                                
                                Divider()
                                
                                // === AUDIO FILES ===
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.phrase_screen_copy_last_soundfile), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        showOverflow = false
                                        uiScope.launch(Dispatchers.IO) {
                                            runCatching {
                                                val last = saidRepo.list()
                                                    .filter { it.saidText == input.text && !it.audioFilePath.isNullOrBlank() }
                                                    .maxByOrNull { it.date ?: it.createdAt ?: 0L }
                                                val path = last?.audioFilePath
                                                if (!path.isNullOrBlank()) {
                                                    audioClipboard?.copyAudioFile(path)
                                                }
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.phrase_screen_share_last_soundfile), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        showOverflow = false
                                        uiScope.launch(Dispatchers.IO) {
                                            runCatching {
                                                val last = saidRepo.list()
                                                    .filter { it.saidText == input.text && !it.audioFilePath.isNullOrBlank() }
                                                    .maxByOrNull { it.date ?: it.createdAt ?: 0L }
                                                val path = last?.audioFilePath
                                                if (!path.isNullOrBlank()) {
                                                    shareService?.shareAudio(path)
                                                }
                                            }
                                        }
                                    }
                                )
                                
                                Divider()
                                
                                // === APP SETTINGS ===
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.phrase_screen_app_settings), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        showOverflow = false
                                        showSettingsDialog = true
                                        featureUsageReporter.reportEvent(
                                            FeatureUsageEvents.SETTINGS_UPDATED,
                                            "action" to "open_app_settings"
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.phrase_screen_import_export_data), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        showOverflow = false
                                        showSettingsExportDialog = true
                                        featureUsageReporter.reportEvent(
                                            FeatureUsageEvents.SETTINGS_UPDATED,
                                            "action" to "open_import_export"
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.phrase_screen_check_updates), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { 
                                        showOverflow = false
                                        featureUsageReporter.reportEvent(
                                            FeatureUsageEvents.SETTINGS_UPDATED,
                                            "action" to "check_updates"
                                        )
                                        if (updateService != null) {
                                            uiScope.launch(Dispatchers.IO) {
                                                runCatching { updateService.checkForUpdates() }
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.phrase_screen_welcome_screen), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        showOverflow = false
                                        featureUsageReporter.reportEvent(
                                            FeatureUsageEvents.SCREEN_VIEW,
                                            "screen" to "welcome"
                                        )
                                        onBackToWelcome?.invoke()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.phrase_screen_boardset_manager), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        showOverflow = false
                                        featureUsageReporter.reportEvent(
                                            FeatureUsageEvents.SCREEN_VIEW,
                                            "screen" to "boardsets"
                                        )
                                        onOpenBoardSetManager?.invoke()
                                    }
                                )
                                
                                Divider()

                                if (enableObfObzImport) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (currentBoard == null) {
                                                    stringResource(Res.string.phrase_screen_load_sample_board)
                                                } else {
                                                    stringResource(Res.string.phrase_screen_close_board)
                                                },
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        onClick = { 
                                            showOverflow = false
                                            if (currentBoard == null) {
                                                uiScope.launch {
                                                    // Load a sample board for demonstration
                                                    val sampleJson = """
                                                        {
                                                          "format": "open-board-0.1",
                                                          "id": "sample_1",
                                                          "name": "Sample Board",
                                                          "grid": { "rows": 3, "columns": 2, "order": [["b1", "b2"], ["b3", "b4"], ["b5", "b6"]] },
                                                          "buttons": [
                                                            {"id": "b1", "label": "Hello", "background_color": "#ffcccc"},
                                                            {"id": "b2", "label": "How are you?", "background_color": "#ccffcc"},
                                                            {"id": "b3", "label": "Yes", "background_color": "#ccccff"},
                                                            {"id": "b4", "label": "No", "background_color": "#ffffcc"},
                                                            {"id": "b5", "label": "Thank you", "background_color": "#ffccff"},
                                                            {"id": "b6", "label": "Please", "background_color": "#ccffff"}
                                                          ]
                                                        }
                                                    """.trimIndent()
                                                    val boardRes = obfParser.parseBoard(sampleJson)
                                                    if (boardRes.isSuccess) {
                                                        val board = boardRes.getOrThrow()
                                                        boardRepo.saveBoard(board)
                                                        currentBoard = board
                                                        featureUsageReporter.reportEvent(
                                                            FeatureUsageEvents.BOARD_IMPORT_COMPLETED,
                                                            "mode" to "sample_board"
                                                        )
                                                    }
                                                }
                                            } else {
                                                currentBoard = null
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.phrase_screen_import_board), style = MaterialTheme.typography.bodyMedium) },
                                        onClick = { 
                                            showOverflow = false
                                            uiScope.launch(Dispatchers.IO) {
                                                if (filePicker != null) {
                                                    val path = filePicker.pickFile(selectBoardDialogTitle, listOf("obf", "obz", "json"))
                                                    if (path != null) {
                                                        val isObz = path.lowercase().endsWith(".obz")
                                                        featureUsageReporter.reportEvent(
                                                            FeatureUsageEvents.BOARD_IMPORT_STARTED,
                                                            "mode" to if (isObz) "obz" else "obf"
                                                        )
                                                        if (isObz) {
                                                            // Handle OBZ (zip archive)
                                                            runCatching {
                                                                val zipFile = java.util.zip.ZipFile(path)
                                                                // Read manifest.json to find root board
                                                                val manifestEntry = zipFile.getEntry("manifest.json")
                                                                if (manifestEntry != null) {
                                                                    val manifestContent = zipFile.getInputStream(manifestEntry).bufferedReader().readText()
                                                                    val manifest = obfParser.parseManifest(manifestContent).getOrNull()
                                                                    if (manifest != null) {
                                                                        // Load ALL boards from manifest into map
                                                                        val loadedBoards = mutableMapOf<String, ObfBoard>()
                                                                        manifest.paths.boards.forEach { (boardId, boardPath) ->
                                                                            val entry = zipFile.getEntry(boardPath)
                                                                            if (entry != null) {
                                                                                val content = zipFile.getInputStream(entry).bufferedReader().readText()
                                                                                obfParser.parseBoard(content).getOrNull()?.let { board ->
                                                                                    loadedBoards[boardId] = board
                                                                                }
                                                                            }
                                                                        }
                                                                        // Extract ALL images from manifest
                                                                        val loadedImages = mutableMapOf<String, ByteArray>()
                                                                        manifest.paths.images.forEach { (imageId, imagePath) ->
                                                                            val entry = zipFile.getEntry(imagePath)
                                                                            if (entry != null) {
                                                                                val bytes = zipFile.getInputStream(entry).readBytes()
                                                                                loadedImages[imagePath] = bytes
                                                                            }
                                                                        }
                                                                        // Also load root board if not in paths
                                                                        val rootEntry = zipFile.getEntry(manifest.root)
                                                                        if (rootEntry != null) {
                                                                            val boardContent = zipFile.getInputStream(rootEntry).bufferedReader().readText()
                                                                            val boardRes = obfParser.parseBoard(boardContent)
                                                                            if (boardRes.isSuccess) {
                                                                                val board = boardRes.getOrThrow()
                                                                                loadedBoards[board.id] = board
                                                                                boardRepo.saveBoard(board)
                                                                                uiScope.launch { 
                                                                                    boardsMap = loadedBoards
                                                                                    boardStack = emptyList()
                                                                                    extractedImages = loadedImages
                                                                                    currentBoard = board 
                                                                                }
                                                                                featureUsageReporter.reportEvent(
                                                                                    FeatureUsageEvents.BOARD_IMPORT_COMPLETED,
                                                                                    "mode" to "obz"
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                zipFile.close()
                                                            }
                                                        } else {
                                                            // Handle plain OBF file
                                                            val content = filePicker.readFileAsText(path)
                                                            if (content != null) {
                                                                val boardRes = obfParser.parseBoard(content)
                                                                if (boardRes.isSuccess) {
                                                                    val board = boardRes.getOrThrow()
                                                                    boardRepo.saveBoard(board)
                                                                    uiScope.launch { currentBoard = board }
                                                                    featureUsageReporter.reportEvent(
                                                                        FeatureUsageEvents.BOARD_IMPORT_COMPLETED,
                                                                        "mode" to "obf"
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
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
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.PLAYBACK_PLAY,
                                    "source" to "input",
                                    "has_secondary_ranges" to secondaryLanguageRanges.isNotEmpty().toString()
                                )
                                isSpeechPaused = false
                                uiScope.launch(Dispatchers.IO) {
                                    try {
                                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                        val secondaryLang = settings.secondaryLanguage
                                        val inputText = input.text
                                        
                                        val hasSSML = inputText.contains("<") && inputText.contains(">")
                                        val canUseRecordedMix = !hasSSML && secondaryLanguageRanges.isEmpty()
                                        val playedRecording = if (canUseRecordedMix) {
                                            runCatching {
                                                trySpeakUsingRecordedPhrases(
                                                    inputText = inputText,
                                                    phrases = state.items,
                                                    speechService = speechService,
                                                    voice = selected
                                                )
                                            }.getOrDefault(false)
                                        } else {
                                            false
                                        }
                                        
                                        if (!playedRecording) {
                                            // When SSML is present, bypass segmentation and speak directly
                                            if (hasSSML) {
                                                speechService.speak(inputText, selected, selected?.pitch, selected?.rate)
                                            } else {
                                                val segments = if (secondaryLanguageRanges.isNotEmpty()) {
                                                    buildLanguageAwareSegments(inputText, secondaryLanguageRanges, secondaryLang)
                                                } else emptyList()
                                                if (segments.isNotEmpty()) {
                                                    speechService.speakSegments(segments, selected, selected?.pitch, selected?.rate)
                                                } else {
                                                    speechService.speak(inputText, selected, selected?.pitch, selected?.rate)
                                                }
                                            }
                                        }
                                        
                                        // Refresh history from repo so the History chip appears after first save
                                        // Also train prediction model incrementally with new phrase
                                        try {
                                            val list = saidRepo.list()
                                            uiScope.launch { historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                                            // Incremental learning for immediate feedback
                                            (predictionService as? io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService)?.learnPhrase(input.text)
                                            predictionModelVersion++ // Trigger update after new entry
                                        } catch (_: Throwable) {}
                                    } catch (t: Throwable) {
                                        // swallow for UI; diagnostics logged by service
                                    }
                                }
                                refocusInput()
                            },
                            onPause = {
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.PLAYBACK_PAUSE,
                                    "source" to "input"
                                )
                                isSpeechPaused = true
                                uiScope.launch {
                                    runCatching { speechService.pause() }
                                        .onFailure { isSpeechPaused = speechService.isPaused() }
                                }
                                refocusInput()
                            },
                            onStop = { 
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.PLAYBACK_STOP,
                                    "source" to "input"
                                )
                                isSpeechPaused = false
                                uiScope.launch {
                                    runCatching { speechService.stop() }
                                        .onFailure { isSpeechPaused = speechService.isPaused() }
                                }
                                refocusInput()
                            },
                            onResume = {
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.PLAYBACK_RESUME,
                                    "source" to "input"
                                )
                                isSpeechPaused = false
                                uiScope.launch {
                                    runCatching { speechService.resume() }
                                        .onFailure { isSpeechPaused = speechService.isPaused() }
                                }
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
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.PLAYBACK_SECONDARY_TOGGLE,
                                    "enabled" to (!selectionAlreadySecondary).toString()
                                )
                                refocusInput()
                            },
                            onThatThought = {
                                val activeDraft = ThoughtDraft(
                                    input = input,
                                    secondaryLanguageRanges = secondaryLanguageRanges
                                )

                                if (pinnedThoughtDraft == null) {
                                    pinnedThoughtDraft = activeDraft
                                    val draftToLoad = scratchThoughtDraft
                                        ?: ThoughtDraft(TextFieldValue(""), emptyList())
                                    input = draftToLoad.input
                                    secondaryLanguageRanges = draftToLoad.secondaryLanguageRanges
                                    featureUsageReporter.reportEvent(
                                        FeatureUsageEvents.PLAYBACK_ON_THAT_THOUGHT,
                                        "action" to "pin"
                                    )
                                } else {
                                    scratchThoughtDraft = activeDraft
                                    val restoredDraft = pinnedThoughtDraft ?: ThoughtDraft(TextFieldValue(""), emptyList())
                                    pinnedThoughtDraft = null
                                    input = restoredDraft.input
                                    secondaryLanguageRanges = restoredDraft.secondaryLanguageRanges
                                    featureUsageReporter.reportEvent(
                                        FeatureUsageEvents.PLAYBACK_ON_THAT_THOUGHT,
                                        "action" to "resume"
                                    )
                                }

                                syncDisplayText(input.text)
                                refocusInput()
                            },
                            isSecondarySelectionActive = selectionAlreadySecondary,
                            isSecondaryActionEnabled = selectionHasLength,
                            isOnThatThoughtActive = pinnedThoughtDraft != null
                        )
                    }
                }
            ) { innerPadding ->
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    val isWide = maxWidth >= 900.dp
                    Row(Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    if (state.loading) Text(stringResource(Res.string.phrase_screen_loading), style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                    ))
                    state.error?.let { Text(stringResource(Res.string.phrase_screen_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                    )) }

                    // Dynamically resolve CategoryUseCase; it might be registered after initial composition (platform overrides)
                    val categoryUseCaseState = remember { mutableStateOf<io.github.jdreioe.wingmate.application.CategoryUseCase?>(null) }
                    LaunchedEffect(Unit) {
                        // Retry until available (or stop after some attempts if desired)
                        repeat(30) {
                            if (categoryUseCaseState.value != null) return@LaunchedEffect
                            categoryUseCaseState.value = koin.getOrNull<io.github.jdreioe.wingmate.application.CategoryUseCase>()
                            if (categoryUseCaseState.value != null) return@LaunchedEffect
                            delay(250)
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
                    
                    val ssmlRanges = remember(input.text) {
                        Regex("\\[(\\d+(\\.\\d+)?)s\\]").findAll(input.text).map {
                            androidx.compose.ui.text.TextRange(it.range.first, it.range.last + 1)
                        }.toList()
                    }
                    val ssmlHighlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)

                    SecondaryLanguageTextField(
                        value = input,
                        onValueChange = { newValue ->
                            val previous = input
                            secondaryLanguageRanges = adjustRangesAfterEdit(previous.text, newValue.text, secondaryLanguageRanges)
                            input = newValue
                            syncDisplayText(newValue.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = (120.dp * settings.inputFieldScale), max = (180.dp * settings.inputFieldScale)),
                        focusRequester = textFieldFocusRequester,
                        highlightRanges = secondaryLanguageRanges,
                        highlightColor = secondaryHighlightColor,
                        ssmlRanges = ssmlRanges,
                        ssmlColor = ssmlHighlightColor,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        minLines = 4,
                        maxLines = 6,
                        placeholder = {
                            Text(
                                stringResource(Res.string.phrase_screen_enter_text_placeholder),
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
                                syncDisplayText(newText)
                            },
                            onLetterSelected = { letter ->
                                val fv = input
                                val pos = fv.selection.start.coerceIn(0, fv.text.length)
                                val newText = fv.text.substring(0, pos) + letter + fv.text.substring(pos)
                                val newCursor = pos + 1
                                secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, newText, secondaryLanguageRanges)
                                input = TextFieldValue(newText, selection = TextRange(newCursor))
                                syncDisplayText(newText)
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
                            Text(stringResource(Res.string.phrase_screen_ssml_controls), style = MaterialTheme.typography.bodyMedium)
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
                                syncDisplayText(newText)
                            },
                            onLetterSelected = { letter ->
                                val fv = input
                                val pos = fv.selection.start.coerceIn(0, fv.text.length)
                                val newText = fv.text.substring(0, pos) + letter + fv.text.substring(pos)
                                val newCursor = pos + 1
                                secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, newText, secondaryLanguageRanges)
                                input = TextFieldValue(newText, selection = TextRange(newCursor))
                                syncDisplayText(newText)
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
                        itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
                            var showCategoryMenu by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    selected = selectedCategory?.id == category.id,
                                    onClick = {
                                        if (selectedCategory?.id == category.id) {
                                            showCategoryMenu = true
                                        } else {
                                            selectedCategory = category
                                        }
                                    },
                                    label = { Text(category.name ?: "All", style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) },
                                    modifier = Modifier
                                        .then(
                                            if (isDesktop()) {
                                                Modifier.pointerInput(Unit) {
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            if (event.type == PointerEventType.Press &&
                                                                event.buttons.isSecondaryPressed) {
                                                                showCategoryMenu = true
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                if (selectedCategory?.id == category.id) {
                                                    showCategoryMenu = true
                                                } else {
                                                    selectedCategory = category
                                                }
                                            },
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
                                val showKeyboard = rememberShowKeyboardOnFocus()
                                OutlinedTextField(
                                    value = categoryName,
                                    onValueChange = { categoryName = it },
                                    placeholder = { Text("Category name", style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) },
                                    singleLine = true,
                                    modifier = Modifier.then(showKeyboard)
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val name = categoryName.trim()
                                        if (name.isNotBlank() && !categories.any { it.name.equals(name, ignoreCase = true) }) {
                                            val ucImmediate = categoryUseCaseState.value ?: koin.getOrNull<io.github.jdreioe.wingmate.application.CategoryUseCase>()?.also { categoryUseCaseState.value = it }
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
                                                    uc = categoryUseCaseState.value ?: koin.getOrNull<io.github.jdreioe.wingmate.application.CategoryUseCase>()?.also { categoryUseCaseState.value = it }
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
                    val selectedCategoryId = selectedCategory?.id
                    val visiblePhrases by remember(isHistory, historyItems, state.items, selectedCategoryId) {
                        derivedStateOf {
                            if (isHistory) {
                                // Map history items to ephemeral Phrase objects to reuse the grid UI; hide Add tile for this view
                                historyItems.mapIndexed { idx, s ->
                                    val stableHistoryId = s.id?.toString() ?: (s.date ?: s.createdAt ?: idx.toLong()).toString()
                                    Phrase(
                                        id = "history_$stableHistoryId",
                                        text = s.saidText ?: "",
                                        name = s.voiceName,
                                        backgroundColor = null,
                                        parentId = HistoryCategoryId,
                                        createdAt = s.date ?: s.createdAt ?: 0L,
                                        recordingPath = s.audioFilePath
                                    )
                                }
                            } else {
                                state.items.filter { selectedCategoryId == null || it.parentId == selectedCategoryId }
                            }
                        }
                    }
                    PhraseGrid(
                        phrases = visiblePhrases,
                        onInsert = { phrase ->
                            // insert phrase.name (vocalization) or phrase.text (label) at current cursor position
                            val fv = input
                            val pos = fv.selection.start.coerceIn(0, fv.text.length)
                            val insertText = phrase.name?.ifBlank { null } ?: phrase.text
                            val newText = fv.text.substring(0, pos) + insertText + fv.text.substring(pos)
                            val newCursor = pos + insertText.length
                            secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, newText, secondaryLanguageRanges)
                            input = TextFieldValue(newText, selection = TextRange(newCursor))
                            syncDisplayText(newText)
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.PHRASE_INSERTED,
                                "source" to if (phrase.parentId == HistoryCategoryId) "history" else "grid"
                            )
                        },
                        onPlay = { phrase ->
                            // Classic Folder Navigation: if item has a linked board, entering it updates the view
                            if (phrase.linkedBoardId != null) {
                                uiScope.launch {
                                    selectedCategory = io.github.jdreioe.wingmate.domain.CategoryItem(
                                        id = phrase.id,
                                        name = phrase.text,
                                        isFolder = true
                                    )
                                }
                            } else {
                                uiScope.launch(Dispatchers.IO) {
                                    try {
                                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                        val textToSpeak = phrase.name?.ifBlank { null } ?: phrase.text
                                        val playedRecorded = phrase.recordingPath?.let { path ->
                                            runCatching {
                                                speechService.speakRecordedAudio(
                                                    audioFilePath = path,
                                                    textForHistory = textToSpeak,
                                                    voice = selected
                                                )
                                            }.getOrDefault(false)
                                        } ?: false
                                        if (!playedRecorded) {
                                            speechService.speak(textToSpeak, selected)
                                        }
                                        featureUsageReporter.reportEvent(
                                            FeatureUsageEvents.PHRASE_PLAYED,
                                            "source" to "grid",
                                            "used_recording" to playedRecorded.toString()
                                        )
                                        // Refresh history from repo
                                        try {
                                            val list = saidRepo.list()
                                            uiScope.launch { historyItems = list.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                                        } catch (_: Throwable) {}
                                    } catch (_: Throwable) {}
                                }
                            }
                        },
                        onPlaySecondary = { phrase ->
                            uiScope.launch(Dispatchers.IO) {
                                try {
                                    val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                    val secondaryLang = settings.secondaryLanguage
                                    val fallbackLang2 = selected?.selectedLanguage ?: ""
                                    val vForSecondary = selected?.copy(selectedLanguage = secondaryLang ?: fallbackLang2)
                                    val textToSpeak = phrase.name?.ifBlank { null } ?: phrase.text
                                    val playedRecorded = phrase.recordingPath?.let { path ->
                                        runCatching {
                                            speechService.speakRecordedAudio(
                                                audioFilePath = path,
                                                textForHistory = textToSpeak,
                                                voice = vForSecondary
                                            )
                                        }.getOrDefault(false)
                                    } ?: false
                                    if (!playedRecorded) {
                                        speechService.speak(textToSpeak, vForSecondary)
                                    }
                                    featureUsageReporter.reportEvent(
                                        FeatureUsageEvents.PHRASE_PLAYED_SECONDARY,
                                        "source" to "grid",
                                        "used_recording" to playedRecorded.toString()
                                    )
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
                                audioClipboard?.copyAudioFile(filePath)
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

                if (currentBoard != null) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Top bar with board name and navigation buttons
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left side: Back button (if stacked) and board name
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (boardStack.isNotEmpty()) {
                                        IconButton(onClick = { 
                                            currentBoard = boardStack.last()
                                            boardStack = boardStack.dropLast(1)
                                        }) {
                                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                        }
                                    }
                                    Text("${currentBoard?.name ?: "Board"}", style = MaterialTheme.typography.titleMedium)
                                }
                                // Right side: Erase and Home buttons
                                Row {
                                    IconButton(onClick = { 
                                        input = TextFieldValue("")
                                        syncDisplayText("")
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Erase")
                                    }
                                    IconButton(onClick = { 
                                        currentBoard = null
                                        boardsMap = emptyMap()
                                        boardStack = emptyList()
                                    }) {
                                        Icon(Icons.Default.Home, contentDescription = "Home")
                                    }
                                }
                            }
                            
                            // Textfield showing accumulated text
                            val boardShowKeyboard = rememberShowKeyboardOnFocus()
                            OutlinedTextField(
                                value = input,
                                onValueChange = { newValue ->
                                    input = newValue
                                    syncDisplayText(newValue.text)
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).then(boardShowKeyboard),
                                placeholder = { Text("Tap buttons to build a sentence...") },
                                trailingIcon = {
                                    if (input.text.isNotEmpty()) {
                                        IconButton(onClick = {
                                            // Speak the entire text
                                            uiScope.launch(Dispatchers.IO) {
                                                val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                                val inputText = input.text
                                                val playedRecording = runCatching {
                                                    trySpeakUsingRecordedPhrases(
                                                        inputText = inputText,
                                                        phrases = state.items,
                                                        speechService = speechService,
                                                        voice = selected
                                                    )
                                                }.getOrDefault(false)
                                                if (!playedRecording) {
                                                    speechService.speak(inputText, selected)
                                                }
                                            }
                                        }) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Speak")
                                        }
                                    }
                                },
                                singleLine = false,
                                maxLines = 3
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Board grid
                            ObfBoardView(
                                board = currentBoard!!,
                                extractedImages = extractedImages,
                                selectedButtons = selectedObfButtons,
                                onButtonClick = { button ->
                                    // Check if this is a linking button
                                    val loadBoard = button.loadBoard
                                    if (loadBoard != null) {
                                        // Try to find the linked board by ID or path
                                        val linkedBoard = loadBoard.id?.let { boardsMap[it] }
                                            ?: loadBoard.path?.let { path -> 
                                                boardsMap.values.find { it.id == path.removeSuffix(".obf") }
                                            }
                                        if (linkedBoard != null) {
                                            boardStack = boardStack + currentBoard!!
                                            currentBoard = linkedBoard
                                        }
                                    } else {
                                        // Normal button - speak and append text
                                        val textToSpeak = button.vocalization ?: button.label
                                        if (!textToSpeak.isNullOrBlank()) {
                                            // Append to main input text field for consistency
                                            val newText = if (input.text.isEmpty()) textToSpeak else "${input.text} $textToSpeak"
                                            input = TextFieldValue(newText, selection = TextRange(newText.length))
                                            
                                            // Append to Symbol Bar list
                                            selectedObfButtons = selectedObfButtons + (button to null)
                                            
                                            uiScope.launch(Dispatchers.IO) {
                                                val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                                val playedRecording = runCatching {
                                                    trySpeakUsingRecordedPhrases(
                                                        inputText = textToSpeak,
                                                        phrases = state.items,
                                                        speechService = speechService,
                                                        voice = selected
                                                    )
                                                }.getOrDefault(false)
                                                if (!playedRecording) {
                                                    speechService.speak(textToSpeak, selected)
                                                }
                                            }
                                            syncDisplayText(newText)
                                        }
                                    }
                                },
                                onSpeakSentence = {
                                    if (input.text.isNotBlank()) {
                                        aacLogger.logSentenceSpeak(input.text)
                                        uiScope.launch(Dispatchers.IO) {
                                            val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                            speechService.speak(input.text, selected)
                                        }
                                    }
                                },
                                onDeleteLast = {
                                    if (selectedObfButtons.isNotEmpty()) {
                                        val last = selectedObfButtons.last().first
                                        val textToRemove = last.vocalization ?: last.label ?: ""
                                        selectedObfButtons = selectedObfButtons.dropLast(1)
                                        
                                        val currentText = input.text.trim()
                                        val newText = if (currentText.endsWith(textToRemove)) {
                                            currentText.removeSuffix(textToRemove).trim()
                                        } else {
                                            currentText.substringBeforeLast(" ").trim()
                                        }
                                        input = TextFieldValue(newText, selection = TextRange(newText.length))
                                        syncDisplayText(newText)
                                    } else if (input.text.isNotEmpty()) {
                                        val newText = input.text.dropLast(1)
                                        input = TextFieldValue(newText, selection = TextRange(newText.length))
                                        syncDisplayText(newText)
                                    }
                                },
                                onClearSentence = {
                                    selectedObfButtons = emptyList()
                                    input = TextFieldValue("")
                                    syncDisplayText("")
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                    }
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
                                    syncDisplayText(newText)
                                }
                            )
                        }
                    }
                }
            }

            if (showSettingsDialog) {
                SettingsScreen(onDismiss = { showSettingsDialog = false }, onSaved = { showSettingsDialog = false })
            }
            if (showVoiceSelection) {
                VoiceSelectionDialog(show = true, onDismiss = { showVoiceSelection = false })
            }
            if (showUiLanguageDialog) {
                UiLanguageDialog(
                    show = true,
                    onDismiss = { showUiLanguageDialog = false },
                    openPrimaryMenuInitially = true
                )
            }
            if (showDictionaryScreen) {
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
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.DICTIONARY_ENTRY_ADDED,
                                "alphabet" to alphabet
                            )
                        }
                    },
                    onDeleteEntry = { entry ->
                        scope.launch {
                            dictionaryRepo.delete(entry.word)
                            entries = dictionaryRepo.getAll()
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.DICTIONARY_ENTRY_DELETED,
                                "alphabet" to entry.alphabet
                            )
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
                                    syncDisplayText(newText)
                                }
                            )
                        }
                    }
                }
            }

            if (showSettingsExportDialog) {
                SettingsExportDialog(
                    onDismiss = { showSettingsExportDialog = false }
                )
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
    ssmlRanges: List<TextRange> = emptyList(),
    ssmlColor: Color = MaterialTheme.colorScheme.primaryContainer,
    textStyle: TextStyle,
    placeholder: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE
) {
    val annotated: AnnotatedString = remember(value.text, highlightRanges, highlightColor, ssmlRanges, ssmlColor) {
        buildAnnotatedString {
            append(value.text)
            highlightRanges.sortedBy { it.start }.forEach { range ->
                val start = range.start.coerceIn(0, value.text.length)
                val end = range.end.coerceIn(0, value.text.length)
                if (start < end) {
                    addStyle(SpanStyle(background = highlightColor), start, end)
                }
            }
            ssmlRanges.sortedBy { it.start }.forEach { range ->
                val start = range.start.coerceIn(0, value.text.length)
                val end = range.end.coerceIn(0, value.text.length)
                if (start < end) {
                    addStyle(SpanStyle(background = ssmlColor, fontWeight = FontWeight.Bold), start, end)
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

            val showKeyboardMod = rememberShowKeyboardOnFocus()
            val inputModifier = if (focusRequester != null) {
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .then(showKeyboardMod)
            } else {
                Modifier.fillMaxWidth().then(showKeyboardMod)
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

private data class RecordedPhraseEntry(
    val phraseId: String,
    val spokenText: String,
    val audioPath: String
)

private sealed interface MixedPlaybackChunk {
    data class Recorded(val entry: RecordedPhraseEntry) : MixedPlaybackChunk
    data class Text(val text: String) : MixedPlaybackChunk
}

private suspend fun trySpeakUsingRecordedPhrases(
    inputText: String,
    phrases: List<Phrase>,
    speechService: io.github.jdreioe.wingmate.domain.SpeechService,
    voice: io.github.jdreioe.wingmate.domain.Voice?
): Boolean {
    val normalizedInput = inputText.trim()
    if (normalizedInput.isEmpty()) return false

    val recordedEntries = phrases.mapNotNull { phrase ->
        val spoken = phraseSpokenText(phrase) ?: return@mapNotNull null
        val path = phrase.recordingPath?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        RecordedPhraseEntry(
            phraseId = phrase.id,
            spokenText = spoken,
            audioPath = path
        )
    }
    if (recordedEntries.isEmpty()) return false

    val plan = buildMixedPlaybackPlan(normalizedInput, recordedEntries) ?: return false
    return playMixedPlan(plan, speechService, voice)
}

private fun phraseSpokenText(phrase: Phrase): String? {
    val spoken = (phrase.name?.ifBlank { null } ?: phrase.text).trim()
    return spoken.ifBlank { null }
}

private fun buildMixedPlaybackPlan(
    inputText: String,
    entries: List<RecordedPhraseEntry>
): List<MixedPlaybackChunk>? {
    if (entries.isEmpty()) return null

    val matchPool = entries
        .distinctBy { it.phraseId }
        .sortedByDescending { it.spokenText.length }

    val chunks = mutableListOf<MixedPlaybackChunk>()
    val textBuffer = StringBuilder()
    var usedRecording = false
    var cursor = 0

    fun flushTextBuffer() {
        if (textBuffer.isEmpty()) return
        chunks += MixedPlaybackChunk.Text(textBuffer.toString())
        textBuffer.clear()
    }

    while (cursor < inputText.length) {
        val match = matchPool.firstOrNull { entry ->
            val candidate = entry.spokenText
            if (candidate.isEmpty()) return@firstOrNull false
            if (cursor + candidate.length > inputText.length) return@firstOrNull false
            if (!inputText.regionMatches(cursor, candidate, 0, candidate.length, ignoreCase = true)) {
                return@firstOrNull false
            }

            isRecordedBoundaryStart(inputText, cursor) &&
                isRecordedBoundaryEnd(inputText, cursor + candidate.length)
        }

        if (match != null) {
            flushTextBuffer()
            chunks += MixedPlaybackChunk.Recorded(match)
            usedRecording = true
            cursor += match.spokenText.length
        } else {
            textBuffer.append(inputText[cursor])
            cursor++
        }
    }

    flushTextBuffer()

    return chunks.takeIf { usedRecording }
}

private suspend fun playMixedPlan(
    chunks: List<MixedPlaybackChunk>,
    speechService: io.github.jdreioe.wingmate.domain.SpeechService,
    voice: io.github.jdreioe.wingmate.domain.Voice?
): Boolean {
    var usedRecording = false

    for (chunk in chunks) {
        when (chunk) {
            is MixedPlaybackChunk.Recorded -> {
                val entry = chunk.entry
                val played = speechService.speakRecordedAudio(
                    audioFilePath = entry.audioPath,
                    textForHistory = entry.spokenText,
                    voice = voice
                )
                if (!played) return false
                usedRecording = true
            }

            is MixedPlaybackChunk.Text -> {
                val text = chunk.text
                if (text.isBlank()) {
                    val pause = pauseForSeparatorChunk(text)
                    if (pause > 0L) delay(pause)
                    continue
                }

                val hasSpeakableContent = text.any { it.isLetterOrDigit() }
                if (!hasSpeakableContent) {
                    val pause = pauseForSeparatorChunk(text)
                    if (pause > 0L) delay(pause)
                    continue
                }

                // Keep punctuation/whitespace around text so transition from recording sounds less abrupt.
                speechService.speak(text, voice, voice?.pitch, voice?.rate)
                waitForSpeechToFinish(speechService)
            }
        }
    }

    return usedRecording
}

private fun pauseForSeparatorChunk(text: String): Long {
    val compact = text.trim()
    if (compact.isEmpty()) {
        return if (text.contains('\n')) 80L else 50L
    }

    return when {
        compact.any { it == '.' || it == '!' || it == '?' } -> 100L
        compact.any { it == ',' || it == ';' || it == ':' } -> 80L
        else -> 50L
    }
}

private suspend fun waitForSpeechToFinish(
    speechService: io.github.jdreioe.wingmate.domain.SpeechService,
    timeoutMs: Long = 15_000L
) {
    var elapsed = 0L
    delay(100)
    while (elapsed < timeoutMs && speechService.isPlaying()) {
        delay(50)
        elapsed += 50
    }
}

private fun isRecordedBoundaryStart(text: String, index: Int): Boolean {
    if (index <= 0) return true
    return isRecordedPhraseSeparator(text[index - 1])
}

private fun isRecordedBoundaryEnd(text: String, indexExclusive: Int): Boolean {
    if (indexExclusive >= text.length) return true
    return isRecordedPhraseSeparator(text[indexExclusive])
}

private fun isRecordedPhraseSeparator(char: Char): Boolean {
    return char.isWhitespace() || when (char) {
        '.', ',', '!', '?', ';', ':', '-', '_', '/', '\\', '(', ')', '[', ']', '{', '}', '"', '\'' -> true
        else -> false
    }
}