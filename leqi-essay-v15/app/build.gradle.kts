plugins {
    id("com.android.application")
}

android {
    namespace = "com.lensmind.rokid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lensmind.rokid"
        minSdk = 28
        targetSdk = 28
        versionCode = 6
        versionName = "1.0.5"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        dex {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
