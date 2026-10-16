plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.novemberraining.psalyric"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novemberraining.psalyric"
        minSdk = 28
        targetSdk = 28
        versionCode = 10
        versionName = "0.1.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // This app is intentionally side-loaded on an Android 9 head unit, not published to Play.
        disable += "ExpiredTargetSdkVersion"
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
