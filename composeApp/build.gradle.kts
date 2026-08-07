plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
    
    androidLibrary {
        namespace = "com.hojmoseit.wingmate.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":shared"))
                @Suppress("DEPRECATION")
                implementation(compose.runtime)
                @Suppress("DEPRECATION")
                implementation(compose.foundation)
                @Suppress("DEPRECATION")
                implementation(compose.material3)
                @Suppress("DEPRECATION")
                implementation(compose.ui)
                @Suppress("DEPRECATION")
                implementation(compose.components.resources)
                @Suppress("DEPRECATION")
                implementation(compose.components.uiToolingPreview)
                @Suppress("DEPRECATION")
                implementation(compose.materialIconsExtended)
                
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                implementation("io.coil-kt.coil3:coil-compose:3.5.0")
                implementation("io.coil-kt.coil3:coil-svg:3.5.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                @Suppress("DEPRECATION")
                implementation(compose.uiTest)
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtimeKtx)
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
            }
        }
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

tasks.matching { it.name == "copyAndroidMainComposeResourcesToAndroidAssets" }.configureEach {
    val outputDirectoryGetter = javaClass.methods.firstOrNull {
        it.name == "getOutputDirectory" && it.parameterCount == 0
    } ?: return@configureEach

    val outputDirectoryProperty = outputDirectoryGetter.invoke(this) ?: return@configureEach
    val outputFile = layout.buildDirectory
        .dir("generated/compose/resourceGenerator/androidAssets/${name}")
        .get()
        .asFile

    val fileValueMethod = outputDirectoryProperty.javaClass.methods.firstOrNull {
        it.name == "fileValue" && it.parameterCount == 1
    }
    if (fileValueMethod != null) {
        fileValueMethod.invoke(outputDirectoryProperty, outputFile)
        return@configureEach
    }

    val setMethod = outputDirectoryProperty.javaClass.methods.firstOrNull {
        it.name == "set" && it.parameterCount == 1
    } ?: return@configureEach

    setMethod.invoke(outputDirectoryProperty, outputFile)
}
