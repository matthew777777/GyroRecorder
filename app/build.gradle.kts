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
        versionCode = 2
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release-only project: device tests also run against the release build.
    testBuildType = "release"

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

    buildFeatures {
        buildConfig = true
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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
