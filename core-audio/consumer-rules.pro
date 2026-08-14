# flac_jni_decoder.cpp and jni_bridge.cpp call these by exact name/signature
# via JNIEnv::GetMethodID / the Java_..._methodName symbol convention.
# Nothing in Kotlin bytecode visibly calls them, so R8 would otherwise
# consider them unused and strip or rename them, silently breaking native
# playback in release builds only (easy to miss — works in debug, crashes
# or no-ops in release).
-keepclassmembers class com.soundscape.audio.nativebridge.FlacBridge {
    private void onFormatKnown(int, int, int);
    private void onPcmFrame(int[], int);
}
-keep class com.soundscape.audio.nativebridge.AAudioBridge { *; }
-keep class com.soundscape.audio.nativebridge.FlacBridge { *; }
