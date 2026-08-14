# dsp_jni_bridge.cpp binds to DspBridge's external fun declarations by
# the Java_com_soundscape_dsp_DspBridge_* JNI symbol convention — same
# risk core-audio/consumer-rules.pro documents for the audio bridges:
# R8 could strip or rename this class in release builds specifically.
-keep class com.soundscape.dsp.DspBridge { *; }
