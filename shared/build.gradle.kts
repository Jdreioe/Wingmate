plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
}

kotlin {
    // Ensure Kotlin uses JVM toolchain 21 for Android compilations
    jvmToolchain(21)
}

kotlin {
    androidTarget() {
        // Align Kotlin JVM target with Java 17 for Android
        compilations.all {
            compilerOptions.options.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
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
                export("io.insert-koin:koin-core:3.5.6")
            }
        }
    }

    sourceSets {
    val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                api("io.insert-koin:koin-core:3.5.6")
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
                // Add logging for Kotlin Multiplatform
                implementation("io.github.oshai:kotlin-logging:7.0.0")
                // Compose Multiplatform dependencies for shared UI
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)

                // MVIKotlin for BLoC pattern
                val mviKotlinVersion = "3.3.0"
                implementation("com.arkivanov.mvikotlin:mvikotlin:$mviKotlinVersion")
                implementation("com.arkivanov.mvikotlin:mvikotlin-main:$mviKotlinVersion")
                implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines:$mviKotlinVersion")

                // SQLDelight
                implementation("app.cash.sqldelight:runtime:2.0.2")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
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
                implementation("io.ktor:ktor-client-okhttp:2.3.12")
                // Required for FileProvider and core Android helpers used in androidMain
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("app.cash.sqldelight:android-driver:2.0.2")
            }
        }
        applyDefaultHierarchyTemplate()
        val iosMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:2.3.12")
                // Ensure Koin is resolved for iOS binaries too
                api("io.insert-koin:koin-core:3.5.6")
                implementation("app.cash.sqldelight:native-driver:2.0.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:2.3.12")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
            }
        }
    }
}

sqldelight {
    databases {
        create("TtsDatabase") {
            packageName.set("io.github.jdreioe.wingmate.db")
        }
    }
}

android {
    namespace = "io.github.jdreioe.wingmate.shared"
    compileSdk = 36
    buildFeatures { compose = true }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

