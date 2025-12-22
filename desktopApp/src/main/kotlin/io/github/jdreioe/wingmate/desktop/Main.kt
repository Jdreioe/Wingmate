package io.github.jdreioe.wingmate.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.awt.GraphicsEnvironment
import org.slf4j.LoggerFactory
import io.github.jdreioe.wingmate.App
import io.github.jdreioe.wingmate.ui.DesktopTheme
import io.github.jdreioe.wingmate.initKoin
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.UpdateService
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
    
    // Register desktop-specific implementations
    io.github.jdreioe.wingmate.overrideDesktopSpeechService() // This registers TtsRepository via Koin
    setupDesktopRepositories()
    setupUpdateService()
    
    application {
        Window(
            onCloseRequest = { exitApplication() },
            title = "Wingmate Desktop",
            state = rememberWindowState()
        ) {
            val windowRef = this.window
            LaunchedEffect(windowRef) {
                setAppIcon(windowRef)
            }
            App()
        }

        // Full-screen display window driven by DisplayWindowBus
        val show by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsState(initial = false)
        if (show) {
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

private fun setupDesktopRepositories() {
    val log = LoggerFactory.getLogger("DesktopMain")
    
    // Register desktop-specific repositories
    runCatching {
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlConfigRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.ConfigRepository
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.ConfigRepository> { repo } })
        log.info("Registered DesktopSqlConfigRepository")
    }.onFailure { t -> log.warn("Could not register DesktopSqlConfigRepository", t) }
    
    runCatching {
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlPhraseRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.PhraseRepository
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.PhraseRepository> { repo } })
        log.info("Registered DesktopSqlPhraseRepository")
    }.onFailure { t -> log.warn("Could not register DesktopSqlPhraseRepository", t) }
    
    runCatching {
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlCategoryRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.CategoryRepository
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.CategoryRepository> { repo } })
        log.info("Registered DesktopSqlCategoryRepository")
    }.onFailure { t -> 
        log.warn("Could not register DesktopSqlCategoryRepository", t)
    }
    
    runCatching {
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.SettingsRepository
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.SettingsRepository> { repo } })
        log.info("Registered DesktopSqlSettingsRepository")
    }.onFailure { t -> log.warn("Could not register DesktopSqlSettingsRepository", t) }

    // Voice repository (SQLite) for caching voices and persisting selected voice
    runCatching {
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlVoiceRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.VoiceRepository
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.VoiceRepository> { repo } })
        log.info("Registered DesktopSqlVoiceRepository")
    }.onFailure { t -> log.warn("Could not register DesktopSqlVoiceRepository", t) }

    // Said text history repository (SQLite)
    runCatching {
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlSaidTextRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.SaidTextRepository
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.SaidTextRepository> { repo } })
        log.info("Registered DesktopSqlSaidTextRepository")
    }.onFailure { t -> log.warn("Could not register DesktopSqlSaidTextRepository", t) }
    
    // Audio clipboard for desktop
    runCatching {
        val clipboardClass = Class.forName("io.github.jdreioe.wingmate.platform.DesktopAudioClipboard")
        val clipboard = clipboardClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.platform.AudioClipboard
        loadKoinModules(module { single<io.github.jdreioe.wingmate.platform.AudioClipboard> { clipboard } })
        log.info("Registered DesktopAudioClipboard")
    }.onFailure { t -> log.warn("Could not register DesktopAudioClipboard", t) }
    
    // Share service for desktop
    runCatching {
        val shareClass = Class.forName("io.github.jdreioe.wingmate.platform.DesktopShareService")
        val share = shareClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.platform.ShareService
        loadKoinModules(module { single<io.github.jdreioe.wingmate.platform.ShareService> { share } })
        log.info("Registered DesktopShareService")
    }.onFailure { t -> log.warn("Could not register DesktopShareService", t) }
    
    // Text prediction service (n-gram based)
    runCatching {
        val predictionClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService")
        val prediction = predictionClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.TextPredictionService
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.TextPredictionService> { prediction } })
        log.info("Registered SimpleNGramPredictionService")
    }.onFailure { t -> log.warn("Could not register SimpleNGramPredictionService", t) }
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
