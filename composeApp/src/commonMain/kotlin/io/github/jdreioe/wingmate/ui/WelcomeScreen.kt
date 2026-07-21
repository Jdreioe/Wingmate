package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.ui.PlatformBackHandler
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_next
import wingmatekmp.composeapp.generated.resources.common_back
import wingmatekmp.composeapp.generated.resources.common_continue
import wingmatekmp.composeapp.generated.resources.welcome_importing_board
import wingmatekmp.composeapp.generated.resources.welcome_analytics_description
import wingmatekmp.composeapp.generated.resources.welcome_analytics_opt_in
import wingmatekmp.composeapp.generated.resources.welcome_analytics_title
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_keyboard_desc
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_keyboard_title
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_screens_desc
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_screens_title
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_subtitle
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_title
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_back_to_choices
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_boardsets_step_1
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_boardsets_step_2
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_boardsets_step_3
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_boardsets_subtitle
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_boardsets_title
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_continue
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_keyboard_step_1
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_keyboard_step_2
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_keyboard_step_3
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_keyboard_subtitle
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_keyboard_title
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_phrase_preview_message
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_phrase_preview_title
import wingmatekmp.composeapp.generated.resources.welcome_mode_tour_title
import wingmatekmp.composeapp.generated.resources.welcome_subtitle
import wingmatekmp.composeapp.generated.resources.welcome_title

