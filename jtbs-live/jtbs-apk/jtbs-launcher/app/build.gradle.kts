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
        applicationId = "com.jtbs.box"
        minSdk = 22
        targetSdk = 22
        versionCode = 8001
        versionName = "8.0.1"
        multiDexEnabled = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable.add("ExpiredTargetSdkVersion")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation("com.google.android.exoplayer:extension-rtmp:2.19.1")
}
