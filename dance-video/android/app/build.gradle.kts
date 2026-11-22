plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "io.github.yw5aj.dancevideo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yw5aj.dancevideo"
        minSdk = 35
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    signingConfigs {
        // The key never lives in the repo: CI writes it to DV_KEYSTORE from a repo secret
        // (or makes a throwaway one when the secret is missing).
        create("release") {
            storeFile = file(System.getenv("DV_KEYSTORE") ?: "release.jks")
            storePassword = System.getenv("DV_KEYSTORE_PASSWORD") ?: "dancevideo"
            keyAlias = "dancevideo"
            keyPassword = System.getenv("DV_KEYSTORE_PASSWORD") ?: "dancevideo"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // ffmpeg ships as an executable named libffmpeg.so; it must be extracted to disk to run.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
