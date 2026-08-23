plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.barkatunnel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.barkatunnel.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 60
        versionName = "1.1.7"
    }

    val stableStoreFile = System.getenv("BARKA_SIGNING_STORE_FILE")
    val stableStorePassword = System.getenv("BARKA_SIGNING_STORE_PASSWORD")
    val stableKeyAlias = System.getenv("BARKA_SIGNING_KEY_ALIAS")
    val stableKeyPassword = System.getenv("BARKA_SIGNING_KEY_PASSWORD")

    signingConfigs {
        create("stable") {
            if (!stableStoreFile.isNullOrBlank()) {
                storeFile = file(stableStoreFile)
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!stableStoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
