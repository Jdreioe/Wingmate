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
            }
        }
    }

    // Apple checks each dynamic framework for its own privacy manifest, so Shared
    // ships the app's manifest, which also covers the APIs Kotlin code uses.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
        if (binary is org.jetbrains.kotlin.gradle.plugin.mpp.Framework) {
            val manifest = rootProject.file("iosApp/iosApp/PrivacyInfo.xcprivacy")
            val framework = outputFile
            inputs.file(manifest)
            doLast { manifest.copyTo(framework.get().resolve("PrivacyInfo.xcprivacy"), overwrite = true) }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core:domain"))
                api(project(":core:presentation"))
                api(project(":feature:communication:domain"))
                api(project(":feature:communication:presentation"))
                implementation(project(":core:data"))
                implementation(project(":feature:communication:data"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                api(libs.koin.core)
                implementation(libs.ktor.client.core)
                implementation(libs.okio)

                // MVIKotlin for BLoC pattern
                implementation(libs.mvikotlin.core)
                implementation(libs.mvikotlin.main)
                implementation(libs.mvikotlin.extensions.coroutines)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // Fake Main dispatcher for MVIKotlin CoroutineExecutor in tests
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                // Remote Screens cloud drives: Custom Tabs for OneDrive sign-in,
                // Google Identity Services authorization for Google Drive.
                implementation(libs.androidx.browser)
                implementation(libs.play.services.auth)
                implementation(libs.aptabase.kotlin.get().toString()) {
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
