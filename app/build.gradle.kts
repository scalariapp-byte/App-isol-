plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.isolapp.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.isolapp.manager"
        minSdk = 28
        targetSdk = 35
        val code = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = 100 + code
        versionName = "1.$code"
    }

    signingConfigs {
        create("release") {
            val store = System.getenv("SIGNING_STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "isolateur"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("SIGNING_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
