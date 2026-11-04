import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- Подпись ---------------------------------------------------------------
// CI: ключ передаётся через SCREENGRABER_* (keystore во временном файле).
// Локально: keystore.properties в корне проекта (в .gitignore).
// Если не задано ни то, ни другое — используется стандартный debug-keystore.

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val ciKeystorePath = System.getenv("SCREENGRABER_KEYSTORE")?.takeIf { it.isNotBlank() }
val ciKeystoreFile = ciKeystorePath?.let { File(it) }

if (ciKeystoreFile != null) {
    require(ciKeystoreFile.isAbsolute) {
        "SCREENGRABER_KEYSTORE must be an absolute path, got: $ciKeystorePath"
    }
    require(ciKeystoreFile.isFile) {
        "SCREENGRABER_KEYSTORE file does not exist: $ciKeystoreFile"
    }
    require(!System.getenv("SCREENGRABER_KEYSTORE_PASSWORD").isNullOrBlank()) {
        "Missing SCREENGRABER_KEYSTORE_PASSWORD for CI signing"
    }
    require(!System.getenv("SCREENGRABER_KEY_ALIAS").isNullOrBlank()) {
        "Missing SCREENGRABER_KEY_ALIAS for CI signing"
    }
    require(!System.getenv("SCREENGRABER_KEY_PASSWORD").isNullOrBlank()) {
        "Missing SCREENGRABER_KEY_PASSWORD for CI signing"
    }
}

val localKeystorePath = keystoreProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val localKeystoreFile = localKeystorePath?.let { rootProject.file(it) }

if (ciKeystoreFile == null && keystorePropertiesFile.exists()) {
    require(localKeystoreFile != null) {
        "keystore.properties exists, but storeFile is not set"
    }
    require(localKeystoreFile!!.isFile) {
        "keystore.properties storeFile not found: $localKeystoreFile"
    }
    require(!keystoreProperties.getProperty("storePassword").isNullOrBlank()) {
        "keystore.properties: storePassword is not set"
    }
    require(!keystoreProperties.getProperty("keyAlias").isNullOrBlank()) {
        "keystore.properties: keyAlias is not set"
    }
    require(!keystoreProperties.getProperty("keyPassword").isNullOrBlank()) {
        "keystore.properties: keyPassword is not set"
    }
}

val useCustomSigning = ciKeystoreFile != null || localKeystoreFile != null
// ---------------------------------------------------------------------------


android {
    namespace = "com.screengraber.text"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.screengraber.text"
        minSdk = 29
        targetSdk = 30
        versionCode = 4
        versionName = "0.4.0"

        // Целевое устройство — Xiaomi Mi 9T (ARM64). Фильтр также уменьшит APK,
        // когда появятся нативные библиотеки Tesseract.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (useCustomSigning) {
            create("screengraber") {
                storeFile = ciKeystoreFile ?: localKeystoreFile
                if (ciKeystoreFile != null) {
                    storePassword = System.getenv("SCREENGRABER_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("SCREENGRABER_KEY_ALIAS")
                    keyPassword = System.getenv("SCREENGRABER_KEY_PASSWORD")
                } else {
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        debug {
            if (useCustomSigning) {
                signingConfig = signingConfigs.getByName("screengraber")
            }
        }
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
