plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight") version "2.2.1"
}

kotlin {
    // Ensure Kotlin uses JVM toolchain 21 for all compilations
    jvmToolchain(21)
    
    androidLibrary {
        namespace = "io.github.jdreioe.wingmate.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    
    // Configure iOS framework
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        if (konanTarget.family == org.jetbrains.kotlin.konan.target.Family.IOS) {
            binaries.framework {
                baseName = "Shared"
                isStatic = false
                // Export Koin for Swift interop
                export("io.insert-koin:koin-core:${libs.versions.koin.get()}")
            }
        }
    }

    sourceSets {
    val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                api(libs.koin.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.contentNegotiation)
                implementation(libs.ktor.serialization.json)
                // Add logging for Kotlin Multiplatform
                implementation("io.github.oshai:kotlin-logging:7.0.0")

                // MVIKotlin for BLoC pattern
                val mviKotlinVersion = "3.3.0"
                implementation("com.arkivanov.mvikotlin:mvikotlin:$mviKotlinVersion")
                implementation("com.arkivanov.mvikotlin:mvikotlin-main:$mviKotlinVersion")
                implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines:$mviKotlinVersion")
                
                // SQLDelight
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
                implementation(libs.ktor.client.cio)
                // Required for FileProvider and core Android helpers used in androidMain
                implementation("androidx.core:core-ktx:1.13.1")
                // Compose Multiplatform for Android UI
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                
                implementation(libs.sqldelight.android)
            }
        }
        applyDefaultHierarchyTemplate()
        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                // Ensure Koin is resolved for iOS binaries too
                api(libs.koin.core)
                // Compose Multiplatform for iOS UI
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                
                implementation(libs.sqldelight.native)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.coroutinesSwing)
                // Compose Multiplatform for desktop JVM UI
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                
                implementation(libs.sqldelight.jvm)
            }
        }
    }
}

sqldelight {
  databases {
    create("WingmateDatabase") {
      packageName.set("io.github.jdreioe.wingmate.db")
    }
  }
}

