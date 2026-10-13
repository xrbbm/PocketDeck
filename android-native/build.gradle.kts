// Top-level build file for the native Android client (android-native/).
// Truth source for versions: keep AGP/Kotlin/SDK aligned with the frozen
// Flutter baseline (android/settings.gradle.kts + android/app/build.gradle.kts).
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
}
