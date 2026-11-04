# Official Media3 player port boundary

Reference: FongMi/TV `fongmi` commit `305dff233cde512e2c83f71529b7c3ecfd61fefc`.
Original author FongMi; derivatives remain GPL-3.0-only. See `android-tv/LICENSE-GPL-3.0.md`.
Modifications: Herrin, 2026-11-04. This module implements an Exo-only subset. It does **not** complete P0 or replace every upstream PlayerManager feature.

| Pinned upstream contract | Concrete host implementation |
| --- | --- |
| `PlayerManager.start/startMediaItem`, `PlayerEngine.start` | `PlayerManager.start` → `ExoPlayerEngine.start` installs a real per-attempt MediaSource, start position, prepare and play intent. |
| `PlayerManager.onPlayTimeout` and ready/end cancellation | Main-looper Handler and generation-checked `LoadDeadline`; stop before notifying existing host recovery; stop/background/release cancel pending callbacks. |
| `PlaySpec`, `MediaItemFactory.from` | Authorized immutable host media, opaque media key, metadata, `/proxy/mpd/` format inference, request metadata/headers, 15s image duration, external subtitle IDs and default-language selection. |
| `ExoUtil` renderer, buffer and selector setup | Official Media3 1.9.4 renderers, platform decoder fallback, video prewarming, highest supported bitrate, text preference, AAC preference, tunneling, local/streaming buffers and audio focus/noisy handling. Movie audio content type is an intentional host change from upstream DEFAULT. |
| `ExoMediaSourceFactory` concatenation and TS scan | Existing GPL source adaptation under `com.fongmi` remains in use; it retains the prior `5e191aaced194e5cac5668cc91908ecb65336222` attribution. The pinned revision still uses the same concatenation loop and 10× TS timestamp scan. The factory is rebuilt with each attempt's headers; no cross-session URL cache. |
| Player listener and transport | Real ExoPlayer state/tracks/first-frame/error listeners feed the existing panel diagnostics, UI and recovery. Existing MediaSession keys, native PlayerView, D-pad seek/play/track/subtitle controls and focus behavior remain connected to that same player. Manager owns session creation/release. |

## Exact exclusions and deliberate adaptations

- The 1,190-line upstream `PlayerManager` is not copied wholesale. Herrin owns authorization, parse orchestration, source refresh/fallback budgets and progress persistence. Manager `parse`, browse preparation, preload transition, timeshift, speed-press/effects, playback-log snapshot, engine rebuild/MPV fallback and database track restoration are not ported here. Existing host source retries are not represented as upstream-equivalent.
- Official Media3 lacks the referenced fork's `DecoderManager`, `DecodeTrackSelector`, `DecoderMode`, `setDecoderManager`, `setDolbyVisionOutputPolicy` and `setEnableSurfaceControlFrameRetention` paths. Installed platform codecs can fall back; no FFmpeg/MPV/native decoder library is shipped by this work.
- `IsoDiscPreparation`, `IsoNavigationPlayerController`, `ProgressiveIsoCache`, `IsoUri`, disc menus, media chapters/editions and selected ISO edition are unavailable in the official dependency. No placeholder APIs or silent success methods implement them.
- `LibassPlaybackSession`, `LibassSubtitleController`, `SecondaryTextTrackSelector`, `SecondaryTextOutput`, subtitle transcript/parser sessions and independent primary/secondary/audio offset commands are unavailable. One official Media3 subtitle renderer remains; ASS/SSA basic text is not libass styling parity.
- The upstream custom `ContentTypeResolvingMediaSource`, `LocalClearKeyLicense`, `DefaultExtractorsFactory.setMp4ClearKeys`, and HTTP Matroska `FLAG_DEFER_SEEK_FOR_CUES`/`setMatroskaExtractorFlagsForHttpSources` paths are not implemented. Normal official MP4/HLS/DASH/Matroska support remains, without fork-specific probing or clear-key-in-extractor behavior.
- `EndTrimMediaSource` and `DefaultPositionMediaSource` are absent; dynamic ending trim and seamless preloaded queue transitions are not supplied.
- `PlaybackMedia` has no DRM, Sub.rawFlag/explicit subtitle MIME, auth context/tag, metadata title/artwork or timeshift fields. Their upstream semantics cannot be recovered from URLs or invented; `PlaySpec` metadata defaults to EMPTY, while explicit host-provided metadata can be carried internally.
- The shared upstream URL cache and upstream `Auth.Context` OkHttp interceptor are deliberately not reused across authorized sessions. Host DefaultHttpDataSource uses per-attempt headers, explicit redirect policy, default host user-agent and 12s connect/read limits. This does not promise upstream cookie/challenge-auth network equivalence.
- Manager timeouts apply once per active buffering interval and are suspended when paused or in background; foreground start does not automatically resume a user/background pause. Recovery preserves play/pause intent. Real TCL audio output, passthrough, tunneled playback and sustained provider decoding require device acceptance.

Tests distinguish a passing pure deadline contract from executed decoder/UI evidence. Compiling instrumentation does not prove continuous frames, audible output or TCL compatibility.
