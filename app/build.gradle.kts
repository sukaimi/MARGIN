plugins {
    id("com.android.application")
}

android {
    namespace = "ai.codeandcraft.secondbrain"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.codeandcraft.secondbrain"
        minSdk = 26
        targetSdk = 35
        versionCode = 68
        versionName = "1.0-pilot68"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}
