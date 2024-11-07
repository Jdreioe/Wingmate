plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.google.firebase.crashlytics)


}

android {
    namespace = "com.hoejmoseit.wingman"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hoejmoseit.wingman"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.3"

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
    implementation(libs.play.services.phenotype)
    val activity_version = "1.9.3"

    implementation("androidx.activity:activity:$activity_version")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

        // Material Design 3
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // UI Tests
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.guava)

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
    implementation(libs.work.runtime)
    implementation(libs.play.services.basement)
    implementation(libs.recyclerview)
    implementation(libs.annotations)
    implementation(libs.firebase.crashlytics)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.material)
    // Speech SDK
    implementation(libs.client.sdk)
    implementation(libs.billing) // Replace with the latest version
    
    implementation(libs.appcompat.v131)
    implementation(libs.constraintlayout.v220)
    implementation("androidx.preference:preference:1.2.1")
    implementation(libs.compiler)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    dependencies {
        implementation(libs.listenablefuture)
        // ... other dependencies ...

        val roomVersion = "2.6.1"

        implementation(libs.room.runtime)
        annotationProcessor(libs.room.compiler)

        // Optional - Kotlin Symbol Processing (KSP)
        // ksp("androidx.room:room-compiler:$roomVersion")

        // Optional - RxJava2 support for Room
        implementation(libs.room.rxjava2)

        // Optional - RxJava3 support for Room
        implementation(libs.room.rxjava3)

        // Optional - Guava support for Room
        // implementation("androidx.room:room-guava:$roomVersion")

        // Optional - Test helpers
        testImplementation(libs.room.testing)

        // Optional - Paging 3 Integration
        implementation(libs.room.paging)

        implementation(libs.xlog)

        // ... other dependencies ...
    }
}