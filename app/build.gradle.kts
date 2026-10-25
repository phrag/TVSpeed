plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build identity for the self-updater (Updater.kt): compared against the
// version.json that CI publishes next to the rolling release APK.
fun git(vararg args: String): String = try {
    ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
        .inputStream.bufferedReader().readText().trim()
} catch (e: Exception) {
    ""
}
val gitSha = git("rev-parse", "HEAD").takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "unknown"
val gitTime = git("log", "-1", "--format=%ct").toLongOrNull() ?: 0L

android {
    namespace = "com.phrag.tvspeed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phrag.tvspeed"
        minSdk = 26          // covers every Shield
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("long", "GIT_TIME", "${gitTime}L")
        buildConfigField("String", "UPDATE_URL", "\"https://github.com/phrag/TVSpeed/releases/download/latest/\"")
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

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Intentionally none at runtime. HttpURLConnection + org.json (bundled) + framework Views.
    // Tests only: android.jar's org.json is stubbed on the JVM, so use the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
