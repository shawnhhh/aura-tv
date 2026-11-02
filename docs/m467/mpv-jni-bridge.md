# M4.67 alternate native MPV JNI bridge — stage 1

**Status: source bridge only; NOT enabled in the consumer player.**

From immutable upstream FongMi/mpv-android commit
`049ade89abc85fc05ef73dc9ba6f1810466c9f8a`:

- `android-tv/app/src/main/java/is/xyz/mpv/MPVLib.kt`: exact JNI API and observer callbacks, no signature/package modifications.
- `android-tv/app/src/main/java/is/xyz/mpv/BaseMPVView.kt`: original SurfaceView surface lifecycle.
- Original MIT license is included verbatim in `android-tv/LICENSE-MPV-ANDROID-MIT.txt`.
- `MpvNativeAvailability.kt`: conservative four-library presence gate, never calls `MPVLib` unless compatible binaries become available and are explicitly enabled in a subsequent stage.
- `M467MpvNativeContractTest.kt`: checks native method signatures with `Class.forName(..., false,...)`, and tests installed-library gate without initializing JNI.

This source bridge is an alternative integration path using actual mpv-android JNI.
It avoids incorrectly treating FongMi's custom Media3 decoder manager as a
standard AndroidX API. It does **not** replace ExoPlayer or claim to provide
MPV rendering yet. The upstream `libplayer.so` is bound to the original
`is.xyz.mpv.MPVLib` class; renaming that class would break native JNI.

Native build from FongMi/mpv-android is running separately in PR #76:
https://github.com/shawnhhh/aura-tv/actions/runs/41375193241

## Next verifiable integration gates

1. Complete both ARM native builds with unified `libffmpeg.so`; inspect ELF
   machine, symbols and dynamic dependencies. Native build failure must remain red.
2. Package genuine `libffmpeg.so`, `libmpv.so`, `libplayer.so`,
   `libc++_shared.so` into the proper ABI directories in a **separate**
   opt-in test APK. Never copy incompatible old split FFmpeg libraries.
3. Verify the JNI `create/init`, surface attach/replacement/detach/destroy
   lifecycle with Android emulator using the original synthetic MP4 stream.
4. Only after actual decoded frames are observed, implement bounded
   Media3->MPV fallback on genuine decoder failures, with an explicit user
   selection and the existing per-session playback headers. Never fallback
   after HTTP 401/403/TLS/DRM errors.
5. Verify full subtitles (ASS/SSA styled, libass), HEVC and HDR/device output
   on TCL TV. Keep all existing Herrin UI, TMDB, posters and film data unchanged.

This PR remains Draft against the M4.67 integration branch, not master.
