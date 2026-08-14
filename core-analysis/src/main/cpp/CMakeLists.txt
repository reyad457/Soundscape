cmake_minimum_required(VERSION 3.22.1)
project("soundscape_analysis")

add_library(soundscape_analysis SHARED
    loudness_jni_bridge.cpp
    ebur128/ebur128.c
)

# ebur128.c does #include <sys/queue.h> — Android's Bionic libc provides
# this (standard BSD-derived header), but we vendor upstream's own
# fallback copy too and put it FIRST on the include path so this doesn't
# depend on trusting Bionic's copy is complete, since that can't be
# verified without an actual NDK toolchain here.
target_include_directories(soundscape_analysis PRIVATE ebur128/queue)

target_compile_options(soundscape_analysis PRIVATE -O3)
set_source_files_properties(ebur128/ebur128.c PROPERTIES COMPILE_FLAGS "-w")

target_link_libraries(soundscape_analysis
    log
    m
)
