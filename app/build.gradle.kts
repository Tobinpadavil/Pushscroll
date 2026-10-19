plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teserakt.pushscroll"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.teserakt.pushscroll"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // On-device body tracking (bundled models, works offline)
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
}
