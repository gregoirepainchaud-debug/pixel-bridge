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
        versionCode = 19
        versionName = "0.19"
        // Banc d'essai : un seul appareil cible, une seule ABI.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // Le type de build natif (Release, -O3) est forcé dans
            // CMakeLists.txt — sans lui, l'APK debug impose -O0 au
            // code natif et ggml y perd l'essentiel de sa vitesse
            // (constaté le 2026-11-16 : 0,65 jeton/s en lecture).
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
    // Scanner documentaire Google ML Kit (scanner de reçus de
    // la page Reçus de la WebView Gestion) : le flux de scan
    // complet (détection des bords, redressement, filtres,
    // nettoyage) roule dans Google Play Services — aucune
    // permission CAMERA pour l'app. Version stable vérifiée
    // sur la doc officielle le 2026-11-17.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    // Tests JVM de la machine à états du suivi de trajet
    // (voir src/test/.../gps/GpsLogiqueTest.kt).
    testImplementation("junit:junit:4.13.2")
}
