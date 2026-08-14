# Soundscape

An original Android bit-perfect audiophile music player. See
`Soundscape-Master-Plan.md` for the full feature roadmap — this repo
currently implements **Phase 0 (walking skeleton) + Phase 1 (USB/AAudio
exclusive-mode playback)**.

## Phase 1 — what's new

- **`:core-usb`** — `UsbAudioManager` handles USB attach/detach and the
  Android permission dance; `UacDescriptorParser` reads raw USB
  descriptors to find a device's Audio Streaming interface and reports
  its supported sample rates/bit depths/channel count (UAC1 Type I —
  see the kdoc in that file for what it does and doesn't cover).
- **`:core-audio` native engine** — `oboe_engine.cpp`/`.h` wrap a single
  Oboe `AudioStream` opened in `SharingMode::Exclusive`, exposed to
  Kotlin via `jni_bridge.cpp` and `AAudioBridge.kt`. It reports the
  *actual* granted sharing mode rather than trusting the request —
  Oboe can silently downgrade to Shared if the device/driver doesn't
  support exclusive access.
- **`PcmDecoder.kt`** — decodes tracks via `MediaCodec`, requesting
  float output so 24-bit+ sources aren't truncated to 16-bit before
  reaching the DAC. Honest caveat in its kdoc: not every codec actually
  honors that request, which is exactly why Phase 2's format-specific
  decoders (libFLAC etc.) still matter.
- **`AAudioExclusiveEngine.kt`** — ties the decoder and native bridge
  together, and is the *only* place `isBitPerfectConfirmed` is allowed
  to be `true`: it requires exclusive mode being actually granted AND
  the stream's opened rate exactly matching the decoded rate (no silent
  resampling).
- **`PlaybackEngineRouter.kt`** — what the app actually injects as
  `PlaybackEngine`. Tries the AAudio path when a recognized, permitted
  USB DAC is attached, gives it a 2-second window to prove it started,
  and falls back to the Phase 0 `ExoPlaybackEngine` otherwise — so the
  app never fails to play, it just doesn't always get the badge.
- UI: a banner above the track list shows attached USB device status
  and an "Allow" button for the permission prompt.

### Known Phase 1 gaps (intentional, tracked for follow-up — not Phase 2)

- USB device routing to a specific AAudio device ID isn't wired yet
  (`usbDeviceId` is hardcoded to `0`/default in `AAudioExclusiveEngine`)
  — descriptor parsing and permission flow work, but the last mile of
  telling AAudio "use *this* USB device" needs `AudioDeviceInfo`
  enumeration matched against `UsbAudioManager`'s device.
- Pause/resume re-opens the stream from scratch rather than holding it
  open — noted inline in `AAudioExclusiveEngine.pause()`.
- Seek isn't implemented on the AAudio path yet.

None of these block testing the core exclusive-mode path end to end;
they're the next things to tighten before calling Phase 1 fully done.

## What's actually working in this skeleton

- Multi-module Gradle structure matching the plan's architecture
  (`:app`, `:core-library`, `:core-audio`, `:core-dsp`, `:core-usb`,
  `:core-network` — the last three are empty stubs for now).
- MediaStore-based local library scan → Room database.
- Basic playback via Media3 ExoPlayer, wrapped behind a `PlaybackEngine`
  interface so the Phase 1 AAudio/USB-exclusive engine can be swapped in
  later without touching the UI or ViewModel layer.
- Compose UI: track list, tap-to-play, a now-playing bar with play/pause.
- Data models already shaped for what's coming next: `Track` carries
  fingerprint/format/fidelity fields even though nothing populates them
  yet; `NetworkSource` and `LyricAttachment` entities exist for the NAS
  and manual-lyrics features even though there's no UI for them yet.

## What's deliberately NOT here yet

- No real bit-perfect path — `ExoPlaybackEngine` is explicit that
  `isBitPerfectConfirmed` is always `false`. That's Phase 1.
- No format-specific decoders (Phase 2) — relies on whatever ExoPlayer
  and MediaStore already handle (FLAC/WAV/MP3/AAC/OGG/Opus natively;
  ALAC/APE/WavPack/DSD need Phase 2 work).
- No DSP, no analysis tools, no NAS/network source UI, no manual lyric
  attachment UI — modules and data models are scaffolded, screens aren't.

## Opening the project

This was generated outside Android Studio, so the Gradle wrapper jar
itself isn't included (binary, and this environment has no network
access to Gradle's distribution servers to verify one). To get running:

1. Open the `soundscape/` folder in Android Studio (Koala or newer).
2. Let Android Studio regenerate the wrapper (`File > Sync Project with
   Gradle Files` — it will offer to fetch `gradle-8.9-bin.zip`, which
   `gradle/wrapper/gradle-wrapper.properties` already points at).
3. Run on a device/emulator with **API 30+** (min SDK, chosen for
   modern USB/AAudio APIs needed by Phase 1).

## Module map

```
app/            Compose UI, ViewModels, DI wiring, manifest
core-library/   Room DB, entities (Track, NetworkSource, LyricAttachment),
                MediaStore scanner
core-audio/     PlaybackEngine interface, Phase 0 ExoPlayer engine,
                Phase 1 AAudio exclusive-mode engine (native/ + Kotlin),
                PlaybackEngineRouter (picks between them)
core-usb/       Phase 1 — UsbAudioManager, UacDescriptorParser
core-dsp/       empty — Phase 3 (parametric EQ, convolution, crossfeed)
core-network/   empty — NAS/WebDAV/Subsonic attachment (master plan §3)
```

## How to verify each phase

Run this after pulling any phase's changes, before assuming it works:

1. **Open in Android Studio, let Gradle sync fully** — this is the real
   smoke test. Phase 1 specifically needs the NDK (Android Studio will
   offer to install NDK `26.3.11579264` on first sync if missing) since
   `:core-audio` now has a native module.
2. **Build → Make Project** and fix anything Gradle/lint flags before
   moving on — don't let build errors carry into the next phase.
3. **Run on a physical device** (an emulator can't exercise USB audio
   or, in later phases, most of the DSP/analysis tools either).
   - Phase 0: confirm the library scan finds local tracks and playback
     works via the on-screen controls.
   - Phase 1: plug in a USB DAC, tap "Allow" on the permission banner,
     play a track, and check Logcat for `SoundscapeOboe` tags —
     `"Requested Exclusive... but got Shared"` tells you the DAC/driver
     didn't grant exclusive mode (still plays, just not bit-perfect
     yet); no such warning + the "Bit-perfect" chip appearing in the
     now-playing bar means it worked.
4. **Skim the "Known gaps" list** in that phase's README section before
   reporting something as broken — some limitations are already known
   and intentionally deferred.

## How to push each phase to git

This repo isn't initialized yet on your machine — first phase you pull
down, run once:

```bash
cd soundscape
git init
git add .
git commit -m "Phase 0: walking skeleton (Compose UI, MediaStore scan, ExoPlayer)"
```

From then on, after verifying a phase per the steps above:

```bash
git add .
git commit -m "Phase 1: USB/AAudio exclusive-mode playback path"
git push origin main   # after adding a remote once: git remote add origin <your-repo-url>
```

Commit message convention going forward: `"Phase N: <one-line summary>"`
so the git log doubles as a build log against the master plan's phases.

## Suggested next step

With Phase 1's exclusive-mode path in place, Phase 2 (format-specific
decoders — libFLAC, libwavpack, DSD/DoP parsing) is the natural next
step: it closes the gap `PcmDecoder`'s kdoc flags, where MediaCodec
doesn't guarantee true bit-perfect decoding for every format.
