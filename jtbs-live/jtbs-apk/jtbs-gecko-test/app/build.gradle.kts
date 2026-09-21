plugins {
  alias(libs.plugins.android.application)
  // alias(libs.plugins.compose.compiler)
  // alias(libs.plugins.kotlin.serialization)
  // alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.jtbs"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jtbs.box.geckotest"
        minSdk = 22
        targetSdk = 22
        versionCode = 1000
        versionName = "1.0.0"
        multiDexEnabled = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = false
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")
    implementation("org.mozilla.geckoview:geckoview:95.0.20211129150630")
}
