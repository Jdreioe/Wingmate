package io.github.jdreioe.wingmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import io.github.jdreioe.wingmate.App
import io.github.jdreioe.wingmate.ui.AppTheme
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Koin if not already done
        if (GlobalContext.getOrNull() == null) {
            initKoin(module { })
        }
        
        // Register Android-specific implementations (TTS, SharedPreferences config)
        overrideAndroidSpeechService(this)

        setContent {
            AppTheme {
                App()
            }
        }
    }
}

