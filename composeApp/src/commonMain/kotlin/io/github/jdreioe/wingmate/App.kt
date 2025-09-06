package io.github.jdreioe.wingmate

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.jdreioe.wingmate.ui.WelcomeScreen
import io.github.jdreioe.wingmate.ui.PhraseScreen
import io.github.jdreioe.wingmate.application.VoiceUseCase
import org.koin.core.context.GlobalContext

enum class Screen { Welcome, Phrases }

/**
 * Main shared app composable that can be used by both Android and Desktop
 */
@Composable
fun App() {
    var currentScreen by remember { mutableStateOf(Screen.Welcome) }
    
    // Determine initial screen based on whether a voice is selected
    LaunchedEffect(Unit) {
        val koin = GlobalContext.getOrNull()
        val voiceUseCase = koin?.let { runCatching { it.get<VoiceUseCase>() }.getOrNull() }
        if (voiceUseCase != null) {
            val selected = runCatching { voiceUseCase.selected() }.getOrNull()
            if (selected != null) currentScreen = Screen.Phrases
        }
    }

    // App content
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (currentScreen) {
            Screen.Welcome -> WelcomeScreen(onContinue = { currentScreen = Screen.Phrases })
            Screen.Phrases -> PhraseScreen(onBackToWelcome = { currentScreen = Screen.Welcome })
        }
    }
}
