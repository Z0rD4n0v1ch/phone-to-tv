plugins {
    id("com.android.application")
}

// Версию задаёт GitHub Actions из тега релиза (v1.2 → 1.2), локально — 1.0
val appVersionName = (findProperty("appVersionName") as String?) ?: "1.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1

// Подпись релиза: ключ передаётся через переменные окружения (секреты GitHub).
// Без них релизная сборка подписывается отладочным ключом.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE")

android {
    namespace = "com.example.phonetotv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.phonetotv"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
