cmake_minimum_required(VERSION 3.22.1)
project("soundscape_audio")

add_library(soundscape_audio SHARED
    oboe_engine.cpp
    jni_bridge.cpp
)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(soundscape_audio
    oboe::oboe
    android
    log
)

target_compile_options(soundscape_audio PRIVATE -Wall -Wextra -Werror=return-type)
