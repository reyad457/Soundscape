cmake_minimum_required(VERSION 3.22.1)
project("soundscape_dsp")

add_library(soundscape_dsp SHARED
    dsp_jni_bridge.cpp
)

target_compile_options(soundscape_dsp PRIVATE -Wall -Wextra -Werror=return-type -O3)
