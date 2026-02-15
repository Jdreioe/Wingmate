plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Convenience aliases so IDEs or CI can call root tasks
tasks.register("assembleDebug") {
    dependsOn(":androidApp:assembleDebug")
}

tasks.register("assembleRelease") {
    dependsOn(":androidApp:assembleRelease")
}

// Task to build desktop app with Conveyor
tasks.register<Exec>("packageDesktop") {
    dependsOn(":desktopApp:build")
    commandLine("conveyor", "make", "site")
    workingDir = rootDir
}
