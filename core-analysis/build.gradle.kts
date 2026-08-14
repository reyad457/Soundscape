plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.soundscape.analysis"
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

// Phase 4 (first slice): loudness/True Peak/LRA scanning via a vendored
// libebur128 (the actual ITU-R BS.1770/EBU R128 reference implementation).
// Fake-lossless detection and spectrogram rendering need an FFT engine —
// not done in this pass, see FidelityScanner.kt's kdoc.

dependencies {
    implementation(project(":core-audio")) // reuses the existing *NativeDecoder classes to decode for scanning
    implementation(project(":core-library")) // AudioFormat, Track

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
}
