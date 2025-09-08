package io.github.jdreioe.wingmate.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import io.github.jdreioe.wingmate.ui.AppTheme
import io.github.jdreioe.wingmate.ui.FullScreenDisplay

class ExternalDisplayPresentation(
    context: Context,
    display: Display
) : Presentation(context, display), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep external screen awake
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize saved state registry before lifecycle events
        savedStateRegistryController.performRestore(savedInstanceState)

        // Transition lifecycle to CREATED
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val compose = ComposeView(context)
        // Manually attach LifecycleOwner and SavedStateRegistryOwner for Compose
        val viewTreeLifecycleOwnerClass = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
        val setLifecycleMethod = viewTreeLifecycleOwnerClass.methods.firstOrNull { it.name == "set" && it.parameterTypes.size == 2 }
        setLifecycleMethod?.invoke(null, compose, this)
        
        val viewTreeSavedStateRegistryOwnerClass = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
        val setSavedStateMethod = viewTreeSavedStateRegistryOwnerClass.methods.firstOrNull { it.name == "set" && it.parameterTypes.size == 2 }
        setSavedStateMethod?.invoke(null, compose, this)

        compose.setContent {
            AppTheme {
                FullScreenDisplay()
            }
        }
        setContentView(compose)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        // Promote directly to RESUMED since Presentation has no distinct onResume callback
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStop() {
        // Mirror Activity ordering: pause then stop
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun dismiss() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            // Move through destroy path if not already destroyed
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        super.dismiss()
    }

    override fun onSaveInstanceState(): Bundle {
        val bundle = super.onSaveInstanceState()
        savedStateRegistryController.performSave(bundle)
        return bundle
    }
}
