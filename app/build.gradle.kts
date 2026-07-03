plugins {
    id("com.android.application")
}

android {
    namespace = "jp.japanride.spinbike"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "jp.japanride.spinbike"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.67.4"
    }
}
