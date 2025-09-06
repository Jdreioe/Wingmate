package io.github.jdreioe.wingmate.display

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.ui.FullScreenDisplay

class PrimaryDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val show by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsState(initial = true)
                Box(Modifier.fillMaxSize()) {
                    // Fullscreen text content over a subtle dark scrim
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        FullScreenDisplay()
                        IconButton(
                            onClick = { finish(); io.github.jdreioe.wingmate.presentation.DisplayWindowBus.close() },
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        ) {
                            Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
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
