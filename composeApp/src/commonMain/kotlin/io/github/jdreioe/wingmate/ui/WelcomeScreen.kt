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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_next
import wingmatekmp.composeapp.generated.resources.welcome_importing_board
import wingmatekmp.composeapp.generated.resources.welcome_subtitle
import wingmatekmp.composeapp.generated.resources.welcome_title
import wingmatekmp.composeapp.generated.resources.welcome_ui_settings

@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
    onCreateFromScratch: (() -> Unit)? = null
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
                    Button(onClick = { step = if (enableBoardImport) 1 else 2 }) { Text(stringResource(Res.string.common_next)) }
                }
            }
        }
        1 -> {
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
                                    step = 2
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
                                    step = 2
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
                        onCreateFromScratch?.invoke()
                    },
                    onSkip = {
                        featureUsageReporter?.reportEvent(
                            FeatureUsageEvents.BOARD_SETUP_CHOICE,
                            "mode" to "skip"
                        )
                        step = 2
                    }
                )
            }
        }
        2 -> {
            // Voice engine selector screen
            VoiceEngineSelectorScreen(
                onNext = { step = 4 }, // Skip to voice selection if System TTS
                onCancel = { step = if (enableBoardImport) 1 else 0 }, // Back to Import or Intro
                onAzureSelected = { step = 3 } // Go to Azure config if Azure selected
            )
        }
        3 -> {
            // Azure configuration screen
            AzureConfigScreen(
                onNext = { step = 4 }, // Go to voice selection after Azure config
                onBack = { step = 2 } // Back to TTS engine selection
            )
        }
        4 -> {
            // Full-screen voice selector
            VoiceSelectionFullScreen(
                onNext = { step = 5 }, // Go to test voice screen after voice selection
                onCancel = { step = 2 } // Back to Engine selector
            )
        }
        5 -> {
            // Test voice screen
            TestVoiceScreen(
                onNext = { onContinue() }, // Complete setup after testing voice
                onBack = { step = 4 } // Back to voice selection
            )
        }
    }

    if (showUiSettings) {
        UiSettingsDialog(onDismissRequest = { showUiSettings = false })
    }
}
