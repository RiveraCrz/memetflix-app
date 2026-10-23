plugins {
    id("com.android.application")
}

android {
    namespace = "com.memetflix.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.memetflix.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release APK with debug key so it can be installed directly on any TV/device
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Pure Android SDK implementation for maximum speed, tiny APK size, and universal Android TV compatibility
}
