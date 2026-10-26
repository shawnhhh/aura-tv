# HERRIN M2.2 — Episode-scoped authorized playback and resume

## Status and branch
Standalone draft PR stacked on M2.1 PR #4, from the validated Android TV 13/13 baseline.
**Keep PR #2 / #3 / #4 and master untouched; do not merge without review.**
The M1.6 full-screen hero, two shelves and approved focus behavior stay unchanged.

## Optional version-1 JSON playback schema
HERRIN reads only the JSON catalog URL **explicitly configured by the user** in settings. This extension does not discover websites, DRM keys, plugins or illegal sources. Legacy catalogs without `playback` remain supported and show the demo player.

```json
{
  "version": 1,
  "items": [
    {
      "id": "my-licensed-series",
      "title": "我的授权演示剧集",
      "category": "剧集",
      "year": "2026",
      "synopsis": "仅供用户拥有播放权的内容。",
      "artwork_url": "https://media.example.org/my-licensed-series.jpg",
      "playback": {
        "episodes": [
          {
            "number": 1,
            "title": "第一集",
            "lines": [
              {
                "id": "official",
                "title": "官方 HLS",
                "url": "https://media.example.org/series/ep1/index.m3u8"
              },
              {
                "id": "backup",
                "title": "自有备用 MP4",
                "url": "https://media.example.org/series/ep1/video.mp4"
              }
            ]
          },
          {
            "number": 2,
            "title": "第二集",
            "lines": [
              {
                "id": "official",
                "title": "官方 DASH",
                "url": "https://media.example.org/series/ep2/index.mpd"
              }
            ]
          }
        ]
      }
    }
  ]
}
```

- `playback` optional. When present, require 1–99 distinct episodes numbered 1–99, and 1–8 distinct lines each. Every line has an ASCII id, a short title and an actual validated **HTTPS** URL. Signed HTTPS query parameters are supported; usernames/passwords in URLs are not supported.
- `Film.episodes` stays compatible with existing numeric count and is raised to cover configured numbered episodes when necessary. The UI lists only configured playable episodes and lines if provided.
- `LicensedPlaybackResolver` uses only a chosen film + episode number + line id; it **never** derives or guesses a stream URL. The optional separately entered test-media URL remains an independent playback test, not the movie's real stream.
- Media playback and metadata rights are the user's responsibility. Do not publish stream tokens in issues, PRs or screenshots.

## Viewing position
- `PlaybackHistory` saves current position/duration per **catalog URL scope + film ID + episode + line** in Android app-private SharedPreferences; the storage key is SHA-256 hashed. It does **not** save URLs, bearer tokens or request headers.
- Positions are recorded while playing, on background and on exit. Items finished near the end are cleared. Very short/invalid durations are ignored.
- From the detail screen, opening the same episode/line resumes; **从头播放** removes the stored offset.
- The `继续观看` shelf prioritizes films with actual saved positions. On a fresh install the shelf still presents demo suggestions but without fabricated progress percentages.
- History is local to a single Android installation. No accounts or multi-device sync yet.

## Testing: don't over-claim public network compatibility
The CI runner generates an original six-second FFmpeg test-pattern MP4 and HLS .m3u8/.ts assets. Android instrumentation hosts them over a **temporary self-signed TLS localhost** test server. A temporary certificate trust override is installed *only* in the test process, and the previous TLS configuration is restored in `finally`; **production keeps normal certificate checks**.

The Android TV emulator test verifies decoded video frames and native Media3 transport via two real loopback HTTPS endpoints, playlist + transport segments for HLS, playback pause/Back release, episode JSON validation, history isolation, and all original M1 and M2.1 regressions.

This is **real HTTPS socket transmission and Media3 rendering on the emulator**, but it is **not** a third-party CDN, DRM, remote internet or TCL T7L Ultra hardware validation. Live provider video, real subtitles, service-specific authentication, EPG, FongMi integration and external DRM remain pending.

## CI acceptance
- `HERRIN Android Debug` : Kotlin, JUnit, lint, actual Debug APK.
- `HERRIN Android TV Emulator` : OS API30/x86, native TV navigation and 13+ instrumentation cases, generated fixture HTTPS MP4/HLS, screenshots, logs, crash checks and genuine frame counters.
- Every new result belongs to the commit it tested. A previous green M2.1 run must not be used to claim that M2.2 passed.
