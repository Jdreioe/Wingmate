plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
    alias(libs.plugins.playPublisher) apply false
}

buildscript {
    configurations.classpath {
        resolutionStrategy {
            // Force patched versions of transitive build/plugin-classpath deps so
            // Dependabot stays clean without waiting on plugin upstream releases.
            // BouncyCastle 1.84 closes the critical (GHSA-574f-3g2m-x479) and both
            // medium (GHSA-c3fc-8qff-9hwx, GHSA-wg6q-6289-32hp) advisories.
            force(
                "org.bouncycastle:bcprov-jdk18on:1.84",
                "org.bouncycastle:bcpkix-jdk18on:1.84",
                "org.bouncycastle:bcutil-jdk18on:1.84",
                // jose4j: GHSA-3677-xxcr-wjqv, jdom2: GHSA-2363-cqg2-863c,
                // commons-lang3: GHSA-j288-q9x7-2f5v
                "org.bitbucket.b_c:jose4j:0.9.6",
                "org.jdom:jdom2:2.0.6.1",
                "org.apache.commons:commons-lang3:3.18.0"
            )
        }
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven { url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1") }
    }
}

// Convenience aliases so IDEs or CI can call root tasks
tasks.register("assembleDebug") {
    dependsOn(":androidApp:assembleDebug")
}

tasks.register("assembleRelease") {
    dependsOn(":androidApp:assembleRelease")
}

tasks.register("packageLinux") {
    group = "build"
    description = "Builds the standalone Linux Kotlin bridge fat JAR."
    dependsOn(":linuxApp:fatJar")
}
