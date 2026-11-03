import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Własny klucz podpisu (nie trafia do repozytorium): keystore.properties w katalogu głównym
// z polami storeFile, storePassword, keyAlias, keyPassword. Bez niego build release
// podpisuje się kluczem debug, więc projekt da się zbudować od razu po sklonowaniu.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

// Własna tapeta (nie trafia do repozytorium): app/personal/res/raw/bg.jpg i bg_blur.jpg
// nadpisują domyślną tapetę z src/main. Przygotujesz je skryptem tools/prepare_wallpaper.py.
val personalRes = file("personal/res")

android {
    namespace = "pl.czysty.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.czysty.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    if (personalRes.isDirectory) {
        sourceSets.getByName("release").res.srcDir(personalRes)
        sourceSets.getByName("debug").res.srcDir(personalRes)
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
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    lint {
        checkReleaseBuilds = false
    }
}
