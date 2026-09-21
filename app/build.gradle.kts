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
        versionCode = 64
        versionName = "4.17.0"
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
    testImplementation("org.json:json:20250517")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
