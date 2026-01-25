package io.github.jdreioe.wingmate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import java.awt.GraphicsEnvironment
import org.slf4j.LoggerFactory
import io.github.jdreioe.wingmate.App
import io.github.jdreioe.wingmate.ui.DesktopTheme
import io.github.jdreioe.wingmate.initKoin
import io.github.jdreioe.wingmate.overrideDesktopSpeechService
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.UpdateService
import io.github.jdreioe.wingmate.infrastructure.DesktopSpeechService
import io.github.jdreioe.wingmate.infrastructure.DesktopUpdateService
import io.github.jdreioe.wingmate.infrastructure.GitHubApiClient
import io.github.jdreioe.wingmate.presentation.UpdateManager
import io.github.jdreioe.wingmate.application.SettingsUseCase
import org.koin.dsl.module
import org.koin.core.context.loadKoinModules
import org.koin.dsl.single
import javax.imageio.ImageIO
import androidx.compose.runtime.LaunchedEffect
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

fun main() {
    val log = LoggerFactory.getLogger("DesktopMain")
    
    // Initialize Koin for desktop
    initKoin(module { })
    
    // Register all desktop-specific implementations (repositories, services, etc.)
    overrideDesktopSpeechService()
    setupUpdateService()
    
    application {
        Window(
            onCloseRequest = { exitApplication() },
            title = "Wingmate Desktop",
            resizable = true,
            state = rememberWindowState()
        ) {
            val windowRef = this.window
            LaunchedEffect(windowRef) {
                setAppIcon(windowRef)
            }
            // Ensure content fills the entire window
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color(0xFF121217))
            ) {
                App()
            }
        }

        // Full-screen display window driven by DisplayWindowBus
        // Only show when explicitly requested via the fullscreen button
        val showDisplay by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsState()
        // Add a startup guard to prevent showing during initial composition
        var hasInitialized by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(500)
            hasInitialized = true
        }
        if (showDisplay && hasInitialized) {
            // Determine a secondary screen if available
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val screens = ge.screenDevices
            val target = screens.firstOrNull { it != ge.defaultScreenDevice } ?: screens.firstOrNull()

            val screenBounds = target?.defaultConfiguration?.bounds
            Window(
                onCloseRequest = { io.github.jdreioe.wingmate.presentation.DisplayWindowBus.close() },
                undecorated = true,
                resizable = false,
                alwaysOnTop = false,
                title = "Display",
                // use default state; we'll move/size the AWT window below
            ) {
                io.github.jdreioe.wingmate.ui.FullScreenDisplay()
                // Size and place to target screen bounds
                (this.window as? ComposeWindow)?.apply {
                    if (screenBounds != null) {
                        bounds = screenBounds
                    } else {
                        // maximize on primary if no secondary
                        isVisible = true
                    }
                    isVisible = true
                }
            }
        }
    }
}



private fun setupUpdateService() {
    val log = LoggerFactory.getLogger("DesktopMain")
    
    runCatching {
        loadKoinModules(
            module {
                single {
                    HttpClient(OkHttp) {
                        install(ContentNegotiation) {
                            json()
                        }
                    }
                }
                single { GitHubApiClient(get()) }
                single<UpdateService> { DesktopUpdateService(get(), get()) }
                single { UpdateManager(get(), get<SettingsUseCase>()) }
            }
        )
        log.info("Registered UpdateService successfully")
    }.onFailure { t -> 
        log.error("Failed to register UpdateService", t) 
    }
}

fun setAppIcon(window: java.awt.Window) {
    try {
        val iconStream = object {}.javaClass.getResourceAsStream("/app-icon.png")
        if (iconStream != null) {
            val icon = ImageIO.read(iconStream)
            (window as? java.awt.Frame)?.iconImage = icon
        }
    } catch (_: Throwable) {}
}
