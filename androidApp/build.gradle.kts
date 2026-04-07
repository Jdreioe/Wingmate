import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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

    defaultConfig {
        applicationId = "com.hojmoseit.wingmate"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = vCode
        versionName = vName
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


    buildFeatures { compose = true }
    
    composeOptions {
        // Compiler extension version must match the Compose compiler compatible with the project's Kotlin plugin.
        // If you use a different Compose compiler version in CI/IDE, adjust this value accordingly.
        kotlinCompilerExtensionVersion = libs.versions.kotlin.get()
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":composeApp"))

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Common AndroidX helpers
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")

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
    implementation("io.insert-koin:koin-core:4.1.0")
    implementation("io.insert-koin:koin-android:4.1.0")

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
