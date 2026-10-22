package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.*
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import org.koin.compose.getKoin

import com.hojmoseit.wingmate.BuildConfig
import com.hojmoseit.wingmate.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jdreioe.wingmate.domain.Settings

/** Input surface under the Message bar. Geometry and IME lifecycle belong to Compose. */
private enum class TypingInputSurface { Keyboard, Tray }

internal fun supportsMathMode(ttsEngine: TtsEngine): Boolean =
    ttsEngine == TtsEngine.AZURE_USER_RESOURCE || ttsEngine == TtsEngine.AZURE_MANAGED

@Composable
fun PhraseScreen(
    onBackToWelcome: (() -> Unit)? = null,
    onOpenBoardSetManager: (() -> Unit)? = null,
    onEditTypingScreen: (() -> Unit)? = null,
) {
    val koin = getKoin()
    val operations = remember(koin) {
        DefaultPhraseScreenOperations(
            session = koin.get(), settingsManager = koin.get(),
            typing = koin.get(), category = koin.get(), deletePhraseUseCase = koin.get(), phraseUseCase = koin.get(),
            saidRepository = koin.get(), voices = koin.get(), editingAccess = koin.getOrNull(),
            prediction = if (BuildConfig.DEBUG) koin.getOrNull<TextPredictionService>() else null,
            reporter = koin.get(),
        )
    }
    val factory = remember(operations) {
        viewModelFactory { initializer { PhraseScreenViewModel(createSavedStateHandle(), operations) } }
    }
    val viewModel: PhraseScreenViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showFullscreen by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val deleted = stringResource(R.string.phrase_deleted)
    val undo = stringResource(R.string.action_undo)
    val unavailable = stringResource(R.string.typing_screen_content_unavailable)
    LaunchedEffect(viewModel, deleted, undo, unavailable) {
        viewModel.events.collect { event ->
            when (event) {
                is PhraseScreenEvent.Deleted -> launch {
                    var undoRequested = false
                    try {
                        undoRequested = snackbar.showSnackbar(deleted, undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
                        if (undoRequested) viewModel.onAction(PhraseScreenAction.UndoDelete(event.deletionId))
                    } finally {
                        if (!undoRequested) viewModel.onAction(PhraseScreenAction.ForgetDelete(event.deletionId))
                    }
                }
                PhraseScreenEvent.ContentUnavailable -> launch { snackbar.showSnackbar(unavailable) }
            }
        }
    }
    LaunchedEffect(state.communication.activeMessage.displayText, showFullscreen) {
        if (showFullscreen) io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(state.communication.activeMessage.displayText)
    }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("typing-screen-ui", android.content.Context.MODE_PRIVATE) }
    var trayHeight by remember { mutableStateOf(preferences.getFloat("keyboard-height-dp", Float.NaN).takeUnless { it.isNaN() }?.dp) }
    PhraseScreenContent(
        ui = state, onAction = viewModel::onAction, snackbarHostState = snackbar,
        boardFeedback = rememberBoardFeedback(),
        showFullscreen = showFullscreen, keyboardTrayHeight = trayHeight,
        onKeyboardHeight = { height ->
            trayHeight = height
            preferences.edit().putFloat("keyboard-height-dp", height.value).apply()
        },
        onToggleFullscreen = {
            io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(state.input.text)
            if (showFullscreen) io.github.jdreioe.wingmate.presentation.DisplayWindowBus.close()
            else io.github.jdreioe.wingmate.presentation.DisplayWindowBus.open()
            operations.report(FeatureUsageEvents.FULLSCREEN_TOGGLE, "enabled" to (!showFullscreen).toString())
        },
        onOpenBoardSetManager = onOpenBoardSetManager?.let { open -> {
            operations.report(FeatureUsageEvents.SCREEN_VIEW, "screen" to "boardsets")
            open()
        } },
        onEditTypingScreen = onEditTypingScreen,
    )
    when (state.dialog) {
        TypingDialog.Settings -> SettingsScreen(
            onDismiss = { viewModel.onAction(PhraseScreenAction.DismissDialog) },
            onSaved = { viewModel.onAction(PhraseScreenAction.DismissDialog) },
            onBackToWelcome = onBackToWelcome,
        )
        TypingDialog.Unlock -> koin.getOrNull<EditingAccessController>()?.let { controller ->
            EditingAccessDialog(controller, EditingAccessDialogMode.Unlock,
                onDismiss = { viewModel.onAction(PhraseScreenAction.DismissDialog) },
                onSuccess = { viewModel.onAction(PhraseScreenAction.Unlocked) })
        }
        TypingDialog.AddPhrase, TypingDialog.EditPhrase -> if (state.dialog == TypingDialog.AddPhrase || state.dialogPhrase != null) AddPhraseDialog(
            onDismiss = { viewModel.onAction(PhraseScreenAction.DismissDialog) },
            categories = state.categories,
            initialPhrase = state.dialogPhrase,
            defaultCategoryId = state.selectedCategory?.id,
            onSave = { viewModel.onAction(PhraseScreenAction.SavePhrase(it)) },
            onDelete = { viewModel.onAction(PhraseScreenAction.DeletePhrase(it)) },
            isEditing = state.dialog == TypingDialog.EditPhrase,
            saveFailed = state.mutationFailed,
            saving = state.mutationInProgress,
        )
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun PhraseScreenContent(
    ui: PhraseScreenState,
    boardFeedback: BoardFeedback = remember { BoardFeedback() },
    onAction: (PhraseScreenAction) -> Unit,
    showFullscreen: Boolean = false,
    keyboardTrayHeight: Dp? = null,
    onKeyboardHeight: (Dp) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onOpenBoardSetManager: (() -> Unit)? = null,
    onEditTypingScreen: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val density = LocalDensity.current
    val settings = ui.settings
    val communicationState = ui.communication
    val input = ui.input
    val mathMode = ui.mathMode
    val secondaryLanguageRanges = ui.secondaryRanges
    val isSpeechPaused = communicationState.playbackStatus == CommunicationPlaybackStatus.Paused
    val predictions = ui.predictions
    val predictionsEnabled = BuildConfig.DEBUG
    val typingTemplateGraph = ui.template
    val typingTemplateLoadFailed = ui.templateStatus == TypingLoadStatus.Failed
    val categoriesLoadFailed = ui.categoryStatus == TypingLoadStatus.Failed
    val phrasesLoadFailed = ui.phraseStatus == TypingLoadStatus.Failed
    val historyItems = ui.history
    val categories = ui.categories
    val selectedPage = ui.selectedPage
    val selectedCategory = ui.selectedCategory
    val visiblePhrases = remember(ui.phrases, selectedPage, ui.settings.historyVisible) { ui.visiblePhrases }
    val compactPhrases = remember(ui.phrases, ui.lastPhraseCategoryId) { ui.compactPhrases }
    val categoryMenu = ui.dialogCategory.takeIf { ui.dialog == TypingDialog.CategoryMenu }
    val confirmDeleteCategory = ui.dialogCategory.takeIf { ui.dialog == TypingDialog.DeleteCategory }
    val showAddCategoryDialog = ui.dialog == TypingDialog.AddCategory
    var appBarMenuExpanded by remember { mutableStateOf(false) }
    var typingMenuExpanded by remember { mutableStateOf(false) }
    val textFieldFocusRequester = remember { FocusRequester() }
    var inputSurface by rememberSaveable { mutableStateOf(TypingInputSurface.Tray) }
    val refocusInput = { if (inputSurface == TypingInputSurface.Keyboard) textFieldFocusRequester.requestFocus() }
    val toggleFullscreen = onToggleFullscreen
    val openBoardSets = { onOpenBoardSetManager?.invoke(); Unit }
    val openSettings = { onAction(PhraseScreenAction.RequestDialog(TypingDialog.Settings)) }
    val requestTypingScreenReset = { onAction(PhraseScreenAction.RequestDialog(TypingDialog.Reset)) }
    fun control(effect: ObfButtonActionEffect) { onAction(PhraseScreenAction.Control(effect)); refocusInput() }
    val playInput = { control(ObfButtonActionEffect.Speak) }
    val pauseSpeech = { control(ObfButtonActionEffect.Pause) }
    val stopSpeech = { control(ObfButtonActionEffect.Stop) }
    val resumeSpeech = { control(ObfButtonActionEffect.Resume) }
    val toggleThatThought = { control(ObfButtonActionEffect.SwapHeldMessage) }
    val toggleSecondarySelection: (() -> Unit)? = if (ui.hasSecondaryLanguage) ({ control(ObfButtonActionEffect.ToggleSecondaryLanguage) }) else null
    val activatePhraseFromTypingScreen: (Phrase) -> Unit = { onAction(PhraseScreenAction.ActivatePhrase(it)) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LaunchedEffect(inputSurface) {
                AndroidAccessInputBus.restartScan()
            }
            val focusManager = LocalFocusManager.current
            val softwareKeyboardController = LocalSoftwareKeyboardController.current
            // Follow the system keyboard: showing it means typing, and dismissing it
            // (Back or the keyboard's own hide key) brings the tray back. A hardware
            // keyboard never shows one, so the toggle below still sets the surface.
            // The visibility flag stays true during native retraction. Follow the
            // animation's destination then, so the tray returns beneath the closing
            // keyboard. A settled floating keyboard has zero source and target
            // heights, so its visibility still comes from the flag.
            val isImeRetracting = WindowInsets.imeAnimationSource.getBottom(density) > 0 &&
                WindowInsets.imeAnimationTarget.getBottom(density) == 0
            val isImeVisible = WindowInsets.isImeVisible && !isImeRetracting
            var wasImeVisible by remember { mutableStateOf(false) }
            // When switching to the keyboard, the tray stays underneath while the
            // keyboard starts (a few hundred ms after it is requested) and slides up
            // over it, so the Message bar never drops in between. A hardware
            // keyboard never shows one, so this simply ends after a moment.
            var trayLingering by remember { mutableStateOf(false) }
            LaunchedEffect(trayLingering) {
                if (trayLingering) {
                    delay(700)
                    trayLingering = false
                }
            }
            LaunchedEffect(isImeVisible) {
                if (isImeVisible) {
                    if (inputSurface == TypingInputSurface.Tray) trayLingering = true
                    inputSurface = TypingInputSurface.Keyboard
                } else if (wasImeVisible) {
                    inputSurface = TypingInputSurface.Tray
                }
                wasImeVisible = isImeVisible
            }
            val showTray: () -> Unit = {
                inputSurface = TypingInputSurface.Tray
                focusManager.clearFocus()
                softwareKeyboardController?.hide()
            }
            val showKeyboard: () -> Unit = {
                inputSurface = TypingInputSurface.Keyboard
                trayLingering = true
                textFieldFocusRequester.requestFocus()
                softwareKeyboardController?.show()
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    // Hidden while the system keyboard is up to leave room for the
                    // Message; everything in it is back as soon as the keyboard closes.
                    AnimatedVisibility(
                        visible = !isImeVisible,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200)),
                    ) { BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val useOverflowMenu = maxWidth <= 720.dp
                        TopAppBar(
                            title = { Text("Wingmate", style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                            )) },
                            actions = {
                                if (useOverflowMenu) {
                                    Box {
                                        IconButton(onClick = { appBarMenuExpanded = true }) {
                                            Icon(
                                                imageVector = Icons.Filled.MoreVert,
                                                contentDescription = stringResource(R.string.common_more_actions)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = appBarMenuExpanded,
                                            onDismissRequest = { appBarMenuExpanded = false }
                                        ) {
                                            if (supportsMathMode(settings.ttsEngine)) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.speech_math_mode)) },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Filled.Calculate,
                                                            contentDescription = null,
                                                            tint = if (mathMode) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            }
                                                        )
                                                    },
                                                    onClick = {
                                                        onAction(PhraseScreenAction.MathMode(!mathMode))
                                                        appBarMenuExpanded = false
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.mode_switch_to_screens)) },
                                                leadingIcon = {
                                                    Icon(Icons.Filled.GridView, contentDescription = null)
                                                },
                                                enabled = onOpenBoardSetManager != null,
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    openBoardSets()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.typing_screen_edit)) },
                                                enabled = onEditTypingScreen != null,
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    onEditTypingScreen?.invoke()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.typing_screen_reset)) },
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    requestTypingScreenReset()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.phrase_screen_toggle_fullscreen_cd)) },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = if (showFullscreen) {
                                                            Icons.Filled.FullscreenExit
                                                        } else {
                                                            Icons.Filled.Fullscreen
                                                        },
                                                        contentDescription = null
                                                    )
                                                },
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    toggleFullscreen()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.phrase_screen_app_settings)) },
                                                leadingIcon = {
                                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                                },
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    openSettings()
                                                }
                                            )
                                        }
                                    }
                                } else {
                                    if (supportsMathMode(settings.ttsEngine)) {
                                        IconToggleButton(
                                            checked = mathMode,
                                            onCheckedChange = { onAction(PhraseScreenAction.MathMode(it)) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Calculate,
                                                contentDescription = stringResource(R.string.speech_math_mode_description),
                                                tint = if (mathMode) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = openBoardSets,
                                        enabled = onOpenBoardSetManager != null
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.GridView,
                                            contentDescription = stringResource(R.string.mode_switch_to_screens)
                                        )
                                    }

                                    IconButton(onClick = toggleFullscreen) {
                                        Icon(
                                            imageVector = if (showFullscreen) {
                                                Icons.Filled.FullscreenExit
                                            } else {
                                                Icons.Filled.Fullscreen
                                            },
                                            contentDescription = stringResource(R.string.phrase_screen_toggle_fullscreen_cd)
                                        )
                                    }

                                    IconButton(onClick = openSettings) {
                                        Icon(
                                            imageVector = Icons.Filled.Settings,
                                            contentDescription = stringResource(R.string.phrase_screen_app_settings)
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { typingMenuExpanded = true }) {
                                            Icon(
                                                imageVector = Icons.Filled.MoreVert,
                                                contentDescription = stringResource(R.string.common_more_actions),
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = typingMenuExpanded,
                                            onDismissRequest = { typingMenuExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.typing_screen_edit)) },
                                                enabled = onEditTypingScreen != null,
                                                onClick = {
                                                    typingMenuExpanded = false
                                                    onEditTypingScreen?.invoke()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.typing_screen_reset)) },
                                                onClick = {
                                                    typingMenuExpanded = false
                                                    requestTypingScreenReset()
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    } }
                },
                // Speak lives in the Message bar and the other speech controls in
                // the tray's Action strip; there is no bottom bar (#243, #299).
            ) { innerPadding ->
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                ) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp),
                        ) {
                    if (ui.phraseStatus == TypingLoadStatus.Loading) Text(stringResource(R.string.phrase_screen_loading), style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                    ))

                    if (typingTemplateLoadFailed || categoriesLoadFailed || phrasesLoadFailed) {
                        RepositoryFailurePanel(onRetry = { onAction(PhraseScreenAction.RetryLoad) })
                    }
                    if (ui.historyFailed && ui.isHistory) {
                        RepositoryFailurePanel(onRetry = { onAction(PhraseScreenAction.RetryHistory) })
                    }
                    if (ui.mutationFailed) {
                        Column {
                            Text(stringResource(R.string.typing_screen_save_failed), color = MaterialTheme.colorScheme.error)
                            Button(onClick = { onAction(PhraseScreenAction.RetryMutation) }, enabled = !ui.mutationInProgress) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                    val secondaryHighlightColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                    
                    val ssmlRanges = remember(input.text) {
                        Regex("\\[(\\d+(\\.\\d+)?)s\\]").findAll(input.text).map {
                            androidx.compose.ui.text.TextRange(it.range.first, it.range.last + 1)
                        }.toList()
                    }
                    val ssmlHighlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)

                    val onTypingAction: (ObfButtonActionEffect) -> Unit = { effect ->
                        if (effect == ObfButtonActionEffect.NativeKeyboard) showKeyboard() else control(effect)
                    }
                    val keyboardHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                    LaunchedEffect(keyboardHeight) {
                        if (keyboardHeight >= 200.dp) {
                            // Wait for the keyboard animation to settle before storing.
                            delay(300)
                            onKeyboardHeight(keyboardHeight)
                        }
                    }

                    categoryMenu?.let { menuCategory ->
                        val index = categories.indexOfFirst { it.id == menuCategory.id }
                        val moveCategory: (Int) -> Unit = { target ->
                            onAction(PhraseScreenAction.MoveCategory(menuCategory.id, target))
                        }
                        val menuTextStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                        )
                        ModalBottomSheet(onDismissRequest = { onAction(PhraseScreenAction.DismissDialog) }) {
                            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.category_move_left), style = menuTextStyle) },
                                    enabled = index > 0,
                                    onClick = {
                                        onAction(PhraseScreenAction.DismissDialog)
                                        moveCategory(index - 1)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.category_move_right), style = menuTextStyle) },
                                    enabled = index in 0 until categories.lastIndex,
                                    onClick = {
                                        onAction(PhraseScreenAction.DismissDialog)
                                        moveCategory(index + 1)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.category_delete_with_phrases), style = menuTextStyle) },
                                    onClick = {
                                        onAction(PhraseScreenAction.DismissDialog)
                                        onAction(PhraseScreenAction.RequestDialog(TypingDialog.DeleteCategory, categoryId = menuCategory.id))
                                    },
                                )
                            }
                        }
                    }

                    // Add category dialog
                    if (showAddCategoryDialog) {
                        val categoryName = ui.categoryName
                        AlertDialog(
                            onDismissRequest = { onAction(PhraseScreenAction.DismissDialog) },
                            title = { Text(stringResource(R.string.category_add_title), style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                            )) },
                            text = {
                                val showKeyboard = Modifier.showKeyboardOnFocus()
                                OutlinedTextField(
                                    value = categoryName,
                                    isError = ui.mutationFailed,
                                    supportingText = if (ui.mutationFailed) ({ Text(stringResource(R.string.typing_screen_save_failed)) }) else null,
                                    onValueChange = { onAction(PhraseScreenAction.CategoryName(it)) },
                                    placeholder = { Text(stringResource(R.string.category_name_label), style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) },
                                    singleLine = true,
                                    modifier = Modifier.then(showKeyboard)
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !ui.mutationInProgress && ui.categoryName.isNotBlank(),
                                    onClick = {
                                        onAction(PhraseScreenAction.SaveCategory)
                                    }
                                ) {
                                    Text(stringResource(R.string.common_add), style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                    ))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { 
                                    onAction(PhraseScreenAction.DismissDialog)
                                    onAction(PhraseScreenAction.CategoryName(""))
                                }) {
                                    Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                    ))
                                }
                            }
                        )
                    }

                    // Confirm delete category cascade
                    if (confirmDeleteCategory != null) {
                        AlertDialog(
                            onDismissRequest = { onAction(PhraseScreenAction.DismissDialog) },
                            title = { Text(stringResource(R.string.category_delete_title), style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                            )) },
                            text = { Text(stringResource(R.string.category_delete_message, confirmDeleteCategory.name.orEmpty()), style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                            )) },
                            confirmButton = {
                                TextButton(onClick = {
                                    onAction(PhraseScreenAction.DeleteCategory)
                                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                )) }
                            },
                            dismissButton = { TextButton(onClick = { onAction(PhraseScreenAction.DismissDialog) }) { Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                            )) } }
                        )
                    }

                    // Free space above the Message bar; the bar and its input
                    // surface stay anchored to the bottom (#299, layout 1).
                    Spacer(modifier = Modifier.weight(1f))

                    val speechActive = isSpeechPaused ||
                        communicationState.currentSpeechRequestId != null ||
                        communicationState.queuedSpeechCount > 0
                    val speechPlaying = communicationState.currentSpeechRequestId != null && !isSpeechPaused
                    val typingActionEnabled: (ObfButtonActionEffect) -> Boolean = { effect ->
                        when (effect) {
                            ObfButtonActionEffect.Pause -> speechPlaying
                            ObfButtonActionEffect.Resume -> isSpeechPaused
                            ObfButtonActionEffect.Stop -> speechActive
                            ObfButtonActionEffect.ToggleSecondaryLanguage -> toggleSecondarySelection != null
                            is ObfButtonActionEffect.Unsupported -> false
                            else -> true
                        }
                    }
                    val typingTemplate = typingTemplateGraph?.rootBoard
                    // Landscape with the keyboard up leaves room for one row above the
                    // Message bar: the Action strip (with Swap when a Message is held)
                    // replaces the Held and Phrase rows, so the Message stays visible.
                    val landscape = LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val oneRowWhileTyping = isImeVisible && landscape

                    // The app bar (with the fullscreen display) hides while typing, so the
                    // typing Action row starts with its own fullscreen toggle.
                    val fullscreenLabel = stringResource(R.string.phrase_screen_toggle_fullscreen_cd)
                    val fullscreenButton: @Composable () -> Unit = {
                        FilledTonalIconButton(
                            onClick = toggleFullscreen,
                            modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                        ) {
                            Icon(
                                imageVector = if (showFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = fullscreenLabel,
                            )
                        }
                    }

                    // Portrait while typing has free space above the Held row; the
                    // Action strip (SSML, Language, Hold) fills it, as in the tray.
                    AnimatedVisibility(
                        visible = isImeVisible && !landscape && typingTemplate != null,
                        enter = expandVertically(tween(250), expandFrom = Alignment.Bottom) + fadeIn(tween(250)),
                        exit = shrinkVertically(tween(250), shrinkTowards = Alignment.Bottom) + fadeOut(tween(250)),
                    ) {
                        if (typingTemplate != null) {
                            TypingActionRow(
                                settings = settings, feedback = boardFeedback,
                                template = typingTemplate,
                                onAction = onTypingAction,
                                isActionEnabled = typingActionEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                leading = fullscreenButton,
                            )
                        }
                    }

                    communicationState.heldMessage?.takeUnless { oneRowWhileTyping }?.let { held ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(start = 12.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = stringResource(R.string.typing_held_message, held.displayText),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = toggleThatThought,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.typing_held_message_swap))
                            }
                        }
                    }

                    if (inputSurface == TypingInputSurface.Keyboard) {
                        // Gboard shows its own suggestions, so Wingmate's only appear
                        // for a hardware keyboard.
                        if (!isImeVisible && !trayLingering && predictionsEnabled &&
                            (predictions.words.isNotEmpty() || predictions.letters.isNotEmpty())
                        ) {
                            PredictionBar(
                                predictions = predictions,
                                onWordSelected = { word ->
                                    onAction(PhraseScreenAction.CompleteWord(word))
                                },
                                onLetterSelected = { letter ->
                                    onAction(PhraseScreenAction.InsertPrediction(letter.toString()))
                                },
                                fontSizeScale = settings.fontSizeScale,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    // Slides in and out with the keyboard instead of popping, so the rows
                    // above the Message bar move smoothly.
                    AnimatedVisibility(
                        visible = inputSurface == TypingInputSurface.Keyboard && !oneRowWhileTyping &&
                            compactPhrases.isNotEmpty() && typingTemplate != null,
                        enter = expandVertically(tween(250), expandFrom = Alignment.Bottom) + fadeIn(tween(250)),
                        exit = shrinkVertically(tween(250), shrinkTowards = Alignment.Bottom) + fadeOut(tween(250)),
                    ) {
                        if (typingTemplate != null) {
                            CompactPhraseRow(
                                settings = settings, feedback = boardFeedback,
                                template = typingTemplate,
                                phrases = compactPhrases,
                                onPhraseActivated = activatePhraseFromTypingScreen,
                                onPhraseLongPress = { phrase ->
                                    onAction(PhraseScreenAction.RequestDialog(TypingDialog.EditPhrase, phraseId = phrase.id))
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }

                    if (oneRowWhileTyping && typingTemplate != null) {
                        val held = communicationState.heldMessage
                        TypingActionRow(
                                settings = settings, feedback = boardFeedback,
                            template = typingTemplate,
                            onAction = onTypingAction,
                            isActionEnabled = typingActionEnabled,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            leading = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    fullscreenButton()
                                    if (held != null) {
                                    val heldLabel = stringResource(R.string.typing_held_message, held.displayText)
                                    FilledTonalButton(
                                        onClick = toggleThatThought,
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .semantics { contentDescription = heldLabel },
                                    ) {
                                        Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.typing_held_message_swap))
                                    }
                                    }
                                }
                            },
                        )
                    }

                    // Message bar: the input-surface toggle, the Message, and the speech
                    // control as the largest target on the thumb side. It speaks, turns
                    // into Pause while speech plays and Resume while paused; Stop joins
                    // it while speech is active. All transport lives here (#299).
                    val barButtonSize = (56.dp * settings.playbackIconScale).coerceIn(48.dp, 72.dp)
                    val speakButtonSize = (64.dp * settings.playbackIconScale).coerceIn(56.dp, 88.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        val trayOpen = inputSurface == TypingInputSurface.Tray
                        FilledTonalIconButton(
                            onClick = if (trayOpen) showKeyboard else showTray,
                            modifier = Modifier.size(barButtonSize),
                        ) {
                            Icon(
                                imageVector = if (trayOpen) Icons.Filled.Keyboard else Icons.Filled.Add,
                                contentDescription = stringResource(
                                    if (trayOpen) R.string.typing_show_keyboard else R.string.typing_show_screen
                                ),
                            )
                        }
                        SecondaryLanguageTextField(
                            value = input,
                            onValueChange = { newValue ->
                                onAction(PhraseScreenAction.EditInput(newValue))
                            },
                            modifier = Modifier
                                .weight(1f)
                                // Focusing the Message means typing, with or without an
                                // on-screen keyboard (a hardware keyboard never shows one).
                                .onFocusChanged { focus ->
                                    if (focus.hasFocus && inputSurface == TypingInputSurface.Tray) {
                                        trayLingering = true
                                        inputSurface = TypingInputSurface.Keyboard
                                    }
                                }
                                // Grows with the Message up to a cap, then scrolls.
                                .heightIn(
                                    min = (56.dp * settings.inputFieldScale),
                                    max = (160.dp * settings.inputFieldScale),
                                ),
                            focusRequester = textFieldFocusRequester,
                            highlightRanges = secondaryLanguageRanges,
                            highlightColor = secondaryHighlightColor,
                            ssmlRanges = ssmlRanges,
                            ssmlColor = ssmlHighlightColor,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            minLines = 1,
                            maxLines = 6,
                            placeholder = {
                                Text(
                                    stringResource(R.string.phrase_screen_enter_text_placeholder),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            },
                        )
                        if (speechActive) {
                            FilledTonalIconButton(
                                onClick = stopSpeech,
                                modifier = Modifier.size(barButtonSize),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.playback_stop))
                            }
                        }
                        FilledIconButton(
                            onClick = when {
                                isSpeechPaused -> resumeSpeech
                                speechPlaying -> pauseSpeech
                                else -> playInput
                            },
                            modifier = Modifier.size(speakButtonSize),
                        ) {
                            Icon(
                                imageVector = when {
                                    speechPlaying -> Icons.Filled.Pause
                                    else -> Icons.Filled.PlayArrow
                                },
                                contentDescription = stringResource(
                                    when {
                                        isSpeechPaused -> R.string.playback_resume
                                        speechPlaying -> R.string.playback_pause
                                        else -> R.string.playback_play
                                    }
                                ),
                                modifier = Modifier.size(speakButtonSize / 2),
                            )
                        }
                    }

                    // Space under the Message bar. The app root already pads for the
                    // keyboard (safeDrawingPadding), so this only adds what the tray
                    // needs beyond the keyboard's current height. Opening follows the
                    // keyboard's target height rather than the lingering tray's height.
                    // Retraction restores the tray's space from the first frame.
                    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    // Limits come from the screen, which stays put while the keyboard
                    // resizes the content area.
                    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                    val maxTrayHeight = screenHeight * 0.6f
                    val preferredTrayHeight = keyboardTrayHeight ?: (screenHeight * 0.4f)
                    val trayHeight = (preferredTrayHeight - navigationBarHeight)
                        .coerceIn(minOf(200.dp, maxTrayHeight), maxTrayHeight)
                    val trayDrawn = inputSurface == TypingInputSurface.Tray || trayLingering || isImeRetracting
                    val imeInsets = WindowInsets.ime
                    val imeTargetInsets = WindowInsets.imeAnimationTarget
                    val imeSourceInsets = WindowInsets.imeAnimationSource
                    val navigationInsets = WindowInsets.navigationBars
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clipToBounds()
                            // Measured in the layout pass, like the root's keyboard padding,
                            // so both follow the same keyboard frame.
                            .layout { measurable, constraints ->
                                val navigation = navigationInsets.getBottom(this)
                                val ime = (imeInsets.getBottom(this) - navigation).coerceAtLeast(0)
                                val imeTarget = (imeTargetInsets.getBottom(this) - navigation).coerceAtLeast(0)
                                // Insets reach layout before recomposition, so read the
                                // direction here too to avoid one frame without tray space.
                                val retracting = imeTargetInsets.getBottom(this) == 0 &&
                                    imeSourceInsets.getBottom(this) > 0
                                val surfaceHeight = when {
                                    retracting -> trayHeight.roundToPx()
                                    isImeVisible -> imeTarget
                                    trayDrawn -> trayHeight.roundToPx()
                                    else -> 0
                                }
                                val height = (surfaceHeight - ime).coerceIn(0, constraints.maxHeight)
                                val placeable = measurable.measure(
                                    constraints.copy(minHeight = height, maxHeight = height)
                                )
                                layout(placeable.width, height) { placeable.place(0, 0) }
                            },
                    ) {
                    if (trayDrawn) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Full height even while the keyboard still covers part of it.
                                .wrapContentHeight(Alignment.Top, unbounded = true)
                                .height(trayHeight),
                        ) {
                            if (typingTemplate == null) {
                                RepositoryFailurePanel(onRetry = { onAction(PhraseScreenAction.RetryLoad) })
                            } else TypingScreenTray(
                                focusedCell = ui.boardFocusedCell,
                                onFocusCell = { onAction(PhraseScreenAction.FocusCell(it)) },
                                settings = settings, feedback = boardFeedback,
                                template = typingTemplate,
                                phrases = visiblePhrases,
                                categories = categories,
                                selection = selectedPage,
                                showHistory = settings.historyVisible && historyItems.isNotEmpty(),
                                history = historyItems,
                                onSelectionChanged = { onAction(PhraseScreenAction.SelectPage(it)) },
                                onOpenCategoryMenu = { category ->
                                    onAction(PhraseScreenAction.RequestDialog(TypingDialog.CategoryMenu, categoryId = category.id))
                                },
                                onAddCategory = { onAction(PhraseScreenAction.RequestDialog(TypingDialog.AddCategory)) },
                                onAddPhrase = { onAction(PhraseScreenAction.RequestDialog(TypingDialog.AddPhrase)) },
                                onPhraseActivated = activatePhraseFromTypingScreen,
                                onEditPhrase = { phrase ->
                                    onAction(PhraseScreenAction.RequestDialog(TypingDialog.EditPhrase, phraseId = phrase.id))
                                },
                                onDeletePhrase = { phrase -> onAction(PhraseScreenAction.DeletePhrase(phrase.id)) },
                                // Dropping onto a Phrase takes its place in the repository order.
                                onMovePhrase = { moved, target ->
                                    onAction(PhraseScreenAction.MovePhrase(moved.id, target.id))
                                },
                                onHistoryActivated = { historyItem ->
                                    val historyPhrase = Phrase(
                                        id = "history_${historyItem.id ?: historyItem.date ?: historyItem.createdAt ?: 0}",
                                        text = historyItem.saidText.orEmpty(),
                                        createdAt = historyItem.date ?: historyItem.createdAt ?: 0L,
                                        recordingPath = historyItem.audioFilePath,
                                    )
                                    activatePhraseFromTypingScreen(historyPhrase)
                                },
                                onAction = onTypingAction,
                                vocabularyMutationsEnabled = ui.vocabularyReady,
                                isActionEnabled = typingActionEnabled,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                    }
                }
                    }
                }
            }


            if (ui.dialog == TypingDialog.Reset) {
                AlertDialog(
                    onDismissRequest = { onAction(PhraseScreenAction.DismissDialog) },
                    title = { Text(stringResource(R.string.typing_screen_reset)) },
                    text = { Text(stringResource(R.string.typing_screen_reset_description)) },
                    confirmButton = { TextButton(enabled = !ui.mutationInProgress, onClick = { onAction(PhraseScreenAction.Reset) }) {
                        Text(stringResource(R.string.typing_screen_reset))
                    } },
                    dismissButton = { TextButton(onClick = { onAction(PhraseScreenAction.DismissDialog) }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
    }
}

@Preview
@Composable
private fun PhraseScreenContentPreview() {
    AppTheme {
        PhraseScreenContent(
            ui = PhraseScreenState(
                input = TextFieldValue("I would like some water"),
                phrases = listOf(
                    Phrase(id = "water", text = "Water", createdAt = 0),
                    Phrase(id = "help", text = "Please help", createdAt = 0),
                ),
                phraseStatus = TypingLoadStatus.Ready,
                template = io.github.jdreioe.wingmate.application.TypingScreenProjector.defaultTemplate(4, now = 0),
                templateStatus = TypingLoadStatus.Ready,
                categoryStatus = TypingLoadStatus.Ready,
            ),
            onAction = {},
        )
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
    maxLines: Int = Int.MAX_VALUE,
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
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.text.isEmpty()) {
                placeholder?.invoke()
            }
            val inputModifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .showKeyboardOnFocus()
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

@Composable
private fun RepositoryFailurePanel(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.typing_screen_load_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.common_retry))
        }
    }
}
