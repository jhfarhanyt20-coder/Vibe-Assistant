plugins {
    id("com.android.application")
}

android {
    namespace = "com.vibe.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibe.assistant"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Offline "Hey Vibe" wake word (Vosk, grammar-restricted keyword spotting).
    // The models themselves are downloaded on the phone the first time.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
