plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    linuxX64()
    mingwX64()
    macosX64()
    macosArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.staticLib {
            baseName = "wingmate_core"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core:domain"))
                implementation(project(":core:data"))
                implementation(project(":feature:communication:domain"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopMain by creating { dependsOn(commonMain) }
        getByName("linuxX64Main").dependsOn(desktopMain)
        getByName("mingwX64Main").dependsOn(desktopMain)
        getByName("macosX64Main").dependsOn(desktopMain)
        getByName("macosArm64Main").dependsOn(desktopMain)
    }
}
