plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build number comes from CI so each release is a higher versionCode than the last.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystoreFile = rootProject.file("app/keystore.jks")

android {
    namespace = "com.fixmylife.selfiescreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fixmylife.selfiescreen"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.2.$buildNumber"
    }

    signingConfigs {
        create("shared") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "selfiescreen"
                keyAlias = "selfie"
                keyPassword = "selfiescreen"
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
