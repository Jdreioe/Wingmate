package io.github.jdreioe.wingmate.display

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.ui.FullScreenDisplay

class PrimaryDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val show by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsState(initial = true)
                Box(Modifier.fillMaxSize()) {
                    FullScreenDisplay(onClose = {
                        finish()
                        io.github.jdreioe.wingmate.presentation.DisplayWindowBus.close()
                    })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure the bus resets even if the user closes via system back or OS gesture
        io.github.jdreioe.wingmate.presentation.DisplayWindowBus.close()
    }
}
