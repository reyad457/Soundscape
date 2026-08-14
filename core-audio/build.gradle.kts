plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.soundscape.audio"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // Phase 1: native AAudio/Oboe exclusive-mode engine.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "26.3.11579264"

    buildFeatures {
        prefab = true // required to consume Oboe's prefab-packaged native libs
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-usb")) // AAudioExclusiveEngine routes through UsbAudioManager
    implementation(project(":core-library")) // AudioFormat enum used to pick a decoder per track
    implementation(project(":core-dsp")) // ParametricEq — EQ/crossfeed processing before writeFrames

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Google's official Oboe release, published with a Prefab package so
    // CMake can `find_package(oboe REQUIRED CONFIG)` without vendoring C++ sources.
    implementation("com.google.oboe:oboe:1.9.3")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
}
