plugins {
    id("com.android.application")
}

android {
    namespace = "com.leqi.essaycompat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lensmind.essay"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
