# loudness_jni_bridge.cpp binds to LoudnessBridge's external fun
# declarations by the Java_com_soundscape_analysis_LoudnessBridge_*
# JNI symbol convention — same risk documented in core-audio and
# core-dsp's consumer-rules.pro.
-keep class com.soundscape.analysis.LoudnessBridge { *; }
