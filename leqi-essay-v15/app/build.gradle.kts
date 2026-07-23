plugins {
    id("com.android.application")
}

android {
    namespace = "com.leqi.essaycompat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.leqi.essaycompat"
        minSdk = 23
        targetSdk = 28
        versionCode = 15
        versionName = "1.5.0-rokid"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
