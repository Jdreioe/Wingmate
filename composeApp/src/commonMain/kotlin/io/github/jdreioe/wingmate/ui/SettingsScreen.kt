package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.*

private enum class SettingsTab { Speech, Display, Accessibility, General }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDismiss: () -> Unit, onSaved: (() -> Unit)? = null) {
    val koin = getKoin()
    val configRepo = remember(koin) { koin.getOrNull<ConfigRepository>() }
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val settingsStateManager = remember(koin) { koin.getOrNull<SettingsStateManager>() }
    val featureUsageReporter = remember(koin) { koin.getOrNull<FeatureUsageReporter>() }

    // Selected tab
    var selectedTab by remember { mutableStateOf(SettingsTab.Speech) }

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
    var autoUpdateEnabled by remember { mutableStateOf(true) }
    var featureUsageReportingEnabled by remember { mutableStateOf(false) }
    var partnerWindowEnabled by remember { mutableStateOf(false) }

    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Partner window device detection (desktop-only)
    val partnerDeviceConnected by PartnerWindowAvailability.deviceConnected.collectAsStateWithLifecycle()

    // Helper to update settings reactively
    fun updateSettings(update: (Settings) -> Settings) {
        scope.launch {
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
        autoUpdateEnabled = s.autoUpdateEnabled
        featureUsageReportingEnabled = s.featureUsageReportingEnabled
        partnerWindowEnabled = s.partnerWindowEnabled
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
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.9f),
        title = {
            Text(
                stringResource(Res.string.ui_settings_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Tab row
                    PrimaryTabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Tab(
                            selected = selectedTab == SettingsTab.Speech,
                            onClick = { selectedTab = SettingsTab.Speech },
                            text = { Text("Speech") },
                            icon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == SettingsTab.Display,
                            onClick = { selectedTab = SettingsTab.Display },
                            text = { Text("Display") },
                            icon = { Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == SettingsTab.Accessibility,
                            onClick = { selectedTab = SettingsTab.Accessibility },
                            text = { Text("Access") },
                            icon = { Icon(Icons.Filled.Accessibility, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == SettingsTab.General,
                            onClick = { selectedTab = SettingsTab.General },
                            text = { Text("General") },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Content for each tab with spring animation
                    AnimatedContent(
                        targetState = selectedTab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        transitionSpec = {
                            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally(
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
                                initialOffsetX = { fullWidth -> direction * fullWidth / 3 }
                            ) + fadeIn(
                                animationSpec = spring(stiffness = Spring.StiffnessLow)
                            )).togetherWith(
                                slideOutHorizontally(
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
                                    targetOffsetX = { fullWidth -> -direction * fullWidth / 3 }
                                ) + fadeOut(
                                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                                )
                            )
                        },
                        label = "settings_tab_content"
                    ) { currentTab ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                        when (currentTab) {
                            SettingsTab.Speech -> SpeechSection(
                                useSystemTts = useSystemTts,
                                onUseSystemTtsChange = { useSystemTts = it },
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
                                autoUpdateEnabled = autoUpdateEnabled,
                                onAutoUpdateChange = { checked -> autoUpdateEnabled = checked; updateSettings { it.copy(autoUpdateEnabled = checked) } },
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
                                onPartnerWindowChange = { checked -> partnerWindowEnabled = checked; updateSettings { it.copy(partnerWindowEnabled = checked) } }
                            )
                        }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.Default) {
                        // Save Azure config if needed
                        if (!useSystemTts && endpoint.isNotBlank() && subscriptionKey.isNotBlank()) {
                            runCatching {
                                configRepo?.saveSpeechConfig(
                                    SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey)
                                )
                            }
                        }
                        // Save TTS engine preference
                        settingsUseCase?.let { useCase ->
                            val current = runCatching { useCase.get() }.getOrNull() ?: Settings()
                            useCase.update(current.copy(
                                useSystemTts = useSystemTts,
                                virtualMicEnabled = virtualMic,
                                featureUsageReportingEnabled = featureUsageReportingEnabled
                            ))
                        }
                    }
                    onSaved?.invoke()
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
        }
    )
}

// ─── Speech Tab ──────────────────────────────────────────────────────────────

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

@Composable
private fun GeneralSection(
    autoUpdateEnabled: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    featureUsageReportingEnabled: Boolean,
    onFeatureReportingChange: (Boolean) -> Unit,
    partnerWindowEnabled: Boolean,
    partnerDeviceConnected: Boolean,
    onPartnerWindowChange: (Boolean) -> Unit
) {
    SectionHeader("Updates & Analytics")

    SettingsCheckbox(
        checked = autoUpdateEnabled,
        onCheckedChange = onAutoUpdateChange,
        title = stringResource(Res.string.ui_settings_auto_updates_title),
        description = stringResource(Res.string.ui_settings_auto_updates_desc)
    )
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
