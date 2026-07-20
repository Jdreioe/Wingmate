import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.Properties
import org.gradle.api.tasks.Sync

fun toBuildConfigStringLiteral(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

fun resolveOpenSymbolsSecretFromInfisical(): String? {
    val enabled = readConfigValue(
        listOf("WINGMATE_INFISICAL_ENABLED", "INFISICAL_ENABLED"),
        listOf("wingmate.infisical.enabled", "infisical.enabled"),
        listOf("WINGMATE_INFISICAL_ENABLED", "INFISICAL_ENABLED")
    )?.equals("false", ignoreCase = true) != true
    if (!enabled) return null

    val baseUrl = (readConfigValue(
        listOf("WINGMATE_INFISICAL_URL", "INFISICAL_URL"),
        listOf("wingmate.infisical.url", "infisical.url"),
        listOf("WINGMATE_INFISICAL_URL", "INFISICAL_URL")
    ) ?: "https://app.infisical.eu").trimEnd('/')

    val environment = readConfigValue(
        listOf("WINGMATE_INFISICAL_ENV", "INFISICAL_ENV"),
        listOf("wingmate.infisical.env", "infisical.env"),
        listOf("WINGMATE_INFISICAL_ENV", "INFISICAL_ENV")
    ) ?: "system_env"

    val secretName = readConfigValue(
        listOf("WINGMATE_INFISICAL_SECRET_NAME", "INFISICAL_SECRET_NAME"),
        listOf("wingmate.infisical.secret.name", "infisical.secret.name"),
        listOf("WINGMATE_INFISICAL_SECRET_NAME", "INFISICAL_SECRET_NAME")
    ) ?: "openSymbols"

    val secretPath = readConfigValue(
        listOf("WINGMATE_INFISICAL_SECRET_PATH", "INFISICAL_SECRET_PATH"),
        listOf("wingmate.infisical.secret.path", "infisical.secret.path"),
        listOf("WINGMATE_INFISICAL_SECRET_PATH", "INFISICAL_SECRET_PATH")
    ) ?: "/"

    val projectId = readConfigValue(
        listOf("WINGMATE_INFISICAL_PROJECT_ID", "INFISICAL_PROJECT_ID"),
        listOf("wingmate.infisical.project.id", "infisical.project.id"),
        listOf("WINGMATE_INFISICAL_PROJECT_ID", "INFISICAL_PROJECT_ID")
    )
    val projectSlug = readConfigValue(
        listOf("WINGMATE_INFISICAL_PROJECT_SLUG", "INFISICAL_PROJECT_SLUG"),
        listOf("wingmate.infisical.project.slug", "infisical.project.slug"),
        listOf("WINGMATE_INFISICAL_PROJECT_SLUG", "INFISICAL_PROJECT_SLUG")
    )
    if (projectId.isNullOrBlank() && projectSlug.isNullOrBlank()) return null

    val accessToken = readConfigValue(
        listOf("WINGMATE_INFISICAL_ACCESS_TOKEN", "INFISICAL_ACCESS_TOKEN"),
        listOf("wingmate.infisical.access.token", "infisical.access.token"),
        listOf("WINGMATE_INFISICAL_ACCESS_TOKEN", "INFISICAL_ACCESS_TOKEN")
    )
    val clientId = readConfigValue(
        listOf("WINGMATE_INFISICAL_CLIENT_ID", "INFISICAL_CLIENT_ID"),
        listOf("wingmate.infisical.client.id", "infisical.client.id"),
        listOf("WINGMATE_INFISICAL_CLIENT_ID", "INFISICAL_CLIENT_ID")
    )
    val clientSecret = readConfigValue(
        listOf("WINGMATE_INFISICAL_CLIENT_SECRET", "INFISICAL_CLIENT_SECRET"),
        listOf("wingmate.infisical.client.secret", "infisical.client.secret"),
        listOf("WINGMATE_INFISICAL_CLIENT_SECRET", "INFISICAL_CLIENT_SECRET")
    )

    val token = when {
        !accessToken.isNullOrBlank() -> accessToken
        !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank() -> {
            loginToInfisical(baseUrl, clientId, clientSecret, readConfigValue(
                listOf("WINGMATE_INFISICAL_ORGANIZATION_SLUG", "INFISICAL_ORGANIZATION_SLUG"),
                listOf("wingmate.infisical.organization.slug", "infisical.organization.slug"),
                listOf("WINGMATE_INFISICAL_ORGANIZATION_SLUG", "INFISICAL_ORGANIZATION_SLUG")
            ))
        }
        else -> null
    }
    if (token.isNullOrBlank()) return null

    return fetchSecretFromInfisical(baseUrl, projectId, projectSlug, environment, secretName, secretPath, token)
}

private fun loginToInfisical(baseUrl: String, clientId: String, clientSecret: String, organizationSlug: String?): String? {
    val body = buildString {
        append("{\"clientId\":\"${escaped(clientId)}\",\"clientSecret\":\"${escaped(clientSecret)}\"")
        if (!organizationSlug.isNullOrBlank()) {
            append(",\"organizationSlug\":\"${escaped(organizationSlug)}\"")
        }
        append("}")
    }
    val request = HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl/api/v1/auth/universal-auth/login"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(8))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = runCatching {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }.getOrNull() ?: return null
    if (response.statusCode() !in 200..299) return null
    return extractJsonValue(response.body(), "accessToken")
}

private fun fetchSecretFromInfisical(
    baseUrl: String, projectId: String?, projectSlug: String?,
    environment: String, secretName: String, secretPath: String, accessToken: String
): String? {
    val params = mutableListOf("environment=$environment", "secretPath=$secretPath", "type=shared", "viewSecretValue=true")
    if (!projectId.isNullOrBlank()) params.add("workspaceId=$projectId")
    if (projectId.isNullOrBlank() && !projectSlug.isNullOrBlank()) params.add("workspaceSlug=$projectSlug")
    val request = HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl/api/v3/secrets/raw/${urlEncoded(secretName)}?${params.joinToString("&")}"))
        .header("Authorization", "Bearer $accessToken")
        .timeout(Duration.ofSeconds(8))
        .GET()
        .build()
    val response = runCatching {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }.getOrNull() ?: return null
    if (response.statusCode() !in 200..299) return null
    return extractJsonValue(response.body(), "secretValue")
}

private fun readConfigValue(envKeys: List<String>, systemPropertyKeys: List<String>, localPropertyKeys: List<String>): String? {
    val localProperties = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    for (key in envKeys) { System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it.trim() } }
    for (key in systemPropertyKeys) { System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it.trim() } }
    for (key in localPropertyKeys) { localProperties.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it.trim() } }
    return null
}

