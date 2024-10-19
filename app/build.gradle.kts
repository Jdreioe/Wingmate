plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)


}

android {
    namespace = "com.hoejmoseit.wingman"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hoejmoseit.wingman"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.material)
    // Speech SDK
    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.40.0")

    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.0")
    implementation("androidx.preference:preference:1.2.0")
    implementation("com.android.databinding:compiler:3.1.4")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    dependencies {
        implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
        // ... other dependencies ...

        val roomVersion = "2.6.1"

        implementation("androidx.room:room-runtime:$roomVersion")
        annotationProcessor("androidx.room:room-compiler:$roomVersion")

        // Optional - Kotlin Symbol Processing (KSP)
        // ksp("androidx.room:room-compiler:$roomVersion")

        // Optional - RxJava2 support for Room
        implementation("androidx.room:room-rxjava2:$roomVersion")

        // Optional - RxJava3 support for Room
        implementation("androidx.room:room-rxjava3:$roomVersion")

        // Optional - Guava support for Room
        // implementation("androidx.room:room-guava:$roomVersion")

        // Optional - Test helpers
        testImplementation("androidx.room:room-testing:$roomVersion")

        // Optional - Paging 3 Integration
        implementation("androidx.room:room-paging:$roomVersion")

        implementation("com.elvishew:xlog:1.11.1")

        // ... other dependencies ...
    }
}