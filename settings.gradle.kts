pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Enable automatic Java toolchain provisioning from Foojay API
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "WingmateKMP"
include(":shared")
include(":androidApp")
include(":composeApp")
include(":desktopApp")
include(":linuxApp")
