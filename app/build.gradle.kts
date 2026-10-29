import java.util.Properties

plugins {
    id("com.android.application")
}

// Firma propia para la APK que se comparte. Crea keystore.properties en la raíz
// (no se sube a git) con: storeFile, storePassword, keyAlias, keyPassword.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.memetflix.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.memetflix.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sin keystore.properties se firma con la llave debug de esta PC (solo para pruebas).
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
