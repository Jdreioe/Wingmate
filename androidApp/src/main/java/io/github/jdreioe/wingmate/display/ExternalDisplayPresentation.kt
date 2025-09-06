package io.github.jdreioe.wingmate.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import io.github.jdreioe.wingmate.ui.FullScreenDisplay
import io.github.jdreioe.wingmate.ui.AppTheme

class ExternalDisplayPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val compose = ComposeView(context).apply {
            setContent {
                AppTheme {
                    FullScreenDisplay()
                }
            }
        }
        setContentView(compose)
    }
}
