plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.openaichat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.openaichat"
        minSdk = 26
        targetSdk = 35
        versionCode = 62
        versionName = "4.16.2"
    }

    buildFeatures {
        // AGP 8+ does not generate BuildConfig unless explicitly enabled.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        getByName("debug") {
            val keystorePath = System.getenv("DEBUG_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
            }
            // If DEBUG_KEYSTORE_PATH isn't set (e.g. local builds without the CI secret),
            // this silently keeps AGP's normal auto-generated ~/.android/debug.keystore.
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
