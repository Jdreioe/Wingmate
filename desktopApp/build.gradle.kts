plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    
    // Desktop-specific dependencies that composeApp needs but can't declare in commonMain
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("javazoom:jlayer:1.0.1")
    implementation("org.xerial:sqlite-jdbc:3.41.2.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.15")
    
    // Koin
    implementation("io.insert-koin:koin-core:3.5.6")
    
    // Compose runtime for collectAsState
    implementation("org.jetbrains.compose.runtime:runtime:1.5.3")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "io.github.jdreioe.wingmate.desktop.MainKt"
    }
}
