plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.soundscape.dsp"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Phase 3: parametric EQ (biquad), convolution, crossfeed, dither.
// Left empty in the Phase 0 skeleton — see Soundscape-Master-Plan.md.
