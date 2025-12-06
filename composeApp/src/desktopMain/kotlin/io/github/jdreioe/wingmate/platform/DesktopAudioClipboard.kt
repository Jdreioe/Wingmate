package io.github.jdreioe.wingmate.platform

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopAudioClipboard : AudioClipboard {
    override fun copyAudioFile(filePath: String): Boolean {
        return runCatching {
            val file = File(filePath)
            if (!file.exists()) {
                println("Audio file does not exist: $filePath")
                return false
            }
            
            // For Linux/desktop, copy file to a standard location that apps can access
            val downloadsDir = File(System.getProperty("user.home"), "Downloads")
            val tempDir = File(downloadsDir, "wingmate_clips")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            
            // Create a unique filename with timestamp
            val timestamp = System.currentTimeMillis()
            val extension = file.extension
            val destFile = File(tempDir, "taleklip_${timestamp}.${extension}")
            
            // Copy the file
            Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("Copied audio file to: ${destFile.absolutePath}")
            
            println("[INFO] Audio file ready: ${destFile.absolutePath}")
            
            // Open file manager with the file selected for easy drag-and-drop to Messenger
            var fileManagerOpened = false
            try {
                val pb = when {
                    isCommandAvailable("dolphin") -> ProcessBuilder("dolphin", "--select", destFile.absolutePath)
                    isCommandAvailable("nautilus") -> ProcessBuilder("nautilus", "--select", destFile.absolutePath)
                    isCommandAvailable("nemo") -> ProcessBuilder("nemo", destFile.absolutePath)
                    isCommandAvailable("thunar") -> ProcessBuilder("thunar", "--select", destFile.absolutePath)
                    isCommandAvailable("pcmanfm") -> ProcessBuilder("pcmanfm", tempDir.absolutePath)
                    isCommandAvailable("xdg-open") -> ProcessBuilder("xdg-open", tempDir.absolutePath)
                    else -> null
                }
                
                pb?.let {
                    it.start()
                    fileManagerOpened = true
                    println("[SUCCESS] Opened file manager showing: ${destFile.name}")
                }
            } catch (e: Exception) {
                println("[ERROR] Could not open file manager: ${e.message}")
            }
            
            // Show desktop notification
            try {
                if (isCommandAvailable("notify-send")) {
                    ProcessBuilder(
                        "notify-send",
                        "-u", "normal",
                        "-t", "5000",
                        "Audio File Ready",
                        "Drag ${destFile.name} to Messenger\nLocation: ${tempDir.absolutePath}"
                    ).start()
                }
            } catch (e: Exception) {
                println("[DEBUG] Could not send notification: ${e.message}")
            }
            
            if (!fileManagerOpened) {
                println("[INFO] File saved to: ${destFile.absolutePath}")
                println("[INFO] Manually navigate to ~/Downloads/wingmate_clips/ to find your audio file")
            }
            
            true
        }.getOrElse { e ->
            println("Failed to copy audio file: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun isCommandAvailable(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        }.getOrElse { false }
    }
    
    /**
     * Transferable implementation for file lists
     */
    private class FileTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> {
            return arrayOf(DataFlavor.javaFileListFlavor)
        }
        
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean {
            return flavor == DataFlavor.javaFileListFlavor
        }
        
        @Throws(UnsupportedFlavorException::class, IOException::class)
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) {
                throw UnsupportedFlavorException(flavor)
            }
            return files
        }
    }
}