@Composable
fun WelcomeScreen(
    onComplete: (startupMode: StartupMode, createScreen: Boolean, analyticsEnabled: Boolean) -> Unit
) {
    val enableBoardImport = !isReleaseBuild()
    val koin = getKoin()
    val boardImportService = remember(enableBoardImport, koin) {
        if (enableBoardImport) koin.getOrNull<BoardImportService>() else null
    }
    val featureUsageReporter = remember(koin) {
        koin.getOrNull<FeatureUsageReporter>()
    }

    var step by remember { mutableStateOf(0) }
    var startupMode by remember { mutableStateOf(StartupMode.Keyboard) }
    var modeTour by remember { mutableStateOf<StartupMode?>(StartupMode.Keyboard) }
    var createScreenOnComplete by remember { mutableStateOf(false) }
    var voiceSelectorFollowsAzureSetup by remember { mutableStateOf(false) }
    var analyticsEnabled by remember { mutableStateOf(false) }
    
    // Import state
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.WELCOME_STARTED)
    }
    LaunchedEffect(step) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.WELCOME_STEP_VIEWED, "step" to step.toString())
    }

    PlatformBackHandler(enabled = step > 0) {
        when (step) {
            1 -> if (modeTour != null) modeTour = null else step = 0
            2 -> step = 1
            3 -> step = if (enableBoardImport) 2 else 1
            4 -> step = 3
            5 -> step = 6
            6 -> step = if (voiceSelectorFollowsAzureSetup) 4 else 3
            7 -> step = 6
            8 -> step = 7
        }
    }

    when (step) {
        0 -> {
            // Intro
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(Res.string.welcome_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(Res.string.welcome_subtitle),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { step = 1 }) { Text(stringResource(Res.string.common_next)) }
                }
            }
        }
        1 -> {
            val selectMode: (StartupMode) -> Unit = { mode ->
                startupMode = mode
                createScreenOnComplete = false
                featureUsageReporter?.reportEvent(
                    FeatureUsageEvents.STARTUP_MODE_SELECTED,
                    "mode" to mode.name.lowercase()
                )
                step = if (enableBoardImport) 2 else 3
            }
            modeTour?.let { mode ->
                StartupModeTourScreen(
                    mode = mode,
                    onContinue = {
                        modeTour = if (mode == StartupMode.Keyboard) StartupMode.Screens else null
                    },
                    onBack = { modeTour = null }
                )
            } ?: StartupModeSelectionScreen(
                onKeyboard = { selectMode(StartupMode.Keyboard) },
                onScreens = { selectMode(StartupMode.Screens) },
                onBack = { step = 0 }
            )
        }
        2 -> {
            // Import Board Options
            if (isImporting) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(Res.string.welcome_importing_board))
                        }
                    }
                }
            } else {
                ImportOptionsScreen(
                    onImportClassic = { 
                        scope.launch {
                            isImporting = true
                            featureUsageReporter?.reportEvent(
                                FeatureUsageEvents.BOARD_IMPORT_STARTED,
                                "mode" to "classic"
                            )
                            try {
                                val result = boardImportService?.importBoards(isModern = false) == true
                                if (result) {
                                    featureUsageReporter?.reportEvent(
                                        FeatureUsageEvents.BOARD_IMPORT_COMPLETED,
                                        "mode" to "classic"
                                    )
                                    // Move to next step if successful
                                    step = 3
                                } else {
                                    featureUsageReporter?.reportEvent(
                                        FeatureUsageEvents.BOARD_IMPORT_FAILED,
                                        "mode" to "classic",
                                        "reason" to "cancelled_or_failed"
                                    )
                                    // Handle failure or cancellation (stay on screen)
                                    // Ideally show snackbar, but for now just stop spinner
                                }
                            } catch (e: Throwable) {
                                featureUsageReporter?.reportEvent(
                                    FeatureUsageEvents.BOARD_IMPORT_FAILED,
                                    "mode" to "classic",
                                    "reason" to "exception"
                                )
                                e.printStackTrace()
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    onImportModern = {
                         scope.launch {
                            isImporting = true
                            featureUsageReporter?.reportEvent(
                                FeatureUsageEvents.BOARD_IMPORT_STARTED,
                                "mode" to "modern"
                            )
                            try {
                                val result = boardImportService?.importBoards(isModern = true) == true
                                if (result) {
                                    featureUsageReporter?.reportEvent(
                                        FeatureUsageEvents.BOARD_IMPORT_COMPLETED,
                                        "mode" to "modern"
                                    )
                                    step = 3
                                } else {
                                    featureUsageReporter?.reportEvent(
                                        FeatureUsageEvents.BOARD_IMPORT_FAILED,
                                        "mode" to "modern",
                                        "reason" to "cancelled_or_failed"
                                    )
                                }
                            } catch (e: Throwable) {
                                featureUsageReporter?.reportEvent(
                                    FeatureUsageEvents.BOARD_IMPORT_FAILED,
                                    "mode" to "modern",
                                    "reason" to "exception"
                                )
                                e.printStackTrace()
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    onCreateFromScratch = {
                        featureUsageReporter?.reportEvent(
                            FeatureUsageEvents.BOARD_SETUP_CHOICE,
                            "mode" to "scratch"
                        )
                        createScreenOnComplete = true
                        step = 3
                    },
                    onSkip = {
                        featureUsageReporter?.reportEvent(
                            FeatureUsageEvents.BOARD_SETUP_CHOICE,
                            "mode" to "skip"
                        )
                        step = 3
                    },
                    showClassic = startupMode == StartupMode.Screens,
                    showModern = startupMode == StartupMode.Keyboard,
                    showCreateFromScratch = startupMode == StartupMode.Screens
                )
            }
        }
        3 -> {
            // Voice engine selector screen
            VoiceEngineSelectorScreen(
                onNext = {
                    voiceSelectorFollowsAzureSetup = false
                    featureUsageReporter?.reportEvent(FeatureUsageEvents.VOICE_ENGINE_SELECTED, "engine" to "system")
                    step = 6
                },
                onCancel = { step = if (enableBoardImport) 2 else 1 },
                onAzureSelected = {
                    featureUsageReporter?.reportEvent(FeatureUsageEvents.VOICE_ENGINE_SELECTED, "engine" to "azure")
                    step = 4
                }
            )
        }
        4 -> {
            // Azure F0 portal-assisted setup flow
            F0SetupScreen(
                onDone = {
                    voiceSelectorFollowsAzureSetup = true
                    step = 6
                },
                onBack = { step = 3 }
            )
        }
        5 -> {
            // Choose languages for the selected voice before testing it.
            LanguageSelectionPage(
                onBack = { step = 6 },
                onContinue = { step = 7 },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp)
            )
        }
        6 -> {
            // Newer searchable voice selector, before language and test-voice steps.
            VoiceSelectionPage(
                onBack = { step = if (voiceSelectorFollowsAzureSetup) 5 else 3 },
                onVoiceSelected = { step = 5 },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp)
            )
        }
        7 -> {
            // Test voice screen
            TestVoiceScreen(
                onNext = { step = 8 },
                onBack = { step = 6 }
            )
        }
        8 -> AnalyticsConsentScreen(
            enabled = analyticsEnabled,
            onEnabledChange = { analyticsEnabled = it },
            onBack = { step = 7 },
            onContinue = { onComplete(startupMode, createScreenOnComplete, analyticsEnabled) }
        )
    }
}

@Composable
private fun AnalyticsConsentScreen(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(Res.string.welcome_analytics_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.welcome_analytics_description), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.welcome_analytics_opt_in), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.common_continue))
            }
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(Res.string.common_back))
            }
        }
    }
}

