# Vendored libFLAC (decode-only)

Source: https://github.com/xiph/flac (upstream `master` at time of vendoring)
License: BSD-style, see `LICENSE-libFLAC.txt` (Xiph.Org Foundation license)

## What's included

Only the files needed for **decoding**, with no ASM/SIMD intrinsics
(`FLAC__NO_ASM` is defined in CMakeLists.txt — portable C only, for a
correct-first baseline; SIMD optimization is a reasonable later pass,
not needed for correctness):

- `src/`: bitmath, bitreader, cpu, crc, fixed, float, format, lpc, md5,
  memory, stream_decoder — the decode call graph, traced from
  `stream_decoder.c`'s own `#include "private/*.h"` list.
- `include/`: the matching public (`FLAC/`), private, protected, and
  `share/` (portability compat layer) headers.

Deliberately NOT included: the encoder, the Ogg mapping, the CLI tools,
`metadata_object.c`/`metadata_iterators.c` (not needed by the decode
path — verified by grepping `stream_decoder.c` for calls into them; it
constructs `FLAC__StreamMetadata` itself).

## Why no config.h

Every vendored file guards its `config.h` include with
`#ifdef HAVE_CONFIG_H`, and macros like `FLAC__HAS_OGG` are used in
`#if` directives with no explicit definition anywhere — the C
preprocessor treats an undefined macro as `0` in `#if`, so this
degrades safely to "Ogg support off" without us needing to generate
libFLAC's usual autoconf/CMake-generated config.h. `HAVE_STDINT_H`,
`HAVE_INTTYPES_H`, and `HAVE_LROUND` are supplied as real compile
definitions instead (NDK genuinely has all three), so the portable
code paths use real libc rather than libFLAC's own fallbacks.
