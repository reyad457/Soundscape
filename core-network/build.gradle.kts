plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.soundscape.network"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// NAS/cloud attachment: SMB/NFS/WebDAV/FTP clients + Subsonic API.
// Left empty in the Phase 0 skeleton — see Soundscape-Master-Plan.md section 3.