@Composable
private fun StartupModeSelectionScreen(
    onKeyboard: () -> Unit,
    onScreens: () -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(Res.string.welcome_start_mode_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.welcome_start_mode_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            ImportOptionCard(
                title = stringResource(Res.string.welcome_start_mode_keyboard_title),
                description = stringResource(Res.string.welcome_start_mode_keyboard_desc),
                icon = Icons.Default.Keyboard,
                onClick = onKeyboard
            )
            Spacer(Modifier.height(16.dp))
            ImportOptionCard(
                title = stringResource(Res.string.welcome_start_mode_screens_title),
                description = stringResource(Res.string.welcome_start_mode_screens_desc),
                icon = Icons.Default.GridView,
                onClick = onScreens
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBack) {
                Text(stringResource(Res.string.common_back))
            }
        }
    }
}

@Composable
private fun StartupModeTourScreen(
    mode: StartupMode,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val isKeyboard = mode == StartupMode.Keyboard
    val title = stringResource(
        if (isKeyboard) Res.string.welcome_mode_tour_keyboard_title else Res.string.welcome_mode_tour_boardsets_title
    )
    val subtitle = stringResource(
        if (isKeyboard) Res.string.welcome_mode_tour_keyboard_subtitle else Res.string.welcome_mode_tour_boardsets_subtitle
    )
    val steps = if (isKeyboard) {
        listOf(
            stringResource(Res.string.welcome_mode_tour_keyboard_step_1),
            stringResource(Res.string.welcome_mode_tour_keyboard_step_2),
            stringResource(Res.string.welcome_mode_tour_keyboard_step_3)
        )
    } else {
        listOf(
            stringResource(Res.string.welcome_mode_tour_boardsets_step_1),
            stringResource(Res.string.welcome_mode_tour_boardsets_step_2),
            stringResource(Res.string.welcome_mode_tour_boardsets_step_3)
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Icon(
                imageVector = if (isKeyboard) Icons.Default.Keyboard else Icons.Default.GridView,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(20.dp))
            Text(stringResource(Res.string.welcome_mode_tour_title), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            if (isKeyboard) {
                PhraseScreenTourPreview()
                Spacer(Modifier.height(24.dp))
            }
            steps.forEachIndexed { index, description ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (index < steps.lastIndex) Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.welcome_mode_tour_continue))
            }
            TextButton(onClick = onBack) {
                Text(stringResource(Res.string.welcome_mode_tour_back_to_choices))
            }
        }
    }
}

@Composable
private fun PhraseScreenTourPreview() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(Res.string.welcome_mode_tour_phrase_preview_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    stringResource(Res.string.welcome_mode_tour_phrase_preview_message),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TourPhraseButton("Hello", Modifier.weight(1f))
                TourPhraseButton("Yes", Modifier.weight(1f))
                TourPhraseButton("Help", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TourPhraseButton(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
