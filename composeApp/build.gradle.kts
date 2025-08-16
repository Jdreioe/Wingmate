plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(project(":shared"))
    // Use the desktop-jvm artifact which brings the appropriate runtime (keep in sync with plugin)
    implementation("org.jetbrains.compose.desktop:desktop-jvm:1.7.0")
    implementation("io.insert-koin:koin-core:3.5.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("javazoom:jlayer:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    // Add explicit Skiko native runtime for Linux x64 to ensure native libs are available
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.15")
    // simple slf4j binding to avoid warning at runtime
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
    // SQLite JDBC for local persistence on desktop
    implementation("org.xerial:sqlite-jdbc:3.41.2.1")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.0")
    implementation("org.jetbrains.compose.material3:material3:1.7.0")
}

kotlin {
    jvmToolchain(11)
}

compose.desktop {
    application {
        mainClass = "io.github.jdreioe.wingmate.ComposeMainKt"
    }
}
