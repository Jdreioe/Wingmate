package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.UpdateInfo
import io.github.jdreioe.wingmate.domain.AppVersion
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String,
    val published_at: String,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long
)

class GitHubApiClient(private val httpClient: HttpClient) {
    private val json = Json { 
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    
    suspend fun getLatestRelease(owner: String, repo: String): UpdateInfo? {
        return try {
            val response: HttpResponse = httpClient.get("https://api.github.com/repos/$owner/$repo/releases/latest")
            
            if (response.status == HttpStatusCode.OK) {
                val releaseJson = response.bodyAsText()
                val release = json.decodeFromString<GitHubRelease>(releaseJson)
                
                // Find the appropriate asset for the current platform
                val platformAsset = findPlatformAsset(release.assets)
                
                if (platformAsset != null) {
                    UpdateInfo(
                        version = AppVersion.parse(release.tag_name),
                        downloadUrl = platformAsset.browser_download_url,
                        releaseNotes = release.body,
                        publishedAt = release.published_at,
                        assetName = platformAsset.name,
                        assetSize = platformAsset.size
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun findPlatformAsset(assets: List<GitHubAsset>): GitHubAsset? {
        // For desktop, look for .jar, .deb, .rpm, .dmg, .exe files
        // Priority: platform-specific > jar > generic
        
        val platformExtensions = when (getCurrentPlatform()) {
            "linux" -> listOf(".deb", ".rpm", ".jar")
            "windows" -> listOf(".exe", ".msi", ".jar")
            "macos" -> listOf(".dmg", ".pkg", ".jar")
            else -> listOf(".jar")
        }
        
        for (extension in platformExtensions) {
            val asset = assets.find { it.name.endsWith(extension, ignoreCase = true) }
            if (asset != null) return asset
        }
        
        // Fallback to any asset
        return assets.firstOrNull()
    }
    
    private fun getCurrentPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("linux") -> "linux"
            os.contains("windows") -> "windows"
            os.contains("mac") -> "macos"
            else -> "unknown"
        }
    }
}