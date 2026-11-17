plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.phoneagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.phoneagent"
        // AccessibilityService.takeScreenshot() needs Android 11 (API 30).
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// No third-party dependencies: HTTP, JSON and the OAuth loopback server use the Android SDK only.
