package io.github.jdreioe.wingmate

import android.os.Bundle
import android.os.Build
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.core.content.ContextCompat
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.FoldingFeature
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaSession
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaSessionCallback
import kotlinx.coroutines.launch
import android.util.Log
import java.util.concurrent.Executor
import io.github.jdreioe.wingmate.display.ExternalDisplayPresentation
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
import androidx.window.core.ExperimentalWindowApi
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import io.github.jdreioe.wingmate.App
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.ui.FullScreenDisplay
@OptIn(ExperimentalWindowApi::class)
class MainActivity : ComponentActivity() {
    private var presentation: ExternalDisplayPresentation? = null
    private var isFoldableUnfolded = false
    
    // Window Area API variables for rear display and dual-screen mode

    private lateinit var windowAreaController: WindowAreaController
    private lateinit var displayExecutor: Executor
    private var windowAreaSession: WindowAreaSession? = null
    private var windowAreaInfo: WindowAreaInfo? = null

    private var capabilityStatus: WindowAreaCapability.Status =
        WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED

    private val dualScreenOperation = WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA
    private val rearDisplayOperation = WindowAreaCapability.Operation.OPERATION_TRANSFER_ACTIVITY_TO_AREA
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

    @OptIn(ExperimentalWindowApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Koin if not already done
        if (GlobalContext.getOrNull() == null) {
            initKoin(module { })
        }
        
        // Register Android-specific implementations (TTS, SharedPreferences config)
        overrideAndroidSpeechService(this)

        // Initialize Window Area Controller for rear display
        if (Build.VERSION.SDK_INT >= 34) {
            displayExecutor = ContextCompat.getMainExecutor(this)
            windowAreaController = WindowAreaController.getOrCreate()
            
            lifecycleScope.launch(Dispatchers.Main) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    windowAreaController.windowAreaInfos
                        .map { info -> info.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING } }
                        .onEach { info -> windowAreaInfo = info }
                        .map { it?.getCapability(rearDisplayOperation)?.status ?: WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED }
                        .distinctUntilChanged()
                        .collect {
                            capabilityStatus = it
                            Log.d("MainActivity", "Rear display capability status: $it")
                        }
                }
            }
        }

        // Observe the window bus to show/hide presentation
        io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show
            .onEach { show ->
                if (show) {
                    // Try rear display (API34+) first - shows same content on rear
                    startRearDisplayIfPossible()
                    
                    // Only fallback to external presentation if rear display is not active
                    if (windowAreaSession == null) {
                        attachToExternalDisplayIfRequested()
                        
                        // If neither rear nor external is active, show a dedicated primary display activity
                        val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
                        val hasExternal = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.isNotEmpty() == true
                        if (!hasExternal) {
                            startActivity(android.content.Intent(this, io.github.jdreioe.wingmate.display.PrimaryDisplayActivity::class.java))
                        }
                    }
                } else {
                    stopRearDisplayIfActive()
                    dismissPresentation()
                }
            }
            .launchIn(lifecycleScope)

        // Auto-open fullscreen if second display is already connected at startup
        checkAndAutoOpenOnSecondDisplay()

        // Start observing foldable state changes
        observeFoldableState()

        setContent {
            AppTheme {
                val show by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsState(initial = false)
                Box(Modifier.fillMaxSize()) {
                    // Main app content - this will be mirrored to rear display when session is active
                    App()
                }
            }
        }
    }

    @OptIn(ExperimentalWindowApi::class)
    override fun onStart() {
        super.onStart()
        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.registerDisplayListener(displayListener, null)
        // Try attaching if the toggle is already on
        io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.value.let { show ->
            if (show) {
                startRearDisplayIfPossible()
                // Only try external display if rear is not active
                if (windowAreaSession == null) {
                    attachToExternalDisplayIfRequested()
                }
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
        
        Log.d("MainActivity", "Display check: external=${externalDisplays.size}, foldable=$isFoldableUnfolded")
        
        // Auto-open only for external displays or foldable unfolded
        
        if (externalDisplays.isNotEmpty()) {
            Log.d("MainActivity", "Auto-opening fullscreen display")
            io.github.jdreioe.wingmate.presentation.DisplayWindowBus.open()
        }
    }

    private fun observeFoldableState() {
        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity)
                .windowLayoutInfo(this@MainActivity)
                .collect { windowLayoutInfo ->
                    val wasUnfolded = isFoldableUnfolded
                    
                    // Check for foldable features
                    val foldingFeatures = windowLayoutInfo.displayFeatures.filterIsInstance<FoldingFeature>()
                    isFoldableUnfolded = foldingFeatures.any { feature ->
                        feature.state == FoldingFeature.State.FLAT || 
                        feature.state == FoldingFeature.State.HALF_OPENED
                    }
                    
                    Log.d("MainActivity", "Foldable state: wasUnfolded=$wasUnfolded, isUnfolded=$isFoldableUnfolded, features=${foldingFeatures.size}")
                    
                    // Don't auto-open fullscreen for foldables - let user manually activate
                    // The rear display capability will be available when needed
                }
        }
    }

    @OptIn(ExperimentalWindowApi::class)
    private fun startRearDisplayIfPossible() {
        if (Build.VERSION.SDK_INT < 34) return
        if (windowAreaSession != null) return // Already active
        
        val rearDisplayArea = windowAreaInfo ?: return
        
        if (capabilityStatus == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) {
            // Start rear display session using TRANSFER_ACTIVITY_TO_AREA
            // This will mirror the current activity content to the rear display
            windowAreaController.transferActivityToWindowArea(
                token = rearDisplayArea.token,
                activity = this,
                executor = displayExecutor,
                windowAreaSessionCallback = object : WindowAreaSessionCallback {
                    override fun onSessionStarted(session: WindowAreaSession) {
                        windowAreaSession = session
                        Log.d("MainActivity", "Rear display session started - content mirrored to rear")
                    }
                    
                    override fun onSessionEnded(t: Throwable?) {
                        windowAreaSession = null
                        Log.d("MainActivity", "Rear display session ended: ${t?.message}")
                    }
                }
            )
        } else {
            Log.d("MainActivity", "Rear display not available, status: $capabilityStatus")
        }
    }
    
    @OptIn(ExperimentalWindowApi::class)
    private fun stopRearDisplayIfActive() {
        windowAreaSession?.close()
        windowAreaSession = null
    }
}

