package io.github.jdreioe.wingmate

import android.os.Bundle
import android.os.Build
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import io.github.jdreioe.wingmate.display.ExternalDisplayPresentation
import io.github.jdreioe.wingmate.display.RearDisplayController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.Modifier
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import io.github.jdreioe.wingmate.App
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.ui.FullScreenDisplay
class MainActivity : ComponentActivity() {
    private val rearController by lazy { RearDisplayController(applicationContext) }
    private var presentation: ExternalDisplayPresentation? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { 
            attachToExternalDisplayIfRequested()
            // Auto-open fullscreen when a new external display is connected
            if (!io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.value) {
                checkAndAutoOpenOnSecondDisplay()
            }
        }
        override fun onDisplayRemoved(displayId: Int) { dismissPresentationIfInvalid() }
        override fun onDisplayChanged(displayId: Int) { /* no-op */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Koin if not already done
        if (GlobalContext.getOrNull() == null) {
            initKoin(module { })
        }
        
        // Register Android-specific implementations (TTS, SharedPreferences config)
        overrideAndroidSpeechService(this)

        // Observe the window bus to show/hide presentation
        io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show
            .onEach { show ->
                if (show) {
                    // Try rear display (API34+) first
                    rearController.startIfPossible(this)
                    // Fallback to classic external presentation
                    attachToExternalDisplayIfRequested()
                    // If neither rear nor external is active, show a dedicated primary display activity
                    val rearActive = rearController.active.value
                    val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
                    val hasExternal = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.isNotEmpty() == true
                    if (!rearActive && !hasExternal) {
                        startActivity(android.content.Intent(this, io.github.jdreioe.wingmate.display.PrimaryDisplayActivity::class.java))
                    }
                } else {
                    rearController.stopIfActive()
                    dismissPresentation()
                }
            }
            .launchIn(lifecycleScope)

        // Auto-open fullscreen if second display is already connected at startup
        checkAndAutoOpenOnSecondDisplay()

        setContent {
            AppTheme {
                val show by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsState(initial = false)
                val rearActive by rearController.active.collectAsState(initial = false)
                Box(Modifier.fillMaxSize()) {
                    App()

                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.registerDisplayListener(displayListener, null)
        // Try attaching if the toggle is already on
        io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.value.let { show ->
            if (show) {
                rearController.startIfPossible(this)
                attachToExternalDisplayIfRequested()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(displayListener)
        // Keep presentation if desired; you can dismiss here if you prefer tie to activity lifecycle
    }

    private fun attachToExternalDisplayIfRequested() {
        val show = io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.value
        if (!show) return
        val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager ?: return
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val external = displays.firstOrNull()
        if (external != null) {
            if (presentation?.display != external) {
                dismissPresentation()
                presentation = ExternalDisplayPresentation(this, external).also { it.show() }
            }
        } else {
            // No external display; optional: show a full-screen Activity instead (not implemented here)
        }
    }

    private fun dismissPresentationIfInvalid() {
        val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager ?: return
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toSet()
        val current = presentation?.display
        if (current != null && !displays.contains(current)) {
            dismissPresentation()
        }
    }

    private fun dismissPresentation() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
    }

    private fun checkAndAutoOpenOnSecondDisplay() {
        val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager ?: return
        val externalDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        
        // Check if rear display is available (API 34+)
        val hasRearDisplay = Build.VERSION.SDK_INT >= 34 && rearController.active.value
        
        // Auto-open if external display or rear display is available
        if (externalDisplays.isNotEmpty() || hasRearDisplay) {
            io.github.jdreioe.wingmate.presentation.DisplayWindowBus.open()
        }
    }
}

