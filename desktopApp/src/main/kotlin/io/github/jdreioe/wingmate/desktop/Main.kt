package io.github.jdreioe.wingmate.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.jdreioe.wingmate.App
import io.github.jdreioe.wingmate.ui.DesktopTheme
import io.github.jdreioe.wingmate.initKoin
import org.koin.dsl.module
import org.koin.core.context.loadKoinModules
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main() {
    // Initialize Koin for desktop
    initKoin(module { })
    
    // Register desktop-specific implementations
    setupDesktopRepositories()
    
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Wingmate Desktop"
        ) {
            DesktopTheme {
                App()
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
    }.onFailure { t -> log.warn("Could not register DesktopSqlCategoryRepository", t) }
    
    runCatching {
        val repoClass = Class.forName("io.github.jdreioe.wingmate.infrastructure.DesktopSqlSettingsRepository")
        val repo = repoClass.getDeclaredConstructor().newInstance() as io.github.jdreioe.wingmate.domain.SettingsRepository
        loadKoinModules(module { single<io.github.jdreioe.wingmate.domain.SettingsRepository> { repo } })
        log.info("Registered DesktopSqlSettingsRepository")
    }.onFailure { t -> log.warn("Could not register DesktopSqlSettingsRepository", t) }
}
