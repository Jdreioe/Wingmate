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
    linuxX64()
    mingwX64()
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core:domain"))
                implementation(libs.koin.core)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.contentNegotiation)
                implementation(libs.ktor.serialization.json)
                implementation("io.github.pdvrieze.xmlutil:core:1.0.2.1")
                implementation(libs.okio)
                implementation("app.cash.sqldelight:runtime:2.0.2")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation("androidx.core:core-ktx:1.19.0")
                implementation("app.cash.sqldelight:android-driver:2.0.2")
                implementation("org.apache.commons:commons-compress:1.28.0")
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

        val desktopMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.curl)
                implementation("app.cash.sqldelight:native-driver:2.0.2")
            }
        }
        getByName("linuxX64Main").dependsOn(desktopMain)
        getByName("mingwX64Main").dependsOn(desktopMain)
        getByName("macosX64Main").dependsOn(desktopMain)
        getByName("macosArm64Main").dependsOn(desktopMain)

        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.apache.commons:commons-compress:1.28.0")
            }
        }
    }
}