private fun extractJsonValue(json: String, key: String): String? {
    val search = "\"$key\":\""
    val start = json.indexOf(search)
    if (start < 0) return null
    val valueStart = start + search.length
    val valueEnd = json.indexOf('"', valueStart)
    if (valueEnd < 0) return null
    return json.substring(valueStart, valueEnd)
}

private fun escaped(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun urlEncoded(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.github.triplet.play")
}

// CI/CD: decode upload keystore and Play Console service account
// from env vars (injected by `infisical run` in CI pipelines).
val ciKeystoreBase64 = System.getenv("WINGMATE_KEYSTORE_BASE64")
if (!ciKeystoreBase64.isNullOrBlank()) {
    val f = file("$buildDir/tmp/keystore/release.keystore")
    f.parentFile.mkdirs()
    f.writeBytes(Base64.getDecoder().decode(ciKeystoreBase64))
}

val ciServiceAccountBase64 = System.getenv("WINGMATE_PLAY_SERVICE_ACCOUNT_JSON")
if (!ciServiceAccountBase64.isNullOrBlank()) {
    val f = file("$buildDir/tmp/play/service-account.json")
    f.parentFile.mkdirs()
    f.writeText(String(Base64.getDecoder().decode(ciServiceAccountBase64), Charsets.UTF_8))
}

android {
    namespace = "com.hojmoseit.wingmate"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    val versionPropsFile = project.file("../version.properties")
    val versionProps = Properties()
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { versionProps.load(it) }
    }
    val vCode = (versionProps.getProperty("versionCode") ?: "1").toInt()
    val vName = versionProps.getProperty("versionName") ?: "1.0"

    val openSymbolsSecret = providers.provider { resolveOpenSymbolsSecretFromInfisical() }
        .orElse(providers.environmentVariable("WINGMATE_OPENSYMBOLS_SECRET"))
        .orElse(providers.environmentVariable("OPENSYMBOLS_SECRET"))
        .orElse(providers.environmentVariable("openSymbols"))
        .orElse(providers.provider {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use(localProperties::load)
            }
            sequenceOf(
                localProperties.getProperty("WINGMATE_OPENSYMBOLS_SECRET"),
                localProperties.getProperty("OPENSYMBOLS_SECRET"),
                localProperties.getProperty("openSymbols")
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        })
        .orElse("")

    defaultConfig {
        applicationId = "com.hojmoseit.wingmate"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = vCode
        versionName = vName
        buildConfigField(
            "String",
            "OPENSYMBOLS_SECRET",
            toBuildConfigStringLiteral(openSymbolsSecret.get())
        )
    }

    tasks.register("incrementVersionCode") {
        doLast {
            val currentCode = versionProps.getProperty("versionCode")?.toInt() ?: 1
            versionProps.setProperty("versionCode", (currentCode + 1).toString())
            versionPropsFile.outputStream().use { versionProps.store(it, "Auto-incremented by build") }
            println("Version code incremented to ${currentCode + 1}")
        }
    }

    tasks.configureEach {
        if (name == "bundleRelease" || name == "bundleReleaseStandard" || name == "bundleReleaseAab") {
            dependsOn("incrementVersionCode")
        }
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("$buildDir/generated/composeAppComposeResources")
        }
    }
    
    composeOptions {
        // Compiler extension version must match the Compose compiler compatible with the project's Kotlin plugin.
        // If you use a different Compose compiler version in CI/IDE, adjust this value accordingly.
        kotlinCompilerExtensionVersion = libs.versions.kotlin.get()
    }

    lint {
        disable += "Instantiatable"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    signingConfigs {
        create("release") {
            val ciFile = file("$buildDir/tmp/keystore/release.keystore")
            storeFile = if (ciFile.exists()) ciFile
                else file("release.keystore").takeIf { it.exists() }
            storePassword = providers.environmentVariable("WINGMATE_KEYSTORE_PASSWORD").orElse("").get()
            keyAlias = providers.environmentVariable("WINGMATE_KEY_ALIAS").orElse("").get()
            keyPassword = providers.environmentVariable("WINGMATE_KEY_PASSWORD").orElse("").get()
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

play {
    val ciFile = file("$buildDir/tmp/play/service-account.json")
    serviceAccountCredentials.set(
        if (ciFile.exists()) ciFile
        else file("service-account.json")
    )
    track.set("production")
}

val syncComposeAppComposeResources by tasks.registering(Sync::class) {
    dependsOn(":composeApp:copyAndroidMainComposeResourcesToAndroidAssets")
    from(
        project(":composeApp").layout.buildDirectory.dir(
            "generated/compose/resourceGenerator/androidAssets/copyAndroidMainComposeResourcesToAndroidAssets"
        )
    )
    into(layout.buildDirectory.dir("generated/composeAppComposeResources"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(syncComposeAppComposeResources)
}

tasks.matching { it.name.endsWith("LintReportModel") || it.name.endsWith("LintVitalReportModel") }.configureEach {
    dependsOn(syncComposeAppComposeResources)
}

tasks.matching { it.name.startsWith("lintAnalyze") }.configureEach {
    dependsOn(syncComposeAppComposeResources)
}

tasks.matching { it.name == "lintVitalAnalyzeRelease" }.configureEach {
    dependsOn(syncComposeAppComposeResources)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":composeApp"))

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // Common AndroidX helpers
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.androidx.lifecycle.get()}")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Keep Material3 only; icons are available via material3 or the icons artifact if required
    implementation("androidx.compose.material3:material3")
    // Provide Android Material Components which include platform Material3 styles/attrs
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")



    // DI
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    // Dual-screen / WindowManager (API 34+ rear display & window area APIs)
    implementation("androidx.window:window:1.3.0")
}

kotlin {
    jvmToolchain(21)
}

// Utility task to print AGP version in use
tasks.register("printAgpVersion") {
    doLast {
        println("AGP version: " + com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION)
    }
}
