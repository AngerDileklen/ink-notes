import java.util.Properties

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
        versionCode = 24
        versionName = "0.19.0-reader"
    }

    // Release signing comes from keystore.properties (never committed; see keystore.properties.example).
    val keystoreFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }
    signingConfigs {
        if (keystoreFile.exists()) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Targeting 29 is deliberate (simple file access on e-ink tablets that stay on Android 9-11); this app is sideloaded, not on Google Play.
    lint { disable += "ExpiredTargetSdkVersion" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // PDF text (search, selection, highlight along text lines). Drawing stays on Android's PdfRenderer.
    implementation("io.legere:pdfiumandroid:1.0.32")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
