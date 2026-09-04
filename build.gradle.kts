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

// Patched versions of transitive deps forced on the build/plugin classpath (in the
// buildscript block) and on every project configuration (in allprojects) so Dependabot
// stays clean without waiting on plugin upstream releases. BouncyCastle 1.85.x closes the
// critical (GHSA-574f-3g2m-x479) and both medium (GHSA-c3fc-8qff-9hwx, GHSA-wg6q-6289-32hp)
// advisories; the rest close jose4j (GHSA-3677-xxcr-wjqv), jdom2 (GHSA-2363-cqg2-863c),
// and commons-lang3 (GHSA-j288-q9x7-2f5v).
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force(
                "org.bouncycastle:bcprov-jdk18on:1.85.2",
                "org.bouncycastle:bcpkix-jdk18on:1.85",
                "org.bouncycastle:bcutil-jdk18on:1.85",
                "org.bitbucket.b_c:jose4j:0.9.6",
                "org.jdom:jdom2:2.0.6.1",
                "org.apache.commons:commons-lang3:3.20.0"
            )
        }
    }
}

val patchedTransitiveVersions = listOf(
    "org.bouncycastle:bcprov-jdk18on:1.85.2",
    "org.bouncycastle:bcpkix-jdk18on:1.85",
    "org.bouncycastle:bcutil-jdk18on:1.85",
    "org.bitbucket.b_c:jose4j:0.9.6",
    "org.jdom:jdom2:2.0.6.1",
    "org.apache.commons:commons-lang3:3.20.0"
)

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven { url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1") }
    }

    // Android's Compose tooling resolves commons-lang3 transitively; without this
    // force it falls back to 3.16.0, which is still vulnerable to
    // GHSA-j288-q9x7-2f5v.
    configurations.configureEach {
        resolutionStrategy {
            force(*patchedTransitiveVersions.toTypedArray())
        }
    }
}

// Convenience aliases so IDEs or CI can call root tasks
tasks.register("assembleDebug") {
    dependsOn(":androidApp:assembleDebug")
}

tasks.register("assembleRelease") {
    dependsOn(":androidApp:assembleRelease")
}

// CodeQL's Java/Kotlin autobuilder invokes this conventional JVM task name,
// which an Android/KMP build does not provide automatically.
tasks.register("testClasses") {
    group = "verification"
    description = "Compiles the Android application for Java/Kotlin analysis."
    dependsOn(":androidApp:compileDebugKotlin")
}
