plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screengraber.text"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.screengraber.text"
        minSdk = 29
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"

        // Целевое устройство — Xiaomi Mi 9T (ARM64). Фильтр также уменьшит APK,
        // когда появятся нативные библиотеки Tesseract.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

// Зависимостей пока нет: каркас использует только Kotlin stdlib.
// AndroidX и Tesseract4Android будут добавлены на соответствующих этапах.
dependencies {
}
