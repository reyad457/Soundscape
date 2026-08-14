plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.soundscape.dsp"
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "26.3.11579264"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Phase 3: parametric EQ (biquad, RBJ Audio EQ Cookbook formulas) and a
// basic crossfeed stage — see dsp_chain.h. Convolution (room/headphone
// correction) is NOT here yet — a bigger undertaking (FFT engine,
// impulse-response file loading, partitioned convolution for real-time
// efficiency) tracked separately, not rushed into this pass.

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
}
