package io.github.jdreioe.wingmate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.jdreioe.wingmate.ui.WelcomeScreen
import io.github.jdreioe.wingmate.ui.PhraseScreen
import org.koin.core.context.GlobalContext

enum class Screen { Welcome, Phrases }

/**
 * Main shared app composable that can be used by both Android and Desktop
 */
@OptIn(ExperimentalMaterial3Api::class )
@Composable
fun App() {
    MaterialTheme {
        var currentScreen by remember { mutableStateOf("welcome") }

        Box(modifier = Modifier.fillMaxSize()) {
            when (currentScreen) {
                "welcome" -> {
                    WelcomeScreen {
                        currentScreen = "phrase"
                    }
                }
                "phrase" -> {
                    PhraseScreen(
                        onBackToWelcome = { currentScreen = "welcome" }
                    )
                }
            }
        }
    }
}
