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
