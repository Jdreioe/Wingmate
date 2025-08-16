package io.github.jdreioe.wingmate

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import io.github.jdreioe.wingmate.ui.WelcomeScreen
import io.github.jdreioe.wingmate.ui.PhraseScreen
import io.github.jdreioe.wingmate.ui.DesktopTheme
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlinx.coroutines.runBlocking
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase

private enum class Screen { Welcome, Phrases }

fun main() {
    // Enable SLF4J SimpleLogger debug output for desktop runs
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
    System.setProperty("org.slf4j.simpleLogger.showThreadName", "true")
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
    System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss")
    // Start shared DI once before the Compose application starts so platform overrides can load safely
    io.github.jdreioe.wingmate.initKoin(null)
    // Ensure desktop speech service is registered
    overrideDesktopSpeechService()

    // Defensive: try to register a JVM SQLite-backed ConfigRepository if available at runtime.
    runCatching {
        val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlConfigRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.ConfigRepository
    // Note: the 'override' parameter on module() is deprecated. If you need to allow overrides,
    // set allowOverride when starting the KoinApplication (see Koin docs). Here we register
    // the reflective repository module without the deprecated parameter.
    loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.ConfigRepository> { repo } })
        log.info("Registered DesktopSqlConfigRepository into Koin (reflective)")

        // Verify registration: try to resolve and log the actual implementation class
        val koin = org.koin.core.context.GlobalContext.getOrNull()
        val resolved = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.ConfigRepository>() }.getOrNull() }
        log.info("ConfigRepository after reflective registration: {}", resolved?.javaClass?.name ?: "<none>")
    }.onFailure { t ->
        val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
        log.warn("Could not reflectively register DesktopSqlConfigRepository", t)
    }

    // Defensive: also try to register a JVM SQLite-backed PhraseRepository if available.
    runCatching {
        val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlPhraseRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.PhraseRepository
    // See note above about allowOverride. Registering module without deprecated 'override' arg.
    loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.PhraseRepository> { repo } })
        log.info("Registered DesktopSqlPhraseRepository into Koin (reflective)")

        val koin = org.koin.core.context.GlobalContext.getOrNull()
        val resolved = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.PhraseRepository>() }.getOrNull() }
        log.info("PhraseRepository after reflective registration: {}", resolved?.javaClass?.name ?: "<none>")
    }.onFailure { t ->
        val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
        log.warn("Could not reflectively register DesktopSqlPhraseRepository", t)
    }
        // Defensive: try to register a JVM SQLite-backed CategoryRepository if available.
        runCatching {
            val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
            val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlCategoryRepository")
            val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.CategoryRepository
            loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.CategoryRepository> { repo } })
            log.info("Registered DesktopSqlCategoryRepository into Koin (reflective)")

            val koin = org.koin.core.context.GlobalContext.getOrNull()
            val resolved = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.CategoryRepository>() }.getOrNull() }
            log.info("CategoryRepository after reflective registration: {}", resolved?.javaClass?.name ?: "<none>")
        }.onFailure { t ->
            val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
            log.warn("Could not reflectively register DesktopSqlCategoryRepository", t)
        }

    // Defensive: try to register a JVM SQLite-backed SettingsRepository if available.
    runCatching {
        val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.SettingsRepository
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.SettingsRepository> { repo } })
        log.info("Registered DesktopSqlSettingsRepository into Koin (reflective)")

        val koin = org.koin.core.context.GlobalContext.getOrNull()
        val resolved = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        log.info("SettingsRepository after reflective registration: {}", resolved?.javaClass?.name ?: "<none>")
    }.onFailure { t ->
        val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
        log.warn("Could not reflectively register DesktopSqlSettingsRepository", t)
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "Wingmate Desktop") {
            // Start with the welcome screen only if no voice is selected. Otherwise go straight to phrases.
            var currentScreen by remember { mutableStateOf(Screen.Welcome) }
            // Determine initial screen based on whether a voice is selected (blocking small startup check)
            LaunchedEffect(Unit) {
                val koin = org.koin.core.context.GlobalContext.getOrNull()
                val voiceUseCase = koin?.let { runCatching { it.get<VoiceUseCase>() }.getOrNull() }
                if (voiceUseCase != null) {
                    try {
                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                        if (selected != null) currentScreen = Screen.Phrases
                    } catch (_: Throwable) {}
                }
            }

            // Wrap the whole app content in DesktopTheme so Material3 is applied from launch
            DesktopTheme() {
                // Paint the window background with the theme background so screens that don't
                // provide their own Surface will match the selected light/dark theme.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (currentScreen) {
                        Screen.Welcome -> WelcomeScreen(onContinue = { currentScreen = Screen.Phrases })
                        Screen.Phrases -> PhraseScreen()
                    }
                }
            }
        }
    }

    // Reconcile persisted settings and selected voice so primaryLanguage and voice.selectedLanguage match
    runCatching {
        val log = org.slf4j.LoggerFactory.getLogger("ComposeMain")
        val koin = org.koin.core.context.GlobalContext.getOrNull()
        val settingsUseCase = koin?.let { runCatching { it.get<SettingsUseCase>() }.getOrNull() }
        val voiceUseCase = koin?.let { runCatching { it.get<VoiceUseCase>() }.getOrNull() }
        if (settingsUseCase != null && voiceUseCase != null) {
            runBlocking {
                try {
                    val s = runCatching { settingsUseCase.get() }.getOrNull()
                    val v = runCatching { voiceUseCase.selected() }.getOrNull()
                    if (s != null && v != null) {
                        val primary = s.primaryLanguage
                        if (!primary.isNullOrBlank() && v.selectedLanguage != primary) {
                            log.info("Reconciling selected voice language: setting '{}' -> voice '{}'", primary, v.name)
                            voiceUseCase.select(v.copy(selectedLanguage = primary))
                        }
                    }
                } catch (t: Throwable) {
                    log.warn("Failed to reconcile settings and voice", t)
                }
            }
        }
    }.onFailure { t -> org.slf4j.LoggerFactory.getLogger("ComposeMain").warn("Reconcile step failed", t) }
}

// Use the shared `PhraseScreen` from common UI (no local duplicate implementation)
