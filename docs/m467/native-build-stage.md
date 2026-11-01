# Herrin M4.67 native playback build and acceptance gates

Status: BLOCKED for complete FongMi playback parity. This is a reproducible native CANDIDATE build plan, not an APK or evidence of TCL playback.

## Upstream provenance

| Upstream | Pinned SHA | Role |
|---|---|---|
| FongMi/TV | 5e191aaced194e5cac5668cc91908ecb65336222 | Defines native AAR ABI through MpvNativeLibraries.groovy |
| FongMi/media | 3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d | Public snapshot missing seven required custom Java APIs |
| FongMi/mpv-android | 049ade89abc85fc05ef73dc9ba6f1810466c9f8a | Native recipes, NDK 30.0.16248370 / Android API 24 |

The pinned TV requires MPV AAR with libmpv.so, libplayer.so and libc++_shared.so, and a separate FFmpeg decoder AAR with libffmpeg.so, libffmpegJNI.so and optionally libffmpegDoviJNI.so. MPV and player JNI must link unified libffmpeg.so, and player JNI must also link libmpv.so. Split libav*.so/libsw*.so dependencies are rejected.

The initial Herrin checker accidentally required libffmpeg.so within the MPV AAR. The revised scripts/m467/verify_native_kit.py aligns to the pinned TV contract, verifies both ARM ABIs, ELF machine/class, dynamic dependencies and AAR layout. This does NOT verify Java/JNI runtime behavior.

## Opt-in build of public native candidates

1. Clone https://github.com/FongMi/mpv-android.git and checkout SHA 049ade89abc85fc05ef73dc9ba6f1810466c9f8a.
2. Follow upstream buildscripts/download.sh (internet access and native prerequisites required). Record actual dependency SHAs/checksums.
3. In the Herrin checkout, run: bash scripts/m467/build_native_candidates.sh --mpv-source /absolute/path/to/mpv-android --dry-run
4. Run the same command without --dry-run. It builds both ARM ABIs through upstream native-only buildall.sh.
5. Output remains in upstream app/src/main/libs/arm64-v8a and app/src/main/libs/armeabi-v7a. The wrapper checks libmpv.so, libplayer.so and libffmpeg.so and runs upstream ffmpeg-shared.py verify-package.

DO NOT copy native candidates directly into Herrin. The public media snapshot lacks seven custom APIs; actual AAR generation also needs the matching Media3 source, correct JNI interfaces, Gradle module packaging and license verification. Do not create fake compatibility stubs or rename old binaries.

When real compatible AARs exist, run the local verification:

~~~sh
python3 scripts/m467/verify_native_kit.py \
  --kit-dir /path/to/native-aars \
  --media-source /path/to/matching-fongmi-media \
  --output native-kit-check.json
~~~

BLOCKED / exit code 2 means inputs are not safe to integrate. INPUTS_STRUCTURALLY_VALID_NOT_RUNTIME_VERIFIED still does not imply playback, native build success, or TCL compatibility.

## Evidence required before full-playback release

| Case | Proof needed | Status |
|---|---|---|
| MP4, HLS, DASH, MKV | Decode actual frames from authorized synthetic HTTP streams | Existing Media3 simulator tests only |
| HEVC 8/10-bit, 4K 24/60 | Actual decoder identity, frame counters, selected output format and resolution | Not verified on TCL |
| HDR10, HDR10+, Dolby Vision | Actual TV HDR mode plus real output video and 60s stable playback | Not verified |
| Dolby Atmos, AC-3, E-AC-3, TrueHD | Actual audio decode vs passthrough and output reported by TV/receiver | Not verified |
| ASS/SSA styled subtitles and libass | Compare actual positioned/styled graphics, not merely cue text | Basic cue tests only |
| Dual audio, subtitle/line changes, seeking | Repeat 30 times without stale headers, memory leak or crash | Partial emulator coverage |
| Spider proxy and HTTP403/503 | Keep authorization; 403 stops; permitted 5xx backup produces frames | Emulator coverage only |
| TCL T7L Ultra authorized streaming | Verified video+audio on hardware, codec logcat and failure categories | Not verified |

All results must distinguish CI from physical-device evidence; never mark 4K/HDR/Atmos green merely because an APK compiled.

## Unchanged project constraints

Keep Herrin UI, TMDB, posters, film data, episode selection, remote focus and stable Media3 fallback intact. Keep PR #76 Draft; do not merge master. Respect FongMi GPL-3.0 redistribution terms and do not include private tokens or unlicensed codec binaries.
