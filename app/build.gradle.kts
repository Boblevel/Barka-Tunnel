plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.barkatunnel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.barkatunnel.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 105
        versionName = "2.0.3.6"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
        getByName("release") {
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libbarka_xray.so",
                "**/libbarka_dnstt.so"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("com.wireguard.android:tunnel:1.0.20250531")
    implementation("com.github.mwiede:jsch:0.2.24")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
