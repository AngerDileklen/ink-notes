plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.einknotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.einknotes.probe"
        minSdk = 28
        // The tablet runs Android 11. Targeting 29 keeps simple file access for the probe's report.
        targetSdk = 29
        versionCode = 2
        versionName = "0.2-probe"
    }

    buildTypes {
        debug {
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
