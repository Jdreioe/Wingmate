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
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.github.jdreioe.wingmate.presentation.UpdateManager
import io.github.jdreioe.wingmate.application.SettingsUseCase
import org.koin.dsl.module
import org.koin.core.context.loadKoinModules
import javax.imageio.ImageIO
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

fun main() {
    val log = LoggerFactory.getLogger("DesktopMain")
    
    // Initialize Koin for desktop
    initKoin(module { })
    
    // Register all desktop-specific implementations (repositories, services, etc.)
    overrideDesktopSpeechService()
    configureOpenSymbolsSecret()
    setupUpdateService()
    
    application {
        Window(
            onCloseRequest = { exitApplication() },
            title = "Wingmate",
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

private fun configureOpenSymbolsSecret() {
    val log = LoggerFactory.getLogger("DesktopMain")

    val resolvedSecret = listOf(
        resolveOpenSymbolsSecretFromInfisical(log),
        System.getenv("openSymbols"),
        System.getProperty("wingmate.opensymbols.secret"),
        loadLocalProperty("openSymbols"),
    ).firstOrNull { !it.isNullOrBlank() }

    OpenSymbolsClient.setSharedSecret(resolvedSecret)

    if (resolvedSecret.isNullOrBlank()) {
        log.warn(
            "OpenSymbols secret not configured. Configure Infisical (recommended), or set " +
                "opensymbols_secret env var, wingmate.opensymbols.secret JVM property, " +
                "or local.properties OPENSYMBOLS_SECRET."
        )
    } else {
        log.info("OpenSymbols secret configured from runtime config")
    }
}

private data class InfisicalConfig(
    val baseUrl: String,
    val projectId: String?,
    val projectSlug: String?,
    val environment: String,
    val secretName: String,
    val secretPath: String,
    val organizationSlug: String?,
    val accessToken: String?,
    val clientId: String?,
    val clientSecret: String?
)

private fun resolveOpenSymbolsSecretFromInfisical(log: org.slf4j.Logger): String? {
    val infisicalEnabled = readConfigValue(
        envKeys = listOf("WINGMATE_INFISICAL_ENABLED", "INFISICAL_ENABLED"),
        systemPropertyKeys = listOf("wingmate.infisical.enabled", "infisical.enabled"),
        localPropertyKeys = listOf("WINGMATE_INFISICAL_ENABLED", "INFISICAL_ENABLED")
    )?.equals("false", ignoreCase = true) != true

    if (!infisicalEnabled) return null

    val config = buildInfisicalConfig()

    if (config.projectId.isNullOrBlank() && config.projectSlug.isNullOrBlank()) {
        return null
    }

    if (config.accessToken.isNullOrBlank() && (config.clientId.isNullOrBlank() || config.clientSecret.isNullOrBlank())) {
        log.warn(
            "Infisical is partially configured. Provide INFISICAL_ACCESS_TOKEN, or INFISICAL_CLIENT_ID + INFISICAL_CLIENT_SECRET."
        )
        return null
    }

    val token = config.accessToken ?: loginToInfisical(config, log) ?: return null
    return fetchSecretFromInfisical(config, token, log)
}

private fun buildInfisicalConfig(): InfisicalConfig {
    val baseUrl = readConfigValue(
        envKeys = listOf("WINGMATE_INFISICAL_URL", "INFISICAL_URL"),
        systemPropertyKeys = listOf("wingmate.infisical.url", "infisical.url"),
        localPropertyKeys = listOf("WINGMATE_INFISICAL_URL", "INFISICAL_URL")
    ) ?: "https://app.infisical.com"

    val environment = readConfigValue(
        envKeys = listOf("WINGMATE_INFISICAL_ENV", "INFISICAL_ENV"),
        systemPropertyKeys = listOf("wingmate.infisical.env", "infisical.env"),
        localPropertyKeys = listOf("WINGMATE_INFISICAL_ENV", "INFISICAL_ENV")
    ) ?: "system_env"

    val secretName = readConfigValue(
        envKeys = listOf("WINGMATE_INFISICAL_SECRET_NAME", "INFISICAL_SECRET_NAME"),
        systemPropertyKeys = listOf("wingmate.infisical.secret.name", "infisical.secret.name"),
        localPropertyKeys = listOf("WINGMATE_INFISICAL_SECRET_NAME", "INFISICAL_SECRET_NAME")
    ) ?: "openSymbols"

    val secretPath = readConfigValue(
        envKeys = listOf("WINGMATE_INFISICAL_SECRET_PATH", "INFISICAL_SECRET_PATH"),
        systemPropertyKeys = listOf("wingmate.infisical.secret.path", "infisical.secret.path"),
        localPropertyKeys = listOf("WINGMATE_INFISICAL_SECRET_PATH", "INFISICAL_SECRET_PATH")
    ) ?: "/"

    return InfisicalConfig(
        baseUrl = baseUrl.trimEnd('/'),
        projectId = readConfigValue(
            envKeys = listOf("WINGMATE_INFISICAL_PROJECT_ID", "INFISICAL_PROJECT_ID"),
            systemPropertyKeys = listOf("wingmate.infisical.project.id", "infisical.project.id"),
            localPropertyKeys = listOf("WINGMATE_INFISICAL_PROJECT_ID", "INFISICAL_PROJECT_ID")
        ),
        projectSlug = readConfigValue(
            envKeys = listOf("WINGMATE_INFISICAL_PROJECT_SLUG", "INFISICAL_PROJECT_SLUG"),
            systemPropertyKeys = listOf("wingmate.infisical.project.slug", "infisical.project.slug"),
            localPropertyKeys = listOf("WINGMATE_INFISICAL_PROJECT_SLUG", "INFISICAL_PROJECT_SLUG")
        ),
        environment = environment,
        secretName = secretName,
        secretPath = secretPath,
        organizationSlug = readConfigValue(
            envKeys = listOf("WINGMATE_INFISICAL_ORGANIZATION_SLUG", "INFISICAL_ORGANIZATION_SLUG"),
            systemPropertyKeys = listOf("wingmate.infisical.organization.slug", "infisical.organization.slug"),
            localPropertyKeys = listOf("WINGMATE_INFISICAL_ORGANIZATION_SLUG", "INFISICAL_ORGANIZATION_SLUG")
        ),
        accessToken = readConfigValue(
            envKeys = listOf("WINGMATE_INFISICAL_ACCESS_TOKEN", "INFISICAL_ACCESS_TOKEN"),
            systemPropertyKeys = listOf("wingmate.infisical.access.token", "infisical.access.token"),
            localPropertyKeys = listOf("WINGMATE_INFISICAL_ACCESS_TOKEN", "INFISICAL_ACCESS_TOKEN")
        ),
        clientId = readConfigValue(
            envKeys = listOf("WINGMATE_INFISICAL_CLIENT_ID", "INFISICAL_CLIENT_ID"),
            systemPropertyKeys = listOf("wingmate.infisical.client.id", "infisical.client.id"),
            localPropertyKeys = listOf("WINGMATE_INFISICAL_CLIENT_ID", "INFISICAL_CLIENT_ID")
        ),
        clientSecret = readConfigValue(
            envKeys = listOf("WINGMATE_INFISICAL_CLIENT_SECRET", "INFISICAL_CLIENT_SECRET"),
            systemPropertyKeys = listOf("wingmate.infisical.client.secret", "infisical.client.secret"),
            localPropertyKeys = listOf("WINGMATE_INFISICAL_CLIENT_SECRET", "INFISICAL_CLIENT_SECRET")
        )
    )
}

private fun loginToInfisical(config: InfisicalConfig, log: org.slf4j.Logger): String? {
    val clientId = config.clientId ?: return null
    val clientSecret = config.clientSecret ?: return null

    val requestBody = buildJsonObject {
        put("clientId", JsonPrimitive(clientId))
        put("clientSecret", JsonPrimitive(clientSecret))
        config.organizationSlug?.let { put("organizationSlug", JsonPrimitive(it)) }
    }.toString()

    val request = HttpRequest.newBuilder()
        .uri(URI.create("${config.baseUrl}/api/v1/auth/universal-auth/login"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(8))
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()

    val response = runCatching {
        JdkHttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }.getOrElse {
        log.warn("Infisical login request failed: ${it.message}")
        return null
    }

    if (response.statusCode() !in 200..299) {
        log.warn("Infisical login failed with HTTP ${response.statusCode()}")
        return null
    }

    val json = runCatching {
        Json.parseToJsonElement(response.body()).jsonObject
    }.getOrElse {
        log.warn("Infisical login returned invalid JSON")
        return null
    }

    val token = json["accessToken"]?.jsonPrimitive?.contentOrNull
    if (token.isNullOrBlank()) {
        log.warn("Infisical login did not return accessToken")
    }
    return token
}

private fun fetchSecretFromInfisical(config: InfisicalConfig, accessToken: String, log: org.slf4j.Logger): String? {
    val queryParams = mutableListOf(
        "environment" to config.environment,
        "secretPath" to config.secretPath,
        "type" to "shared",
        "viewSecretValue" to "true"
    )

    config.projectId?.let { queryParams += "workspaceId" to it }
    if (config.projectId.isNullOrBlank()) {
        config.projectSlug?.let { queryParams += "workspaceSlug" to it }
    }

    val queryString = queryParams.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }

    val endpoint =
        "${config.baseUrl}/api/v3/secrets/raw/${urlEncode(config.secretName)}?$queryString"

    val request = HttpRequest.newBuilder()
        .uri(URI.create(endpoint))
        .header("Authorization", "Bearer $accessToken")
        .timeout(Duration.ofSeconds(8))
        .GET()
        .build()

    val response = runCatching {
        JdkHttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }.getOrElse {
        log.warn("Infisical secret request failed: ${it.message}")
        return null
    }

    if (response.statusCode() !in 200..299) {
        log.warn("Infisical secret fetch failed with HTTP ${response.statusCode()}")
        return null
    }

    val json = runCatching {
        Json.parseToJsonElement(response.body()).jsonObject
    }.getOrElse {
        log.warn("Infisical secret response was not valid JSON")
        return null
    }

    val secretValue = json["secret"]
        ?.jsonObject
        ?.get("secretValue")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()

    if (secretValue.isNullOrBlank()) {
        log.warn(
            "Infisical secret response missing secretValue for ${config.secretName} in ${config.environment}."
        )
        return null
    }

    log.info("OpenSymbols secret loaded from Infisical")
    return secretValue
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun readConfigValue(
    envKeys: List<String>,
    systemPropertyKeys: List<String>,
    localPropertyKeys: List<String>
): String? {
    val allCandidates = mutableListOf<String?>()

    envKeys.forEach { key -> allCandidates += System.getenv(key) }
    systemPropertyKeys.forEach { key -> allCandidates += System.getProperty(key) }
    localPropertyKeys.forEach { key -> allCandidates += loadLocalProperty(key) }

    return allCandidates
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
}

private fun loadLocalProperty(key: String): String? {
    val localPropertiesPath = Paths.get(System.getProperty("user.dir"), "local.properties")
    if (!Files.exists(localPropertiesPath)) return null

    return runCatching {
        Properties().apply {
            Files.newInputStream(localPropertiesPath).use { input -> load(input) }
        }.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
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
