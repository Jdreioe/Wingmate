plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight") version "2.0.2"
}

kotlin {
    // Ensure Kotlin uses JVM toolchain 21 for all compilations
    jvmToolchain(21)
    
    androidLibrary {
        namespace = "com.hojmoseit.wingmate.shared"
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
                // Swift code uses domain models such as Shared.Phrase. Kotlin/Native
                // frameworks only expose dependency types when they are exported.
                export(project(":core:domain"))
                export(project(":feature:communication:presentation"))
                // Export Koin for Swift interop
                export("io.insert-koin:koin-core:${libs.versions.koin.get()}")
            }
        }
    }

    sourceSets {
    val commonMain by getting {
            dependencies {
                api(project(":core:domain"))
                api(project(":core:data"))
                api(project(":core:presentation"))
                api(project(":feature:communication:domain"))
                api(project(":feature:communication:data"))
                api(project(":feature:communication:presentation"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation(libs.kotlinx.serialization.json)
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                api(libs.koin.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.contentNegotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.okio)

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
                implementation(libs.ktor.client.okhttp)
                implementation(
                    "com.github.aptabase:aptabase-kotlin:${libs.versions.aptabase.get()}"
                ) {
                    // Aptabase does not reference Material Components, but declares it as a
                    // runtime dependency. Its retained dialogs call Android 15-deprecated
                    // system-bar color APIs even though Wingmate never uses those dialogs.
                    exclude(group = "com.google.android.material", module = "material")
                    // This production SDK also declares AndroidX Test Monitor at runtime,
                    // which conflicts with the newer monitor used by instrumentation tests.
                    exclude(group = "androidx.test", module = "monitor")
                }
                // Required for FileProvider and core Android helpers used in androidMain
                implementation("androidx.core:core-ktx:1.19.0")
                // Compose Multiplatform for Android UI
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                
                implementation("app.cash.sqldelight:android-driver:2.0.2")
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
                
                implementation("app.cash.sqldelight:native-driver:2.0.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                // Compose Multiplatform for desktop JVM UI
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
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
