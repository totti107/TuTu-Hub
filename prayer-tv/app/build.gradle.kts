plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = "1.0.0"

android {
    namespace = "com.tutu.prayertv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tutu.prayertv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = appVersion
    }

    // Fixed key so every new build installs over the previous one. Personal app: the key is not secret.
    signingConfigs {
        create("prayer") {
            storeFile = file("prayer.jks")
            storePassword = "prayertv"
            keyAlias = "prayer"
            keyPassword = "prayertv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("prayer")
        }
        debug {
            signingConfig = signingConfigs.getByName("prayer")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false; checkReleaseBuilds = false }
}
