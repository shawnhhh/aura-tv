# HERRIN M2.3 — media controls, tracks and WebVTT

M2.3 is a **separate, stacked draft** based on verified M2.2 PR #5.
M1.6 home visuals and M2.2 episode/resume flows must remain unchanged.

## Implemented
- Media3 ExoPlayer native Android TV playback continues to handle HLS, DASH and progressive MP4.
- D-pad focusable Compose controls at bottom of player: ±10 seconds, Play/Pause, next audio track, next subtitle track or subtitle off.
- Real remote MEDIA_FAST_FORWARD and MEDIA_REWIND transport keys are supported, with bounded seeking (0..duration for known duration); native Media3 PlayerView controller is retained. Back still exits and releases the player.
- Tracks are enumerated from **Media3's actual supported currentTracks**, not fictional languages. Audio selection requires at least two supported tracks; subtitles disabled if absent. Selection uses TrackSelectionOverride and the actual media track groups.
- Optional HTTPS WebVTT sidecars per authorized playback line. Nothing is scraped or guessed. User-provided media and caption URLs must have appropriate rights and remain HTTPS.
- CI original synthetic fixture has video + two audio tracks (eng and zho) and a WebVTT file. Offline Android TV tests use a local synthetic clip, while separate loopback HTTPS HLS testing checks a real TLS subtitle fetch/cue decode path.

## Optional per-line catalog schema

```json
{
  "id": "official",
  "title": "正版官方线路",
  "url": "https://licensed.example.org/series/1/master.m3u8",
  "subtitles": [
    {
      "language": "zh-CN",
      "title": "简体中文",
      "url": "https://licensed.example.org/captions/zh-CN.vtt"
    },
    {
      "language": "en",
      "title": "English",
      "url": "https://licensed.example.org/captions/en.vtt"
    }
  ]
}
```

`subtitles` is optional; each line may carry up to 6 unique BCP-47-like language tags. Subtitles must be explicitly configured, legal-to-use **HTTPS WebVTT**. Unsupported caption types and arbitrary remote scripts are out of scope. Unsigned/HTTP/video-site extractor URLs are not provided.

## Verification gates
- Android Debug: compilation, static source check, JUnit parser/authorization validation, lint, installable APK.
- Android TV API30 emulator: all M1, M1.6, M2.1, M2.2 tests must continue passing; new tests validate actual dual audio and caption tracks, proper selection, physical remote media seek, D-pad CENTER on Compose player controls and real HTTPS WebVTT cue decoding in a TLS fixture.
- Save genuine screenshot, logcat and JUnit artifacts on failure and success. Do not loosen assertions to force green.
- Demo content and synthetic playback are **not proof** of compatibility with arbitrary real CDNs, provider-specific headers, DRM, all codec/audio combinations or TCL T7L Ultra physical TV.

## Still pending
- Physical TCL remote + IME / actual HDMI output QA.
- Real approved licensed source/CDN tests; protected-provider integrations.
- Accessibility, subtitle style preferences, audio output device selection, playback speed, 4K HDR/DRM and audio passthrough.
- FongMi/TV legal and architectural review; no GPL code imported into this PR.

Keep PRs #2–#6 unmerged until reviewed. Root GitHub Pages files stay unchanged.
