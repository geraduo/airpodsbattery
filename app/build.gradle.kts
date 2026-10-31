plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.podsbattery"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.podsbattery"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
