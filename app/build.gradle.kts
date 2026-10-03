plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.luna.telegram"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.luna.telegram"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-prototype"

        val apiId = project.findProperty("TELEGRAM_API_ID")?.toString() ?: "0"
        val apiHash = project.findProperty("TELEGRAM_API_HASH")?.toString() ?: ""
        buildConfigField("int", "TELEGRAM_API_ID", apiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$apiHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    // Community-maintained prebuilt TDLib Android AAR.
    implementation("io.github.tdlib-android:core:0.1.1")
}
