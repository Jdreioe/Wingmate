package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.infrastructure.ArasaacDownloadProgress
import io.github.jdreioe.wingmate.infrastructure.ArasaacSymbolDownloadService
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.*

private enum class SettingsTab { Speech, Display, Accessibility, General }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    onSaved: (() -> Unit)? = null,
    onBaseBoardsRestored: (() -> Unit)? = null
) {
    val koin = getKoin()
    val configRepo = remember(koin) { koin.getOrNull<ConfigRepository>() }
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val settingsStateManager = remember(koin) { koin.getOrNull<SettingsStateManager>() }
    val featureUsageReporter = remember(koin) { koin.getOrNull<FeatureUsageReporter>() }
    val boardSetUseCase = remember(koin) { koin.getOrNull<BoardSetUseCase>() }
    val obfParser = remember(koin) { koin.getOrNull<ObfParser>() }
    val imageCacher = remember(koin) { koin.getOrNull<ImageCacher>() }
    val arasaacDownloader = remember(imageCacher) {
        imageCacher?.let(::ArasaacSymbolDownloadService)
    }

    // Null represents the Pixel-style settings index; categories open as child pages.
    var selectedTab by remember { mutableStateOf<SettingsTab?>(null) }

    // --- Speech section state ---
    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var useSystemTts by remember { mutableStateOf(false) }
    var virtualMic by remember { mutableStateOf(false) }

    // --- Display section state ---
    var fontSizeScale by remember { mutableStateOf(1.0f) }
    var playbackIconScale by remember { mutableStateOf(1.0f) }
    var categoryChipScale by remember { mutableStateOf(1.0f) }
    var buttonScale by remember { mutableStateOf(1.0f) }
    var inputFieldScale by remember { mutableStateOf(1.0f) }
    var showLabels by remember { mutableStateOf(true) }
    var showSymbols by remember { mutableStateOf(true) }
    var labelAtTop by remember { mutableStateOf(false) }
    var gridColumns by remember { mutableStateOf(3) }
    var highContrastMode by remember { mutableStateOf(false) }

    // --- Accessibility section state ---
    var holdToSelectMillis by remember { mutableStateOf(0L) }
    var dwellToSelectMillis by remember { mutableStateOf(0L) }
    var selectionSoundEnabled by remember { mutableStateOf(false) }
    var auditoryFishingEnabled by remember { mutableStateOf(false) }
    var usageLoggingEnabled by remember { mutableStateOf(false) }

    // --- General section state ---
    var featureUsageReportingEnabled by remember { mutableStateOf(false) }
    var partnerWindowEnabled by remember { mutableStateOf(false) }
    var startupMode by remember { mutableStateOf(StartupMode.Keyboard) }
    var startupBoardSetId by remember { mutableStateOf<String?>(null) }
    var availableBoardSets by remember { mutableStateOf<List<ObfBoardSet>>(emptyList()) }
    var restoringBaseBoards by remember { mutableStateOf(false) }
    var baseBoardsStatus by remember { mutableStateOf<String?>(null) }
    var cachedArasaacSymbols by remember { mutableStateOf(0) }
    var arasaacProgress by remember { mutableStateOf<ArasaacDownloadProgress?>(null) }
    var arasaacDownloadError by remember { mutableStateOf(false) }
    var arasaacFailedCount by remember { mutableStateOf(0) }

    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val settingsUpdateMutex = remember { Mutex() }
    val baseBoardsRestoredMessage = stringResource(Res.string.ui_settings_base_boards_restored)
    val baseBoardsErrorMessage = stringResource(Res.string.ui_settings_base_boards_error)

    // Partner window device detection (desktop-only)
    val partnerDeviceConnected by PartnerWindowAvailability.deviceConnected.collectAsStateWithLifecycle()

    // Helper to update settings reactively
    fun updateSettings(update: (Settings) -> Settings) {
        scope.launch {
            settingsUpdateMutex.withLock {
                if (settingsStateManager != null) {
                    settingsStateManager.updateSettings(update)
                } else {
                    settingsUseCase?.let { useCase ->
                        withContext(Dispatchers.Default) {
                            val current = runCatching { useCase.get() }.getOrNull() ?: Settings()
                            useCase.update(update(current))
                        }
                    }
                }
            }
        }
    }

    // Load all settings on first composition
    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.Default) { configRepo?.getSpeechConfig() }
        cfg?.let {
            endpoint = it.endpoint
            subscriptionKey = it.subscriptionKey
        }

        val s = withContext(Dispatchers.Default) {
            runCatching { settingsUseCase?.get() }.getOrNull() ?: Settings()
        }
        useSystemTts = s.useSystemTts
        virtualMic = s.virtualMicEnabled
        featureUsageReportingEnabled = s.featureUsageReportingEnabled
        partnerWindowEnabled = s.partnerWindowEnabled
        startupMode = s.startupMode
        startupBoardSetId = s.startupBoardSetId
        availableBoardSets = withContext(Dispatchers.Default) {
            runCatching { boardSetUseCase?.listBoardSets().orEmpty() }.getOrDefault(emptyList())
        }
        showLabels = s.showLabels
        showSymbols = s.showSymbols
        labelAtTop = s.labelAtTop
        holdToSelectMillis = s.holdToSelectMillis
        gridColumns = s.gridColumns
        highContrastMode = s.highContrastMode
        dwellToSelectMillis = s.dwellToSelectMillis
        selectionSoundEnabled = s.selectionSoundEnabled
        auditoryFishingEnabled = s.auditoryFishingEnabled
        usageLoggingEnabled = s.usageLoggingEnabled
        fontSizeScale = s.fontSizeScale
        playbackIconScale = s.playbackIconScale
        categoryChipScale = s.categoryChipScale
        buttonScale = s.buttonScale
        inputFieldScale = s.inputFieldScale
        featureUsageReporter?.setEnabled(s.featureUsageReportingEnabled)
        cachedArasaacSymbols = runCatching { arasaacDownloader?.cachedCount() ?: 0 }.getOrDefault(0)
        loading = false
    }

    // Persist text input after the user pauses typing instead of waiting for a Save button.
    LaunchedEffect(endpoint, subscriptionKey, loading) {
        if (!loading) {
            delay(400)
            runCatching {
                configRepo?.saveSpeechConfig(
                    SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey)
                )
            }
        }
    }

    fun closeSettings() {
        onSaved?.invoke()
        onDismiss()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        selectedTab?.let { settingsCategoryTitle(it) }
                            ?: stringResource(Res.string.ui_settings_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedTab == null) closeSettings() else selectedTab = null
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_close))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 920.dp)
                        .align(Alignment.TopCenter)
                ) {
                    if (selectedTab == null) {
                        SettingsHomePage(
                            onSelectCategory = { selectedTab = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val currentTab = checkNotNull(selectedTab)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp)
                        ) {
                        when (currentTab) {
                            SettingsTab.Speech -> SpeechSection(
                                useSystemTts = useSystemTts,
                                onUseSystemTtsChange = { checked ->
                                    useSystemTts = checked
                                    updateSettings { it.copy(useSystemTts = checked) }
                                },
                                endpoint = endpoint,
                                onEndpointChange = { endpoint = it },
                                subscriptionKey = subscriptionKey,
                                onSubscriptionKeyChange = { subscriptionKey = it },
                                virtualMic = virtualMic,
                                onVirtualMicChange = { checked ->
                                    virtualMic = checked
                                    updateSettings { it.copy(virtualMicEnabled = checked) }
                                }
                            )
                            SettingsTab.Display -> DisplaySection(
                                fontSizeScale = fontSizeScale,
                                onFontSizeScaleChange = { fontSizeScale = it; updateSettings { s -> s.copy(fontSizeScale = it) } },
                                playbackIconScale = playbackIconScale,
                                onPlaybackIconScaleChange = { playbackIconScale = it; updateSettings { s -> s.copy(playbackIconScale = it) } },
                                categoryChipScale = categoryChipScale,
                                onCategoryChipScaleChange = { categoryChipScale = it; updateSettings { s -> s.copy(categoryChipScale = it) } },
                                buttonScale = buttonScale,
                                onButtonScaleChange = { buttonScale = it; updateSettings { s -> s.copy(buttonScale = it) } },
                                inputFieldScale = inputFieldScale,
                                onInputFieldScaleChange = { inputFieldScale = it; updateSettings { s -> s.copy(inputFieldScale = it) } },
                                showLabels = showLabels,
                                onShowLabelsChange = { checked -> showLabels = checked; updateSettings { it.copy(showLabels = checked) } },
                                showSymbols = showSymbols,
                                onShowSymbolsChange = { checked -> showSymbols = checked; updateSettings { it.copy(showSymbols = checked) } },
                                labelAtTop = labelAtTop,
                                onLabelAtTopChange = { checked -> labelAtTop = checked; updateSettings { it.copy(labelAtTop = checked) } },
                                gridColumns = gridColumns,
                                onGridColumnsChange = { gridColumns = it },
                                onGridColumnsChangeFinished = { updateSettings { it.copy(gridColumns = gridColumns) } },
                                highContrastMode = highContrastMode,
                                onHighContrastModeChange = { checked -> highContrastMode = checked; updateSettings { it.copy(highContrastMode = checked) } }
                            )
                            SettingsTab.Accessibility -> AccessibilitySection(
                                holdToSelectMillis = holdToSelectMillis,
                                onHoldToSelectChange = { holdToSelectMillis = it },
                                onHoldToSelectChangeFinished = { updateSettings { it.copy(holdToSelectMillis = holdToSelectMillis) } },
                                dwellToSelectMillis = dwellToSelectMillis,
                                onDwellToSelectChange = { dwellToSelectMillis = it },
                                onDwellToSelectChangeFinished = { updateSettings { it.copy(dwellToSelectMillis = dwellToSelectMillis) } },
                                selectionSoundEnabled = selectionSoundEnabled,
                                onSelectionSoundChange = { checked -> selectionSoundEnabled = checked; updateSettings { it.copy(selectionSoundEnabled = checked) } },
                                auditoryFishingEnabled = auditoryFishingEnabled,
                                onAuditoryFishingChange = { checked -> auditoryFishingEnabled = checked; updateSettings { it.copy(auditoryFishingEnabled = checked) } },
                                usageLoggingEnabled = usageLoggingEnabled,
                                onUsageLoggingChange = { checked -> usageLoggingEnabled = checked; updateSettings { it.copy(usageLoggingEnabled = checked) } }
                            )
                            SettingsTab.General -> GeneralSection(
                                startupMode = startupMode,
                                startupBoardSetId = startupBoardSetId,
                                availableBoardSets = availableBoardSets,
                                onStartupModeChange = { mode ->
                                    startupMode = mode
                                    updateSettings { it.copy(startupMode = mode) }
                                },
                                onStartupBoardSetChange = { boardSetId ->
                                    startupBoardSetId = boardSetId
                                    updateSettings { it.copy(startupBoardSetId = boardSetId) }
                                },
                                featureUsageReportingEnabled = featureUsageReportingEnabled,
                                onFeatureReportingChange = { checked ->
                                    featureUsageReportingEnabled = checked
                                    updateSettings { it.copy(featureUsageReportingEnabled = checked) }
                                    featureUsageReporter?.setEnabled(checked)
                                    featureUsageReporter?.reportEvent(
                                        FeatureUsageEvents.ANALYTICS_CONSENT_CHANGED,
                                        "enabled" to checked.toString(),
                                        "source" to "settings_screen"
                                    )
                                },
                                partnerWindowEnabled = partnerWindowEnabled,
                                partnerDeviceConnected = partnerDeviceConnected,
                                onPartnerWindowChange = { checked -> partnerWindowEnabled = checked; updateSettings { it.copy(partnerWindowEnabled = checked) } },
                                restoringBaseBoards = restoringBaseBoards,
                                baseBoardsStatus = baseBoardsStatus,
                                onRestoreBaseBoards = {
                                    if (!restoringBaseBoards) {
                                        scope.launch {
                                            restoringBaseBoards = true
                                            baseBoardsStatus = null
                                            val restored = runCatching {
                                                val parser = obfParser ?: error(baseBoardsErrorMessage)
                                                val useCase = boardSetUseCase ?: error(baseBoardsErrorMessage)
                                                restoreStarterBoards(systemLanguageTag(), parser, useCase)
                                                    ?: error(baseBoardsErrorMessage)
                                            }
                                            baseBoardsStatus = restored.fold(
                                                onSuccess = {
                                                    onBaseBoardsRestored?.invoke()
                                                    baseBoardsRestoredMessage
                                                },
                                                onFailure = { it.message ?: baseBoardsErrorMessage }
                                            )
                                            availableBoardSets = runCatching {
                                                boardSetUseCase?.listBoardSets().orEmpty()
                                            }.getOrDefault(availableBoardSets)
                                            restoringBaseBoards = false
                                        }
                                    }
                                },
                                arasaacAvailable = arasaacDownloader != null,
                                cachedArasaacSymbols = cachedArasaacSymbols,
                                arasaacProgress = arasaacProgress,
                                arasaacDownloadError = arasaacDownloadError,
                                arasaacFailedCount = arasaacFailedCount,
                                onDownloadArasaac = {
                                    if (arasaacProgress == null) {
                                        scope.launch {
                                            arasaacDownloadError = false
                                            runCatching {
                                                arasaacDownloader?.downloadAll(systemLanguageTag()) { progress ->
                                                    arasaacProgress = progress
                                                } ?: error("ARASAAC storage unavailable")
                                            }.onSuccess { result ->
                                                cachedArasaacSymbols = result.total - result.failed
                                                arasaacDownloadError = result.failed > 0
                                                arasaacFailedCount = result.failed
                                            }.onFailure {
                                                arasaacDownloadError = true
                                                arasaacFailedCount = arasaacProgress?.failed ?: 0
                                                cachedArasaacSymbols = runCatching {
                                                    arasaacDownloader?.cachedCount() ?: cachedArasaacSymbols
                                                }.getOrDefault(cachedArasaacSymbols)
                                            }
                                            arasaacProgress = null
                                        }
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun settingsCategoryTitle(tab: SettingsTab): String = when (tab) {
    SettingsTab.Speech -> stringResource(Res.string.ui_settings_speech_title)
    SettingsTab.Display -> stringResource(Res.string.ui_settings_display_title)
    SettingsTab.Accessibility -> stringResource(Res.string.ui_settings_accessibility_title)
    SettingsTab.General -> stringResource(Res.string.ui_settings_general_title)
}

private data class SettingsCategoryItem(
    val tab: SettingsTab,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconContainerColor: Color,
    val iconColor: Color
)

@Composable
private fun SettingsHomePage(
    onSelectCategory: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val categories = listOf(
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = stringResource(Res.string.ui_settings_speech_title),
            subtitle = stringResource(Res.string.ui_settings_speech_desc),
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65)
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(Res.string.ui_settings_display_title),
            subtitle = stringResource(Res.string.ui_settings_display_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000)
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(Res.string.ui_settings_accessibility_title),
            subtitle = stringResource(Res.string.ui_settings_accessibility_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044)
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(Res.string.ui_settings_general_title),
            subtitle = stringResource(Res.string.ui_settings_general_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18)
        )
    )
    val normalizedQuery = query.trim().lowercase()
    val filteredCategories = categories.filter {
        normalizedQuery.isEmpty() ||
            it.title.lowercase().contains(normalizedQuery) ||
            it.subtitle.lowercase().contains(normalizedQuery)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(Res.string.ui_settings_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )

        if (filteredCategories.isEmpty()) {
            Text(
                text = stringResource(Res.string.ui_settings_search_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                filteredCategories.forEachIndexed { index, item ->
                    SettingsCategoryRow(
                        item = item,
                        onClick = { onSelectCategory(item.tab) }
                    )
                    if (index < filteredCategories.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 88.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsCategoryRow(
    item: SettingsCategoryItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = item.iconContainerColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.iconColor,
                    modifier = Modifier.size(27.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Speech Settings ─────────────────────────────────────────────────────────

@Composable
private fun SpeechSection(
    useSystemTts: Boolean,
    onUseSystemTtsChange: (Boolean) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    subscriptionKey: String,
    onSubscriptionKeyChange: (String) -> Unit,
    virtualMic: Boolean,
    onVirtualMicChange: (Boolean) -> Unit
) {
    // Sub-dialog states
    var showVoiceSelection by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    SectionHeader("Text-to-Speech Engine")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !useSystemTts,
            onClick = { onUseSystemTtsChange(false) },
            label = { Text("Azure TTS") },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = useSystemTts,
            onClick = { onUseSystemTtsChange(true) },
            label = { Text("System TTS") },
            modifier = Modifier.weight(1f)
        )
    }

    if (!useSystemTts) {
        Spacer(modifier = Modifier.height(16.dp))
        val showKeyboard = rememberShowKeyboardOnFocus()
        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            label = { Text("Region / Endpoint") },
            placeholder = { Text("e.g., eastus") },
            modifier = Modifier.fillMaxWidth().then(showKeyboard)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = subscriptionKey,
            onValueChange = onSubscriptionKeyChange,
            label = { Text("Subscription Key") },
            modifier = Modifier.fillMaxWidth().then(showKeyboard)
        )
    }

    Spacer(modifier = Modifier.height(24.dp))
    SectionHeader(stringResource(Res.string.phrase_screen_voice_settings))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { showVoiceSelection = true },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.voice_select_title))
        }
        OutlinedButton(
            onClick = { showLanguageDialog = true },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.common_language))
        }
    }

    if (isDesktop()) {
        Spacer(modifier = Modifier.height(16.dp))
        SettingsSwitch(
            checked = virtualMic,
            onCheckedChange = onVirtualMicChange,
            title = stringResource(Res.string.ui_settings_virtual_mic_title),
            description = stringResource(Res.string.ui_settings_virtual_mic_desc)
        )
    }

    // Embedded sub-dialogs
    if (showVoiceSelection) {
        VoiceSelectionDialog(show = true, onDismiss = { showVoiceSelection = false })
    }
    if (showLanguageDialog) {
        UiLanguageDialog(
            show = true,
            onDismiss = { showLanguageDialog = false },
            openPrimaryMenuInitially = true
        )
    }
}

// ─── Display Tab ─────────────────────────────────────────────────────────────

@Composable
private fun DisplaySection(
    fontSizeScale: Float,
    onFontSizeScaleChange: (Float) -> Unit,
    playbackIconScale: Float,
    onPlaybackIconScaleChange: (Float) -> Unit,
    categoryChipScale: Float,
    onCategoryChipScaleChange: (Float) -> Unit,
    buttonScale: Float,
    onButtonScaleChange: (Float) -> Unit,
    inputFieldScale: Float,
    onInputFieldScaleChange: (Float) -> Unit,
    showLabels: Boolean,
    onShowLabelsChange: (Boolean) -> Unit,
    showSymbols: Boolean,
    onShowSymbolsChange: (Boolean) -> Unit,
    labelAtTop: Boolean,
    onLabelAtTopChange: (Boolean) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    onGridColumnsChangeFinished: () -> Unit,
    highContrastMode: Boolean,
    onHighContrastModeChange: (Boolean) -> Unit
) {
    // --- Grid Layout ---
    SectionHeader("Grid Layout")

    SettingsSwitch(
        checked = showLabels,
        onCheckedChange = onShowLabelsChange,
        title = stringResource(Res.string.ui_settings_show_labels_title)
    )
    SettingsSwitch(
        checked = showSymbols,
        onCheckedChange = onShowSymbolsChange,
        title = stringResource(Res.string.ui_settings_show_symbols_title)
    )
    if (showLabels && showSymbols) {
        SettingsSwitch(
            checked = labelAtTop,
            onCheckedChange = onLabelAtTopChange,
            title = stringResource(Res.string.ui_settings_label_at_top_title)
        )
    }

    SettingsSlider(
        title = stringResource(Res.string.ui_settings_grid_columns_title),
        value = gridColumns.toFloat(),
        onValueChange = { onGridColumnsChange(it.toInt()) },
        onValueChangeFinished = onGridColumnsChangeFinished,
        valueRange = 1f..6f,
        steps = 4,
        valueLabel = "$gridColumns"
    )

    SettingsSwitch(
        checked = highContrastMode,
        onCheckedChange = onHighContrastModeChange,
        title = stringResource(Res.string.ui_settings_high_contrast_title)
    )

    Spacer(modifier = Modifier.height(24.dp))

    // --- UI Scaling ---
    SectionHeader("UI Scaling")

    ScaleSlider("Font Size", fontSizeScale, onFontSizeScaleChange)
    ScaleSlider("Playback Icons", playbackIconScale, onPlaybackIconScaleChange)
    ScaleSlider("Category Chips", categoryChipScale, onCategoryChipScaleChange)
    ScaleSlider("Buttons", buttonScale, onButtonScaleChange)
    ScaleSlider("Input Fields", inputFieldScale, onInputFieldScaleChange)
}

// ─── Accessibility Tab ───────────────────────────────────────────────────────

@Composable
private fun AccessibilitySection(
    holdToSelectMillis: Long,
    onHoldToSelectChange: (Long) -> Unit,
    onHoldToSelectChangeFinished: () -> Unit,
    dwellToSelectMillis: Long,
    onDwellToSelectChange: (Long) -> Unit,
    onDwellToSelectChangeFinished: () -> Unit,
    selectionSoundEnabled: Boolean,
    onSelectionSoundChange: (Boolean) -> Unit,
    auditoryFishingEnabled: Boolean,
    onAuditoryFishingChange: (Boolean) -> Unit,
    usageLoggingEnabled: Boolean,
    onUsageLoggingChange: (Boolean) -> Unit
) {
    SectionHeader("Touch & Timing")

    SettingsSlider(
        title = stringResource(Res.string.ui_settings_hold_to_select_title),
        description = stringResource(Res.string.ui_settings_hold_to_select_desc),
        value = holdToSelectMillis.toFloat(),
        onValueChange = { onHoldToSelectChange(it.toLong()) },
        onValueChangeFinished = onHoldToSelectChangeFinished,
        valueRange = 0f..2000f,
        steps = 19,
        valueLabel = "${holdToSelectMillis.toInt()} ms"
    )

    SettingsSlider(
        title = stringResource(Res.string.ui_settings_dwell_to_select_title),
        description = stringResource(Res.string.ui_settings_dwell_to_select_desc),
        value = dwellToSelectMillis.toFloat(),
        onValueChange = { onDwellToSelectChange(it.toLong()) },
        onValueChangeFinished = onDwellToSelectChangeFinished,
        valueRange = 0f..5000f,
        steps = 19,
        valueLabel = "${dwellToSelectMillis.toInt()} ms"
    )

    Spacer(modifier = Modifier.height(24.dp))
    SectionHeader("Feedback & Logging")

    SettingsCheckbox(
        checked = selectionSoundEnabled,
        onCheckedChange = onSelectionSoundChange,
        title = stringResource(Res.string.ui_settings_selection_sound_title)
    )
    SettingsCheckbox(
        checked = auditoryFishingEnabled,
        onCheckedChange = onAuditoryFishingChange,
        title = stringResource(Res.string.ui_settings_auditory_fishing_title),
        description = stringResource(Res.string.ui_settings_auditory_fishing_desc)
    )
    SettingsCheckbox(
        checked = usageLoggingEnabled,
        onCheckedChange = onUsageLoggingChange,
        title = stringResource(Res.string.ui_settings_usage_logging_title),
        description = stringResource(Res.string.ui_settings_usage_logging_desc)
    )
}

// ─── General Tab ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralSection(
    startupMode: StartupMode,
    startupBoardSetId: String?,
    availableBoardSets: List<ObfBoardSet>,
    onStartupModeChange: (StartupMode) -> Unit,
    onStartupBoardSetChange: (String?) -> Unit,
    featureUsageReportingEnabled: Boolean,
    onFeatureReportingChange: (Boolean) -> Unit,
    partnerWindowEnabled: Boolean,
    partnerDeviceConnected: Boolean,
    onPartnerWindowChange: (Boolean) -> Unit,
    restoringBaseBoards: Boolean,
    baseBoardsStatus: String?,
    onRestoreBaseBoards: () -> Unit,
    arasaacAvailable: Boolean,
    cachedArasaacSymbols: Int,
    arasaacProgress: ArasaacDownloadProgress?,
    arasaacDownloadError: Boolean,
    arasaacFailedCount: Int,
    onDownloadArasaac: () -> Unit
) {
    SectionHeader(stringResource(Res.string.ui_settings_startup_mode_title))
    Text(
        stringResource(Res.string.ui_settings_startup_mode_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = startupMode == StartupMode.Keyboard,
            onClick = { onStartupModeChange(StartupMode.Keyboard) },
            label = { Text(stringResource(Res.string.ui_settings_startup_mode_keyboard)) },
            leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = startupMode == StartupMode.Screens,
            onClick = { onStartupModeChange(StartupMode.Screens) },
            label = { Text(stringResource(Res.string.ui_settings_startup_mode_screens)) },
            leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.weight(1f)
        )
    }

    if (startupMode == StartupMode.Screens) {
        var targetExpanded by remember { mutableStateOf(false) }
        val selectedName = availableBoardSets
            .firstOrNull { it.id == startupBoardSetId }
            ?.name
            ?: stringResource(Res.string.ui_settings_startup_screen_library)

        Spacer(modifier = Modifier.height(12.dp))
        ExposedDropdownMenuBox(
            expanded = targetExpanded,
            onExpandedChange = { targetExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(Res.string.ui_settings_startup_screen_title)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(
                    ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = true
                )
            )
            ExposedDropdownMenu(
                expanded = targetExpanded,
                onDismissRequest = { targetExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.ui_settings_startup_screen_library)) },
                    onClick = {
                        onStartupBoardSetChange(null)
                        targetExpanded = false
                    }
                )
                availableBoardSets.forEach { boardSet ->
                    DropdownMenuItem(
                        text = { Text(boardSet.name) },
                        onClick = {
                            onStartupBoardSetChange(boardSet.id)
                            targetExpanded = false
                        }
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
    SectionHeader(stringResource(Res.string.ui_settings_base_boards_title))
    Text(
        stringResource(Res.string.ui_settings_base_boards_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onRestoreBaseBoards,
        enabled = !restoringBaseBoards
    ) {
        if (restoringBaseBoards) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                if (restoringBaseBoards) {
                    Res.string.ui_settings_base_boards_restoring
                } else {
                    Res.string.ui_settings_base_boards_restore
                }
            )
        )
    }
    baseBoardsStatus?.let { status ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(24.dp))
    SectionHeader(stringResource(Res.string.ui_settings_symbols_title))
    Text(
        stringResource(Res.string.ui_settings_symbols_download_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    val activeProgress = arasaacProgress
    OutlinedButton(
        onClick = onDownloadArasaac,
        enabled = arasaacAvailable && activeProgress == null
    ) {
        if (activeProgress != null) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (activeProgress != null) {
                stringResource(
                    Res.string.ui_settings_symbols_downloading,
                    activeProgress.completed,
                    activeProgress.total
                )
            } else {
                stringResource(Res.string.ui_settings_symbols_download)
            }
        )
    }
    if (activeProgress != null && activeProgress.total > 0) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { activeProgress.completed.toFloat() / activeProgress.total },
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        when {
            !arasaacAvailable -> stringResource(Res.string.ui_settings_symbols_unavailable)
            arasaacDownloadError -> stringResource(
                Res.string.ui_settings_symbols_failed,
                arasaacFailedCount
            )
            cachedArasaacSymbols > 0 -> stringResource(
                Res.string.ui_settings_symbols_cached,
                cachedArasaacSymbols
            )
            else -> stringResource(Res.string.ui_settings_symbols_download_title)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (arasaacDownloadError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(24.dp))
    SectionHeader(stringResource(Res.string.ui_settings_analytics_title))

    SettingsCheckbox(
        checked = featureUsageReportingEnabled,
        onCheckedChange = onFeatureReportingChange,
        title = stringResource(Res.string.ui_settings_feature_reporting_title),
        description = stringResource(Res.string.ui_settings_feature_reporting_desc)
    )

    if (partnerDeviceConnected) {
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Partner Window")

        SettingsCheckbox(
            checked = partnerWindowEnabled,
            onCheckedChange = onPartnerWindowChange,
            title = stringResource(Res.string.ui_settings_partner_window_title),
            description = stringResource(Res.string.ui_settings_partner_window_desc)
        )
    }
}

// ─── Reusable Components ─────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String? = null
) {
    val bounceScale by animateFloatAsState(
        targetValue = if (checked) 1.0f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "checkbox_bounce"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .scale(bounceScale)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    description: String? = null
) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ScaleSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { newValue ->
                val stepped = (newValue * 10).toInt() / 10f
                onValueChange(stepped)
            },
            valueRange = 0.5f..2.0f,
            steps = 14,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
