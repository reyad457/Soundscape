// Hand-written replacement for autoconf/CMake-generated config.h (see
// ../ape/../wavpack equivalents in this repo for the same pattern with
// FLAC/WavPack — this project's upstream just doesn't have a
// HAVE_CONFIG_H guard around the include, so we supply our own instead
// of running their build system). Values chosen for Android NDK's
// Bionic libc, decode-only, no assembly.
#ifndef CONFIG_H
#define CONFIG_H

#define HAVE_SYS_IOCTL_H
#define HAVE_FCNTL_H
#define HAVE_MEMORY_H
#define HAVE_STDARG_H
#define HAVE_STDLIB_H
#define HAVE_STRING_H
#define HAVE_UNISTD_H
#define STDC_HEADERS

#define HAVE_MEMCMP
#define HAVE_STAT
// NDK's Bionic libc doesn't provide wcscasecmp — NoWindows.h supplies a
// portable fallback (mac_wcscasecmp) when this is left undefined.
/* #undef HAVE_WCSCASECMP */

#define BUILD_CROSS_PLATFORM

// Deliberately NOT defined: correctness-first baseline, same choice as
// FLAC__NO_ASM in the libFLAC vendoring — SIMD optimization is a later
// pass, not required for correct decode. See Assembly/common.cpp: with
// this undefined, asmInit() just wires up the portable C implementations.
/* #undef ENABLE_ASSEMBLY */
/* #undef ARCH_X86 */
/* #undef ARCH_X86_64 */

/* #undef WORDS_BIGENDIAN */

#endif
