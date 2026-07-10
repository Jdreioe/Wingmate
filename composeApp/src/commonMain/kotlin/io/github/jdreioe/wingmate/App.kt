package io.github.jdreioe.wingmate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.ui.WelcomeScreen
import io.github.jdreioe.wingmate.ui.PhraseScreen
import io.github.jdreioe.wingmate.ui.BoardSetManagerScreen
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.domain.SettingsRepository
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

enum class Screen { Welcome, Phrases }

/**
 * Main shared app composable that can be used by both Android and Desktop
 */
@OptIn(ExperimentalMaterial3Api::class )
@Composable
fun App() {
    val settingsRepository = koinInject<SettingsRepository>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // Check if welcome flow is completed
            var welcomeCompleted by remember { mutableStateOf<Boolean?>(null) }
            var currentScreen by remember { mutableStateOf<String?>(null) }
            var initialBoardId by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            fun completeWelcomeAndNavigate(target: String) {
                scope.launch(Dispatchers.Default) {
                    val currentSettings = runCatching { settingsRepository.get() }.getOrNull()
                    currentSettings?.let { settings ->
                        settingsRepository.update(settings.copy(welcomeFlowCompleted = true))
                    }
                }
                currentScreen = target
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.WELCOME_COMPLETED,
                    "target" to target
                )
            }
            
            LaunchedEffect(Unit) {
                val settings = withContext(Dispatchers.Default) {
                    runCatching { settingsRepository.get() }.getOrNull()
                }
                welcomeCompleted = settings?.welcomeFlowCompleted ?: false
                currentScreen = if (welcomeCompleted == true) "phrase" else "welcome"
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.APP_STARTED,
                    "welcome_completed" to (welcomeCompleted == true).toString()
                )
            }

            LaunchedEffect(currentScreen) {
                val screen = currentScreen ?: return@LaunchedEffect
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.SCREEN_VIEW,
                    "screen" to screen
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    "welcome" -> {
                        WelcomeScreen(
                            onContinue = { completeWelcomeAndNavigate("phrase") },
                            onCreateFromScratch = { completeWelcomeAndNavigate("boardsets") }
                        )
                    }
                    "phrase" -> {
                        PhraseScreen(
                            onBackToWelcome = { currentScreen = "welcome" },
                            onOpenBoardSetManager = { currentScreen = "boardsets" },
                            initialBoardId = initialBoardId
                        )
                    }
                    "boardsets" -> {
                        BoardSetManagerScreen(
                            onBack = { currentScreen = "phrase" },
                            onOpenBoard = { boardId ->
                                initialBoardId = boardId
                                currentScreen = "phrase"
                            }
                        )
                    }
                    null -> {
                        // Loading state
                        Box(modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}
