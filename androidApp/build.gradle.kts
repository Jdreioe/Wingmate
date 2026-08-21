import java.util.Base64
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction

abstract class IncrementVersionCodeTask : DefaultTask() {
    @get:Internal
    abstract val versionFile: RegularFileProperty

    @TaskAction
    fun increment() {
        val file = versionFile.get().asFile
        val properties = Properties()
        if (file.exists()) {
            file.inputStream().use(properties::load)
        }
        val nextCode = (properties.getProperty("versionCode")?.toIntOrNull() ?: 1) + 1
        properties.setProperty("versionCode", nextCode.toString())
        file.outputStream().use { properties.store(it, "Auto-incremented by build") }
        logger.lifecycle("Version code incremented to $nextCode")
    }
}

fun toBuildConfigStringLiteral(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
    val vCode = System.getenv("WINGMATE_VERSION_CODE")?.toIntOrNull()
        ?: (versionProps.getProperty("versionCode") ?: "1").toInt()
    val vName = System.getenv("WINGMATE_VERSION_NAME")
        ?: (versionProps.getProperty("versionName") ?: "1.0")

    fun resolveConfigValue(key: String): String {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use(localProperties::load)
        }
        return sequenceOf(
            System.getenv("WINGMATE_$key"),
            localProperties.getProperty("WINGMATE_$key"),
            localProperties.getProperty(key)
        ).firstOrNull { !it.isNullOrBlank() } ?: ""
    }

    val aptabaseAppKey = resolveConfigValue("APTABASE_APP_KEY")
    val openSymbolsProxyUrl = resolveConfigValue("OPENSYMBOLS_PROXY_URL")
        .ifBlank { "https://wingmate-opensymbols-proxy.patient-mouse-467e.workers.dev" }

    defaultConfig {
        applicationId = "com.hojmoseit.wingmate"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = vCode
        versionName = vName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "OPENSYMBOLS_PROXY_URL",
            toBuildConfigStringLiteral(openSymbolsProxyUrl)
        )
        buildConfigField(
            "String",
            "APTABASE_APP_KEY",
            toBuildConfigStringLiteral(aptabaseAppKey)
        )
    }

    tasks.register<IncrementVersionCodeTask>("incrementVersionCode") {
        versionFile.set(layout.projectDirectory.file("../version.properties"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
            // Keep a mapping file for Google Play so R8-obfuscated crash reports
            // can be translated back to the original Kotlin/Java symbols.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Package unstripped native symbols from dependency-provided .so files
            // for upload to Google Play Console alongside the App Bundle.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
}

// Persist the next version code after an app bundle task completes. The bundle
// uses the code read during configuration; the next bundle picks up the
// incremented value from version.properties.
androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    val bundleTaskName = "bundle${variant.name.replaceFirstChar { it.uppercase() }}"
    tasks.matching { it.name == bundleTaskName }.configureEach {
        finalizedBy("incrementVersionCode")
    }
}

play {
    val ciFile = file("$buildDir/tmp/play/service-account.json")
    serviceAccountCredentials.set(
        if (ciFile.exists()) ciFile
        else file("service-account.json")
    )
    track.set("alpha")
}

dependencies {
    implementation(project(":shared"))

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.testExt.junit)
    // Compose UI Test still brings Espresso 3.5 transitively. That release
    // reflects on InputManager.getInstance(), which no longer exists on API 36.
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Common AndroidX helpers
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtimeKtx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.androidx.lifecycle.get()}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:${libs.versions.androidx.lifecycle.get()}")

    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Image loading
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-svg:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    // DI
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Ktor engine for ARM API calls
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Dual-screen / WindowManager (API 34+ rear display & window area APIs)
    implementation(libs.androidx.window)

    // Unit testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
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
