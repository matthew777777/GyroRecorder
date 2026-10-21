plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Release-only project: bare `./gradlew` builds the release APK.
defaultTasks(":app:assembleRelease")
