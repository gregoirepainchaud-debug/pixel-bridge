plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.gregoirepainchaud.pixelbridge"
    compileSdk = 35
    // Épinglé : les runners CI n'ont pas toujours un NDK préinstallé,
    // et AGP échoue alors à installer son défaut (vu le 2026-11-16).
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "ca.gregoirepainchaud.pixelbridge"
        // Appareil cible unique : le Pixel 8 Pro de Grégoire (Android 14+).
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Banc d'essai : un seul appareil cible, une seule ABI.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Signature STABLE du harnais : clé dédiée, jetable, commise au dépôt
    // à dessein (voir README). Sans elle, chaque machine de CI signe avec
    // sa propre clé debug et Android refuse les mises à jour par-dessus
    // l'installation précédente (constaté le 2026-11-16).
    signingConfigs {
        create("bench") {
            storeFile = file("bench-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("bench")
        }
        release {
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
