plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "io.github.jdreioe.wingmate.core.presentation"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core:domain"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("com.arkivanov.mvikotlin:mvikotlin:3.3.0")
                implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines:3.3.0")
            }
        }
    }
}
