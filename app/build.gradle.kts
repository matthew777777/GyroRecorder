plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.matthew.gyrorecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.matthew.gyrorecorder"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("releaseLocal") {
            // Same convention as PerfOverlay: sign release with the local debug
            // key so it installs with no secrets. Only wired in when that
            // keystore already exists; otherwise the release APK is left
            // unsigned instead of failing the build on a fresh machine.
            val debugKey = file("${System.getProperty("user.home")}/.android/debug.keystore")
            if (debugKey.exists()) {
                storeFile = debugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val releaseLocal = signingConfigs.getByName("releaseLocal")
            if (releaseLocal.storeFile?.exists() == true) {
                signingConfig = releaseLocal
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.ar:core:1.57.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
