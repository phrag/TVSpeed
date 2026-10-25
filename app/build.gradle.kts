plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phrag.tvspeed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phrag.tvspeed"
        minSdk = 26          // covers every Shield
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Committed debug key so every CI build has the same signature and the
    // "latest" release APK installs over the previous one. Debug-only: not a
    // secret, don't use it for anything published to a store.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true   // VERSION_NAME in the User-Agent
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Intentionally none. HttpURLConnection + org.json (bundled) + framework Views.
}
