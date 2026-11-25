import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
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
                "org.bouncycastle:bcprov-jdk18on:1.86",
                "org.bouncycastle:bcpkix-jdk18on:1.86",
                "org.bouncycastle:bcutil-jdk18on:1.86",
                "org.bitbucket.b_c:jose4j:0.9.7",
                "org.jdom:jdom2:2.0.6.1",
                "org.apache.commons:commons-lang3:3.21.0"
            )
        }
    }
}

val patchedTransitiveVersions = listOf(
    "org.bouncycastle:bcprov-jdk18on:1.86",
    "org.bouncycastle:bcpkix-jdk18on:1.86",
    "org.bouncycastle:bcutil-jdk18on:1.86",
    "org.bitbucket.b_c:jose4j:0.9.7",
    "org.jdom:jdom2:2.0.6.1",
    "org.apache.commons:commons-lang3:3.21.0"
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

/**
 * Verifies KMP module dependency boundaries and version-catalog hygiene (#184):
 * - domain modules never depend on data, presentation, :shared, or :androidApp
 * - presentation modules never depend on data, :shared, or :androidApp
 * - :shared never re-exports *:data modules via api(...)
 * - shared KMP modules do not pull in Compose UI plugins or dependencies
 * - subproject build files use gradle/libs.versions.toml instead of hardcoded artifact versions
 */
abstract class CheckModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val moduleBuildFiles: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildFileContents: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val projectDepPattern = Regex("""\b(api|implementation|testImplementation|androidTestImplementation)\(\s*project\(\s*"([^"]+)"\s*\)\s*\)""")
        val hardcodedCoordPattern = Regex("""\b(api|implementation|testImplementation|androidTestImplementation|debugImplementation|platform)\(\s*"([^":\s]+):([^":\s]+):([^"\s]+)"\s*\)""")
        val composeLeakPattern = Regex("""(org\.jetbrains\.compose|androidx\.compose|plugin\.compose)""")

        val violations = mutableListOf<String>()

        moduleBuildFiles.get().toSortedMap().forEach { (modulePath, filePath) ->
            val file = File(filePath)
            if (!file.exists()) return@forEach

            val lines = file.readLines()
            val isDomain = modulePath.endsWith(":domain")
            val isPresentation = modulePath.endsWith(":presentation")
            val isShared = modulePath == ":shared"
            val isAndroidApp = modulePath == ":androidApp"

            lines.forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore("//").trim()
                if (line.isEmpty()) return@forEachIndexed
                val lineNo = index + 1

                projectDepPattern.findAll(line).forEach { match ->
                    val config = match.groupValues[1]
                    val target = match.groupValues[2]
                    if (isDomain && (target.endsWith(":data") || target.endsWith(":presentation") || target == ":shared" || target == ":androidApp")) {
                        violations += "$modulePath:$lineNo — domain module cannot depend on $target ($config)"
                    }
                    if (isPresentation && (target.endsWith(":data") || target == ":shared" || target == ":androidApp")) {
                        violations += "$modulePath:$lineNo — presentation module cannot depend on $target ($config)"
                    }
                    if (isShared && config == "api" && target.endsWith(":data")) {
                        violations += "$modulePath:$lineNo — :shared must not expose $target via api(...); use implementation(...)"
                    }
                }

                if (!isAndroidApp && composeLeakPattern.containsMatchIn(line)) {
                    violations += "$modulePath:$lineNo — non-Android-app module must not declare Compose plugin/dependencies: $line"
                }

                hardcodedCoordPattern.findAll(line).forEach { match ->
                    val coord = "${match.groupValues[2]}:${match.groupValues[3]}:${match.groupValues[4]}"
                    violations += "$modulePath:$lineNo — hardcoded dependency version '$coord'; declare it in gradle/libs.versions.toml"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module dependency graph / version catalog violations:\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }
    }
}

tasks.register<CheckModuleGraphTask>("checkModuleGraph") {
    group = "verification"
    description = "Enforces KMP layer boundaries, no shared Compose dependencies, and single-source version catalog usage."
    val subprojectBuildFiles = subprojects
        .filter { it.buildFile.exists() }
        .associate { it.path to it.buildFile.absolutePath }
    moduleBuildFiles.set(subprojectBuildFiles)
    buildFileContents.from(subprojectBuildFiles.values.map(::File))
}
