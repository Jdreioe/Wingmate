plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.native.cocoapods")
}

kotlin {
    // Ensure Kotlin uses JVM toolchain 21 for Android compilations
    jvmToolchain(21)
}

kotlin {
    androidTarget() {
        // Align Kotlin JVM target with Java 17 for Android
        compilations.all {
            compilerOptions.options.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    cocoapods {
        summary = "Wingmate shared module"
        homepage = "https://github.com/jdreioe/Wingmate"
        version = "0.1.0"
    ios.deploymentTarget = "14.0"
        framework {
            baseName = "Shared"
            isStatic = true
            // Export Koin as it's referenced in a public API (initKoin(extra: Module?)) so Swift can see the types
            export("io.insert-koin:koin-core:3.5.6")
        }
    }

    sourceSets {
    val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("io.insert-koin:koin-core:3.5.6")
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
                // Compose Multiplatform dependencies for shared UI
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
                implementation("io.ktor:ktor-client-okhttp:2.3.12")

            }
        }
        applyDefaultHierarchyTemplate()
        val iosMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:2.3.12")
                // Ensure Koin is resolved for iOS binaries too
                implementation("io.insert-koin:koin-core:3.5.6")
            }
        }
    }
}

android {
    namespace = "io.github.jdreioe.wingmate.shared"
    compileSdk = 36
    buildFeatures { compose = true }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Build + embed + (optionally) codesign the iOS framework into the Xcode app bundle.
// Intended to be called from an Xcode Run Script phase.
tasks.register("embedAndSignAppleFrameworkForXcode") {
    group = "build"
    description = "Builds the KMP iOS framework for the current SDK/CONFIGURATION, embeds into Xcode's Frameworks dir, and signs if needed."

    // Resolve Xcode env
    val configuration = System.getenv("CONFIGURATION") ?: "Debug"
    val sdkName = System.getenv("SDK_NAME") ?: "iphonesimulator"
    val archs = System.getenv("ARCHS") ?: ""
    val targetBuildDir = System.getenv("TARGET_BUILD_DIR")
    val frameworksFolder = System.getenv("FRAMEWORKS_FOLDER_PATH")
    val codeSignIdentity = System.getenv("EXPANDED_CODE_SIGN_IDENTITY")

    // Determine K/N target from SDK + arch
    val targetName = when {
        sdkName.startsWith("iphoneos") -> "iosArm64"
        sdkName.startsWith("iphonesimulator") && archs.contains("arm64") -> "iosSimulatorArm64"
        else -> "iosX64"
    }
    val buildType = when {
        configuration.contains("DEBUG", ignoreCase = true) -> "Debug"
        else -> "Release"
    }

    // Compute candidate link tasks (MPP standard and Cocoapods-prefixed)
    val linkCandidates = listOf(
        // Standard MPP task name
        "link${buildType}Framework" + targetName.replaceFirstChar { it.uppercase() },
        // Cocoapods plugin task name
        "linkPod${buildType}Framework" + targetName.replaceFirstChar { it.uppercase() }
    )
    linkCandidates.forEach { name ->
        tasks.findByName(name)?.let { dependsOn(it) }
    }

    doLast {
        if (targetBuildDir.isNullOrBlank() || frameworksFolder.isNullOrBlank()) {
            logger.lifecycle("Xcode environment not detected; skipping embed.")
            return@doLast
        }

        // Resolve framework output location; try Cocoapods sync dir first, then K/N bins
        val cocoapodsDir = layout.buildDirectory.dir("cocoapods/framework").get().asFile
        val binDirStandard = layout.buildDirectory.dir("bin/$targetName/$buildType").get().asFile
        val binDirPod = layout.buildDirectory.dir("bin/$targetName/pod${buildType}Framework").get().asFile

        fun scanFrameworks(dir: File): List<File> = dir.takeIf { it.exists() }?.listFiles { f ->
            f.isDirectory && f.name.endsWith(".framework")
        }?.toList().orEmpty()

        val frameworks = buildList<File> {
            addAll(scanFrameworks(cocoapodsDir))
            addAll(scanFrameworks(binDirPod))
            addAll(scanFrameworks(binDirStandard))
        }.distinctBy { it.name }
        if (frameworks.isEmpty()) {
            throw GradleException("No frameworks found for $targetName/$buildType in: ${cocoapodsDir.absolutePath}, ${binDirPod.absolutePath}, ${binDirStandard.absolutePath}")
        }

        val embedDir = file(targetBuildDir).resolve(frameworksFolder)
        embedDir.mkdirs()

    // Copy our framework(s) (include transitive K/N frameworks if present)
        frameworks.forEach { fw ->
            project.copy {
                from(fw)
                into(embedDir)
            }
            // Copy dSYM if present
            val dsym = File(fw.parentFile, fw.name + ".dSYM")
            if (dsym.exists()) {
                val dsymDest = File(targetBuildDir).resolve("${fw.name}.dSYM")
                project.copy {
                    from(dsym)
                    into(dsymDest.parentFile)
                }
            }
        }

        // Optionally sign frameworks (dynamic frameworks require signing; static ones typically don't)
        if (!codeSignIdentity.isNullOrBlank()) {
            frameworks.forEach { fw ->
                val embeddedFw = embedDir.resolve(fw.name)
                if (embeddedFw.exists()) {
                    exec {
                        commandLine(
                            "codesign",
                            "--force",
                            "--sign",
                            codeSignIdentity,
                            "--timestamp=none",
                            embeddedFw.absolutePath
                        )
                    }
                }
            }
        } else {
            logger.lifecycle("No EXPANDED_CODE_SIGN_IDENTITY provided; skipping codesign.")
        }

        logger.lifecycle("Embedded ${frameworks.size} framework(s) for $targetName/$buildType into ${embedDir.absolutePath}")
    }
}

