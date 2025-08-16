plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.native.cocoapods")
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
    cocoapods {
        summary = "Wingmate shared module"
        homepage = "https://github.com/jdreioe/Wingmate"
        version = "0.1.0"
    ios.deploymentTarget = "14.0"
        framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
    val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("io.insert-koin:koin-core:3.5.6")
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
                // Compose Multiplatform dependencies for shared UI
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
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

            }
        }
        applyDefaultHierarchyTemplate()
        val iosMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:2.3.12")
            }
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
