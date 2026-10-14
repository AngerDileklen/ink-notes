plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.einknotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.einknotes"
        minSdk = 28
        // The tablet runs Android 11. Targeting 29 keeps simple file access for the probe's report.
        targetSdk = 29
        versionCode = 12
        versionName = "0.7.0-export"
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}
