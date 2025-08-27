package io.github.jdreioe.wingmate.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object DesktopPaths {
    fun configDir(): Path {
        val os = System.getProperty("os.name").lowercase()
        val dir: Path = when {
            os.contains("mac") -> Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Wingmate")
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming").toString()
                Paths.get(appData, "Wingmate")
            }
            else -> Paths.get(System.getProperty("user.home"), ".config", "wingmate")
        }
        if (!Files.exists(dir)) Files.createDirectories(dir)
        return dir
    }

    fun dataDir(): Path {
        val os = System.getProperty("os.name").lowercase()
        val dir: Path = when {
            os.contains("mac") -> Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Wingmate")
            os.contains("win") -> {
                val local = System.getenv("LOCALAPPDATA") ?: Paths.get(System.getProperty("user.home"), "AppData", "Local").toString()
                Paths.get(local, "Wingmate")
            }
            else -> Paths.get(System.getProperty("user.home"), ".local", "share", "wingmate")
        }
        if (!Files.exists(dir)) Files.createDirectories(dir)
        return dir
    }
}
