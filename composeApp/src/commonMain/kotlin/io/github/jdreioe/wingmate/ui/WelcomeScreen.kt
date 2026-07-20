package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.domain.StartupMode
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_next
import wingmatekmp.composeapp.generated.resources.common_back
import wingmatekmp.composeapp.generated.resources.welcome_importing_board
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_keyboard_desc
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_keyboard_title
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_screens_desc
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_screens_title
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_subtitle
import wingmatekmp.composeapp.generated.resources.welcome_start_mode_title
import wingmatekmp.composeapp.generated.resources.welcome_subtitle
import wingmatekmp.composeapp.generated.resources.welcome_title
import wingmatekmp.composeapp.generated.resources.welcome_ui_settings

@Composable
fun WelcomeScreen(
    onComplete: (startupMode: StartupMode, createScreen: Boolean) -> Unit
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
    var createScreenOnComplete by remember { mutableStateOf(false) }
    var showUiSettings by remember { mutableStateOf(false) }
    
    // Import state
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when (step) {
    0 -> {
            // Intro
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
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
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { showUiSettings = true }) { 
                        Text(stringResource(Res.string.welcome_ui_settings))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { step = 1 }) { Text(stringResource(Res.string.common_next)) }
                }
            }
        }
        1 -> {
            StartupModeSelectionScreen(
                onKeyboard = {
                    startupMode = StartupMode.Keyboard
                    createScreenOnComplete = false
                    step = if (enableBoardImport) 2 else 3
                },
                onScreens = {
                    startupMode = StartupMode.Screens
                    createScreenOnComplete = false
                    step = if (enableBoardImport) 2 else 3
                },
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
                onNext = { step = 5 }, // Skip to voice selection if System TTS
                onCancel = { step = if (enableBoardImport) 2 else 1 },
                onAzureSelected = { step = 4 }
            )
        }
        4 -> {
            // Azure configuration screen
            AzureConfigScreen(
                onNext = { step = 5 },
                onBack = { step = 3 }
            )
        }
        5 -> {
            // Full-screen voice selector
            VoiceSelectionFullScreen(
                onNext = { step = 6 },
                onCancel = { step = 3 }
            )
        }
        6 -> {
            // Test voice screen
            TestVoiceScreen(
                onNext = { onComplete(startupMode, createScreenOnComplete) },
                onBack = { step = 5 }
            )
        }
    }

    if (showUiSettings) {
        SettingsScreen(onDismiss = { showUiSettings = false })
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
            modifier = Modifier.fillMaxSize().padding(24.dp),
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
                onClick = onKeyboard
            )
            Spacer(Modifier.height(16.dp))
            ImportOptionCard(
                title = stringResource(Res.string.welcome_start_mode_screens_title),
                description = stringResource(Res.string.welcome_start_mode_screens_desc),
                onClick = onScreens
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBack) {
                Text(stringResource(Res.string.common_back))
            }
        }
    }
}
