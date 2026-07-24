plugins {
    id("com.android.application")
}

android {
    namespace = "com.lensmind.essay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lensmind.essay"
        minSdk = 28
        targetSdk = 28
        versionCode = 2
        versionName = "1.0.2"
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
