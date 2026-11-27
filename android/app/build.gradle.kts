plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "se.lumen.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "se.lumen.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = run
        versionName = "1.0.$run"
    }

    signingConfigs {
        create("lumen") {
            storeFile = file("lumen.keystore")
            storePassword = "lumen-app"
            keyAlias = "lumen"
            keyPassword = "lumen-app"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lumen")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lumen")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../../shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.13.1")
}
