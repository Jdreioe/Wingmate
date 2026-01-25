package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

class DesktopUpdateService(
    private val httpClient: HttpClient,
    private val gitHubApiClient: GitHubApiClient
) : UpdateService {
    
    private val log = LoggerFactory.getLogger("DesktopUpdateService")
    
    // GitHub repository configuration
    private val repoOwner = "Jdreioe"
    private val repoName = "Wingmate"
    
    // Current version - should be updated with each release
    private val currentVersion = AppVersion.parse(getCurrentVersionFromManifest() ?: "1.0.0")
    
    private val updateStatus = AtomicReference(UpdateStatus.UP_TO_DATE)
    
    override suspend fun checkForUpdates(): UpdateInfo? {
        setUpdateStatus(UpdateStatus.CHECKING)
        log.info("Checking for updates from GitHub...")
        
        return try {
            val updateInfo = gitHubApiClient.getLatestRelease(repoOwner, repoName)
            
            if (updateInfo != null && updateInfo.version.isNewerThan(currentVersion)) {
                log.info("Update available: ${updateInfo.version.version}")
                setUpdateStatus(UpdateStatus.AVAILABLE)
                updateInfo
            } else {
                log.info("No updates available")
                setUpdateStatus(UpdateStatus.UP_TO_DATE)
                null
            }
        } catch (e: Exception) {
            log.error("Failed to check for updates", e)
            setUpdateStatus(UpdateStatus.ERROR)
            null
        }
    }
    
    override suspend fun downloadUpdate(updateInfo: UpdateInfo): Result<String> {
        setUpdateStatus(UpdateStatus.DOWNLOADING)
        log.info("Downloading update: ${updateInfo.assetName}")
        
        return try {
            val downloadDir = getDownloadDirectory()
            val downloadFile = File(downloadDir, updateInfo.assetName)
            
            // Ensure download directory exists
            downloadDir.mkdirs()
            
            val response: HttpResponse = httpClient.get(updateInfo.downloadUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    val progress = if (contentLength > 0) {
                        (bytesSentTotal * 100 / contentLength).toInt()
                    } else 0
                    log.debug("Download progress: $progress%")
                }
            }
            
            val channel: ByteReadChannel = response.body()
            downloadFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead <= 0) break
                    output.write(buffer, 0, bytesRead)
                }
            }
            
            log.info("Download completed: ${downloadFile.absolutePath}")
            setUpdateStatus(UpdateStatus.DOWNLOADED)
            Result.success(downloadFile.absolutePath)
            
        } catch (e: Exception) {
            log.error("Failed to download update", e)
            setUpdateStatus(UpdateStatus.ERROR)
            Result.failure(e)
        }
    }
    
    override suspend fun installUpdate(downloadPath: String): Result<Unit> {
        setUpdateStatus(UpdateStatus.INSTALLING)
        log.info("Installing update from: $downloadPath")
        
        return try {
            val downloadFile = File(downloadPath)
            if (!downloadFile.exists()) {
                throw IOException("Downloaded file not found: $downloadPath")
            }
            
            when {
                downloadPath.endsWith(".jar") -> installJarUpdate(downloadFile)
                downloadPath.endsWith(".deb") -> installDebUpdate(downloadFile)
                downloadPath.endsWith(".rpm") -> installRpmUpdate(downloadFile)
                downloadPath.endsWith(".exe") -> installExeUpdate(downloadFile)
                downloadPath.endsWith(".dmg") -> installDmgUpdate(downloadFile)
                else -> throw IllegalArgumentException("Unsupported update file format")
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Failed to install update", e)
            setUpdateStatus(UpdateStatus.ERROR)
            Result.failure(e)
        }
    }
    
    override fun getCurrentVersion(): AppVersion = currentVersion
    
    override suspend fun getUpdateStatus(): UpdateStatus = updateStatus.get()
    
    override suspend fun setUpdateStatus(status: UpdateStatus) {
        updateStatus.set(status)
    }
    
    private fun getDownloadDirectory(): File {
        val userHome = System.getProperty("user.home")
        return File(userHome, ".wingmate/updates")
    }
    
    private suspend fun installJarUpdate(jarFile: File) {
        // For JAR updates, we need to replace the current JAR and restart
        val currentJar = getCurrentJarPath()
        if (currentJar != null) {
            // Create a script to replace the JAR and restart the app
            createUpdateScript(jarFile, currentJar)
        } else {
            throw IOException("Could not determine current JAR path")
        }
    }
    
    private suspend fun installDebUpdate(debFile: File) {
        val process = ProcessBuilder("pkexec", "dpkg", "-i", debFile.absolutePath)
            .inheritIO()
            .start()
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Failed to install .deb package, exit code: $exitCode")
        }
        
        // Exit the current application
        delay(1000)
        exitProcess(0)
    }
    
    private suspend fun installRpmUpdate(rpmFile: File) {
        val process = ProcessBuilder("pkexec", "rpm", "-U", rpmFile.absolutePath)
            .inheritIO()
            .start()
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Failed to install .rpm package, exit code: $exitCode")
        }
        
        // Exit the current application
        delay(1000)
        exitProcess(0)
    }
    
    private suspend fun installExeUpdate(exeFile: File) {
        // On Windows, run the installer
        val process = ProcessBuilder(exeFile.absolutePath, "/S") // Silent install
            .inheritIO()
            .start()
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Failed to install .exe package, exit code: $exitCode")
        }
        
        // Exit the current application
        delay(1000)
        exitProcess(0)
    }
    
    private suspend fun installDmgUpdate(dmgFile: File) {
        // On macOS, mount the DMG and copy the app
        val mountPoint = "/tmp/wingmate_update"
        val mountProcess = ProcessBuilder("hdiutil", "attach", dmgFile.absolutePath, "-mountpoint", mountPoint)
            .inheritIO()
            .start()
        
        if (mountProcess.waitFor() != 0) {
            throw IOException("Failed to mount DMG")
        }
        
        try {
            // Copy the app to Applications
            val copyProcess = ProcessBuilder("cp", "-R", "$mountPoint/Wingmate.app", "/Applications/")
                .inheritIO()
                .start()
            
            if (copyProcess.waitFor() != 0) {
                throw IOException("Failed to copy application")
            }
        } finally {
            // Unmount the DMG
            ProcessBuilder("hdiutil", "detach", mountPoint).start().waitFor()
        }
        
        // Exit the current application
        delay(1000)
        exitProcess(0)
    }
    
    private fun getCurrentJarPath(): File? {
        try {
            val protectionDomain = DesktopUpdateService::class.java.protectionDomain
            if (protectionDomain?.codeSource?.location != null) {
                val jarPath = protectionDomain.codeSource?.location?.toURI()?.path
                if (jarPath != null) {
                    val jarFile = File(jarPath)
                    return if (jarFile.exists() && jarFile.name.endsWith(".jar")) jarFile else null
                }
            }
            return null
        } catch (e: Exception) {
            log.warn("Could not determine JAR path", e)
            return null
        }
    }
    
    private suspend fun createUpdateScript(newJar: File, currentJar: File) {
        val scriptFile = File(getDownloadDirectory(), "update.sh")
        
        val scriptContent = """
            #!/bin/bash
            echo "Starting update process..."
            sleep 2
            
            # Backup current JAR
            cp "${currentJar.absolutePath}" "${currentJar.absolutePath}.backup"
            
            # Replace with new JAR
            cp "${newJar.absolutePath}" "${currentJar.absolutePath}"
            
            # Start the new application
            java -jar "${currentJar.absolutePath}" &
            
            echo "Update completed successfully"
        """.trimIndent()
        
        scriptFile.writeText(scriptContent)
        scriptFile.setExecutable(true)
        
        // Execute the script and exit
        ProcessBuilder("bash", scriptFile.absolutePath).start()
        delay(1000)
        exitProcess(0)
    }
    
    private fun getCurrentVersionFromManifest(): String? {
        return try {
            val packageName = this::class.java.`package`?.implementationVersion
            packageName
        } catch (e: Exception) {
            log.warn("Could not read version from manifest", e)
            null
        }
    }
}