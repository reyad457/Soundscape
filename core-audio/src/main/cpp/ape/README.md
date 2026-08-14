# Vendored Monkey's Audio (APE) decoder (decode-only)

Source code: https://github.com/fernandotcl/monkeys-audio (a Unix-portable
mirror of Monkey's Audio's own SDK source)
License: 3-Clause BSD — see `LICENSE-BSD3.txt`, sourced directly from the
official https://monkeysaudio.com/license.html (Monkey's Audio moved to
BSD in a relatively recent relicensing; older redistributed copies of
this codebase may still carry the project's old, more restrictive
license text — the BSD terms here are the current, authoritative ones
from the copyright holder's own site).

## What's included

Traced from `APEDecompress.cpp`/`APEInfo.cpp`'s actual include graph,
same methodology as the FLAC and WavPack vendoring: `APEDecompress`,
`APEInfo`, `APEHeader`, `APETag` (constructed internally even when no
tag object is passed in — needed for correct seek/size math, not just
tag reading), `UnBitArray`/`UnBitArrayBase`, `NewPredictor`, `NNFilter`,
`Prepare`, plus the `Shared/` portability helpers (`CharacterHelper`,
`CircleBuffer`, `GlobalFunctions`, `MACUtils`) and `Assembly/common.cpp`
(provides the portable C fallback for the adaptive-filter/dot-product
routines that upstream can otherwise implement in hand-written asm).

Deliberately NOT included: the encoder (`APECompress*.cpp`), the
"Old/" legacy pre-3.98-format decode path (guarded by
`BACKWARDS_COMPATIBILITY`, which we don't define), `MD5.cpp` (only
used by the CLI tool, not the decode path), `BitArray.cpp` (the
encoder's bit *writer* — `UnBitArray` is the decoder's reader and is
unrelated despite the similar name), and all `.asm` files.

A few unused `#include` lines were removed from the vendored `.cpp`
files (e.g. `UnBitArray.cpp` including `BitArray.h`, `APEInfo.cpp` and
`NewPredictor.cpp` both including the encoder's `APECompress.h`) —
verified via grep that nothing in this decode-only file set actually
calls into those unused headers before removing them, so this isn't
guessing, it's confirmed-dead code paths.

## Why a hand-written config.h

Unlike libFLAC's `#ifdef HAVE_CONFIG_H` guard (which lets you skip
`config.h` entirely if you don't define that macro), this codebase
includes `"config.h"` unconditionally from `Shared/All.h` and
`Shared/MACUtils.h`. Upstream generates it from `config.h.in` via
autoconf/CMake; we just hand-wrote the equivalent for an Android
NDK/Bionic target instead of running their build system — see the
comments in `config.h` itself for what each define does and why.

## The IO abstraction

`ape_jni_decoder.cpp` implements its own minimal `CIO` subclass
(`CFdIO`) over a `FILE*`, rather than vendoring upstream's own
`Shared/StdLibFileIO.cpp` — that implementation insists on opening a
file by name/path internally, which doesn't fit local tracks arriving
as `content://` URIs with no filesystem path. Same "own the fd, not
the filename" choice `flac_jni_decoder.cpp` and `wavpack_jni_decoder.cpp`
both make.
