plugins {
    id("com.android.application")
}

android {
    namespace = "com.hongcha.teslawatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hongcha.teslawatch"
        minSdk = 30
        targetSdk = 34
        versionCode = 18
        versionName = "2.7"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../tesla-watch.keystore")
            storePassword = "watchpass"
            keyAlias = "teslawatch"
            keyPassword = "watchpass"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.tiles:tiles-material:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.0")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.0")
    implementation("com.google.guava:guava:33.2.1-android")
}
