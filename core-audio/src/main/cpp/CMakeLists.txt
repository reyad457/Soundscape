cmake_minimum_required(VERSION 3.22.1)
project("soundscape_audio")

add_library(soundscape_audio SHARED
    oboe_engine.cpp
    jni_bridge.cpp
    flac_jni_decoder.cpp
    alac_jni_decoder.cpp
    wavpack_jni_decoder.cpp
    ape_jni_decoder.cpp
    flac/src/bitmath.c
    flac/src/bitreader.c
    flac/src/cpu.c
    flac/src/crc.c
    flac/src/fixed.c
    flac/src/float.c
    flac/src/format.c
    flac/src/lpc.c
    flac/src/md5.c
    flac/src/memory.c
    flac/src/stream_decoder.c
    alac/ALACBitUtilities.c
    alac/ALACDecoder.cpp
    alac/EndianPortable.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c
    wavpack/src/common_utils.c
    wavpack/src/decorr_utils.c
    wavpack/src/entropy_utils.c
    wavpack/src/open_utils.c
    wavpack/src/open_filename.c
    wavpack/src/read_words.c
    wavpack/src/unpack.c
    wavpack/src/unpack_floats.c
    wavpack/src/unpack_seek.c
    wavpack/src/unpack_utils.c
    ape/APEDecompress.cpp
    ape/APEInfo.cpp
    ape/APEHeader.cpp
    ape/APETag.cpp
    ape/UnBitArray.cpp
    ape/UnBitArrayBase.cpp
    ape/NewPredictor.cpp
    ape/NNFilter.cpp
    ape/Prepare.cpp
    ape/CharacterHelper.cpp
    ape/CircleBuffer.cpp
    ape/GlobalFunctions.cpp
    ape/MACUtils.cpp
    ape/Assembly/common.cpp
)

target_include_directories(soundscape_audio PRIVATE
    flac/include alac wavpack/include wavpack/src ape ape/Assembly .
)

# libFLAC compiles with no config.h present (all HAVE_* / FLAC__HAS_OGG
# checks fall back safely to "off" when undefined — see README for the
# specific files vendored and why). We do supply a few defines the NDK
# can genuinely satisfy so the portable code paths use real libc features
# instead of libFLAC's own fallbacks.
target_compile_definitions(soundscape_audio PRIVATE
    ANDROID
    HAVE_STDINT_H
    HAVE_INTTYPES_H
    HAVE_LROUND
    FLAC__NO_ASM
    FLAC__INTEGER_ONLY_LIBRARY=0
    _7ZIP_PPMD_SUPPPORT=0
)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(soundscape_audio
    oboe::oboe
    android
    log
    m
)

target_compile_options(soundscape_audio PRIVATE -Wall -Wextra -Werror=return-type)
# libFLAC's own sources aren't held to our -Werror=return-type bar — it's
# third-party code we vendor, not code we're claiming to have gotten right ourselves.
set_source_files_properties(
    flac/src/bitmath.c flac/src/bitreader.c flac/src/cpu.c flac/src/crc.c
    flac/src/fixed.c flac/src/float.c flac/src/format.c flac/src/lpc.c
    flac/src/md5.c flac/src/memory.c flac/src/stream_decoder.c
    alac/ALACBitUtilities.c alac/ALACDecoder.cpp alac/EndianPortable.c
    alac/ag_dec.c alac/dp_dec.c alac/matrix_dec.c
    wavpack/src/common_utils.c wavpack/src/decorr_utils.c wavpack/src/entropy_utils.c
    wavpack/src/open_utils.c wavpack/src/open_filename.c wavpack/src/read_words.c
    wavpack/src/unpack.c wavpack/src/unpack_floats.c wavpack/src/unpack_seek.c
    wavpack/src/unpack_utils.c
    ape/APEDecompress.cpp ape/APEInfo.cpp ape/APEHeader.cpp ape/APETag.cpp
    ape/UnBitArray.cpp ape/UnBitArrayBase.cpp ape/NewPredictor.cpp ape/NNFilter.cpp
    ape/Prepare.cpp ape/CharacterHelper.cpp ape/CircleBuffer.cpp
    ape/GlobalFunctions.cpp ape/MACUtils.cpp ape/Assembly/common.cpp
    PROPERTIES COMPILE_FLAGS "-w"
)
