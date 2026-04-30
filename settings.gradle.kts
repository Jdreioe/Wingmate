pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Enable automatic Java toolchain provisioning from Foojay API
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "WingmateKMP"
include(":shared")
include(":androidApp")
include(":composeApp")
include(":desktopApp")
include(":linuxApp")
include(":core:domain")
include(":core:data")
include(":core:presentation")
include(":core:design-system")
include(":feature:communication:domain")
include(":feature:communication:data")
include(":feature:communication:presentation")
