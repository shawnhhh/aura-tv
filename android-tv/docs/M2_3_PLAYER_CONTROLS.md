# HERRIN M2.3 — Native TV transport and authorized subtitles

This stacked draft PR is based on M2.2 PR #5. It **does not alter the approved M1.6 homepage** and must not be merged until CI and actual Android TV screenshot evidence pass.

## Features actually implemented
- Native focusable player controls: 播放/暂停, -10s, +10s, audio track and subtitle track selectors. PlayerView's built-in controls remain available.
- MEDIA_FAST_FORWARD / MEDIA_REWIND remote keys invoke the same transport seek operations as visible buttons. Existing MEDIA_PLAY / PAUSE and BACK behavior is kept.
- Audio and subtitle selections are sourced from **Media3's actual supported track groups**. No fake choices are shown. The audio selector is disabled for one or fewer audio tracks; subtitle selector is disabled when there are no text tracks.
- Authorized catalog JSON v1 optional `subtitles` per episode/line; each track specifies language, title and **HTTPS WebVTT** URL. Media3 is configured with each actual `MediaItem.SubtitleConfiguration` and uses normal production TLS checks.
- Catalogue/film/episode line binding, watch-progress isolation and two-scroll-row Apple TV-inspired approved UI are inherited from M2.2.

## Optional line schema
```json
{
  "id": "official",
  "title": "有版权的播放线路",
  "url": "https://media.example.org/ep1/index.m3u8",
  "subtitles": [
    {
      "language": "zh-CN",
      "title": "简体中文",
      "url": "https://media.example.org/ep1/zh.vtt"
    },
    {
      "language": "en",
      "title": "English",
      "url": "https://media.example.org/ep1/en.vtt"
    }
  ]
}
```
Max six captions per line, unique language identifiers, HTTPS only; HTTP, URL credentials and duplicate language tracks rejected. These example URLs are **not** real streaming services.

## Real emulator verification gate
GitHub Actions generates **original synthetic 6s H.264 test video with two different AAC audio tracks** and `synthetic.vtt` (only test assets, not distributed in release APK). Android instrumentation must:
1. Start real Media3 player, initialize decoder and render frames.
2. Enumerate at least two real audio tracks and verify the selected Media3 track changes.
3. Enumerate real WebVTT subtitle track(s), enable and disable an actual track.
4. Operate remote MEDIA_PAUSE, MEDIA_FAST_FORWARD, MEDIA_REWIND and BACK keys, verify changed position/paused state and player release.
5. Preserve original M2.2 17/17 tests including HTTPS loopback MP4/HLS decode, M1.6 two rails, search IME handoff, favorites, watch history, and settings.
6. Save JUnit XML, logcat, screenshot and APK artifact. Prefer debugging actual failures over weakening tests.

## Explicit boundaries
The synthetic file and sidecar are provided by GitHub test CI. No licensed remote supplier, public CDN, DRM, subtitles on commercial provider content, real TCL T7L Ultra or FongMi integration has been verified. These all require separate validation. Original-fiction images remain only demo assets. Root GitHub Pages and all earlier PRs remain unchanged.

## FongMi/TV status
Defer import of GPL-3.0 components to a distinct code/license review PR. Avoid silently combining GPL source into HERRIN before deciding redistribution obligations, third-party AAR provenance and user-facing licensing.
