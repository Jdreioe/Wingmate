plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "io.github.jdreioe.wingmate.feature.communication.presentation"
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
                implementation(project(":feature:communication:domain"))
                implementation(project(":feature:communication:data"))
                implementation(project(":core:presentation"))
                implementation(project(":core:design-system"))
                implementation(libs.koin.core)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("com.arkivanov.mvikotlin:mvikotlin:3.3.0")
                implementation("com.arkivanov.mvikotlin:mvikotlin-main:3.3.0")
                implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines:3.3.0")
            }
        }
    }
}
