plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "io.github.jdreioe.wingmate.core.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core:domain"))
                implementation(libs.koin.core)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.contentNegotiation)
                implementation(libs.ktor.serialization.json)
                implementation("io.github.pdvrieze.xmlutil:core:0.91.3")
                implementation(libs.okio)
                implementation("app.cash.sqldelight:runtime:2.0.2")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("app.cash.sqldelight:android-driver:2.0.2")
                implementation("org.apache.commons:commons-compress:1.27.1")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
            }
        }

        applyDefaultHierarchyTemplate()

        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation("app.cash.sqldelight:native-driver:2.0.2")
            }
        }

        // macOS shares all Foundation/AVFoundation-based iOS infrastructure; only the
        // UIKit-dependent classes below are excluded and re-implemented with AppKit.
        val macosMain by getting {
            dependsOn(iosMain)
        }

        // UIKit-only implementations (pasteboard, share sheet) that must NOT be compiled
        // into the macOS framework. Picked up by the iOS targets via dependsOn below.
        val iosUiKitMain by creating {
            dependsOn(iosMain)
        }
        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.compilations.getByName("main").defaultSourceSet.dependsOn(iosUiKitMain)
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.apache.commons:commons-compress:1.27.1")
            }
        }
    }
}
