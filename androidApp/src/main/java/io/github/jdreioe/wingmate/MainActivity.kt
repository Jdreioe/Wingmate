package io.github.jdreioe.wingmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import android.os.Build
import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import io.github.jdreioe.wingmate.application.VoiceUseCase

private enum class Screen { Welcome, Phrases }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (GlobalContext.getOrNull() == null) {
            initKoin(module { })
        }
        // Register Android-specific implementations (TTS, SharedPreferences config)
        overrideAndroidSpeechService(this)

        setContent {
            // Start with the welcome screen by default; we'll switch to phrases if a voice is already selected.
            var currentScreen by remember { mutableStateOf(Screen.Welcome) }

            // Determine initial screen based on whether a voice is selected (non-blocking check)
            LaunchedEffect(Unit) {
                val koin = GlobalContext.getOrNull()
                val voiceUseCase = koin?.let { runCatching { it.get<VoiceUseCase>() }.getOrNull() }
                if (voiceUseCase != null) {
                    try {
                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                        if (selected != null) currentScreen = Screen.Phrases
                    } catch (_: Throwable) {}
                }
            }

            // Use centralized AppTheme which prefers dynamic colors and falls back to a purple seed scheme
            io.github.jdreioe.wingmate.ui.AppTheme(seed = androidx.compose.ui.graphics.Color(0xFF7C4DFF)) {
                when (currentScreen) {
                    Screen.Welcome -> io.github.jdreioe.wingmate.ui.WelcomeScreen(onContinue = { currentScreen = Screen.Phrases })
                    Screen.Phrases -> io.github.jdreioe.wingmate.ui.PhraseScreen()
                }
            }
        }
    }
}

