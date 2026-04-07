plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
    
    androidLibrary {
        namespace = "com.hojmoseit.wingmate.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    
    jvm("desktop")
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":shared"))
                @Suppress("DEPRECATION")
                implementation(compose.runtime)
                @Suppress("DEPRECATION")
                implementation(compose.foundation)
                @Suppress("DEPRECATION")
                implementation(compose.material3)
                @Suppress("DEPRECATION")
                implementation(compose.ui)
                @Suppress("DEPRECATION")
                implementation(compose.components.resources)
                @Suppress("DEPRECATION")
                implementation(compose.components.uiToolingPreview)
                @Suppress("DEPRECATION")
                implementation(compose.materialIconsExtended)
                
                implementation("io.insert-koin:koin-core:3.5.6")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.10.1")
                implementation("androidx.core:core-ktx:1.10.1")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
            }
        }
        
        val desktopMain by getting {
            dependencies {
                @Suppress("DEPRECATION")
                implementation(compose.desktop.common)
                implementation("io.ktor:ktor-client-okhttp:2.3.12")
                implementation("javazoom:jlayer:1.0.1")
                implementation("org.xerial:sqlite-jdbc:3.41.2.1")
                runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
                runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.15")
                // Partner window display driver (FTDI FT232H USB/SPI)
                implementation("org.usb4java:usb4java:1.3.0")
                implementation("org.usb4java:usb4java-javax:1.3.0")
            }
        }
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}
compose.desktop {
    application {
        mainClass = "io.github.jdreioe.wingmate.desktop.MainKt"
    }
}
