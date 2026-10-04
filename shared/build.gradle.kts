plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
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
                api(libs.koin.core)
                implementation(libs.ktor.client.core)
                implementation(libs.okio)

                // MVIKotlin for BLoC pattern
                val mviKotlinVersion = "3.3.0"
                implementation("com.arkivanov.mvikotlin:mvikotlin:$mviKotlinVersion")
                implementation("com.arkivanov.mvikotlin:mvikotlin-main:$mviKotlinVersion")
                implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines:$mviKotlinVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // Fake Main dispatcher for MVIKotlin CoroutineExecutor in tests
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
        val androidMain by getting {
            dependencies {
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
            }
        }
        // The JVM target only runs tests; clients created with HttpClient() need an engine there.
        val jvmTest by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }

        applyDefaultHierarchyTemplate()
        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.ktor.client.contentNegotiation)
                implementation(libs.ktor.serialization.json)
                // Ensure Koin is resolved for iOS binaries too
                api(libs.koin.core)
            }
        }
    }
}
