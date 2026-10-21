plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hizir.sadehome"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hizir.sadehome"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Kişisel cihaz için: yerel debug anahtarıyla imzalanır (Play Store'a gitmeyecek).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = false
    }
}

// Bilinçli olarak androidx / Compose yok: 2 GB RAM'li kutuda süreç ~20-30 MB daha hafif kalıyor.
dependencies {}
