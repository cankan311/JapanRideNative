plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.japanride.spinbike"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.japanride.spinbike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.67.1"
    }
}
