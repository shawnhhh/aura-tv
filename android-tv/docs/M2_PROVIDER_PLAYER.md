# HERRIN M2 Alpha — Authorized provider catalog + Media3 playback

M2 builds on the **approved M1.6 cinematic native UI** (stacked PR #3). Keep PR #2 (M1) and PR #3 (M1.6) intact; do **not** merge automatically.

## What is implemented
- Optional user-configured **HTTPS** catalog endpoint, JSON v1 parser and metadata validation. Does **not** discover/scrape streaming sources.
- App-private cached JSON for the **same exact catalog URL**; network failure uses that last valid snapshot, or original fiction demo catalog when unavailable.
- Optional HTTPS poster image URLs, bounded image download and private on-device disk cache; local bundled HERRIN cinematic stills remain fallback.
- User-entered HTTPS **test-media URL** only. Media3 / ExoPlayer supports MP4/progressive, HLS `.m3u8`, and DASH `.mpd`, with Android TV PlayerView controls and player lifecycle release. This address is *not* a resolved movie episode and must be a source the user is authorized to play.
- Settings fields for both URLs, save, manual catalog sync and cache/offline status.
- No preconfigured video provider, copyrighted film metadata scrape, DRM bypass, FongMi code import, or pirated stream collection.

## JSON catalog contract
Serve UTF-8 JSON at a user-controlled HTTPS URL with a normal 200 response (no redirects). Max response 2,000,000 bytes; 1–100 entries:

```json
{
  "version": 1,
  "items": [
    {
      "id": "demo-feature-001",
      "title": "授权测试影片",
      "subtitle": "AUTHORIZED TEST FILM",
      "category": "电影",
      "year": "2026",
      "synopsis": "本内容仅用于你有权使用的测试目录。",
      "episodes": 1,
      "artwork_url": "https://your-domain.example/poster.jpg"
    }
  ]
}
```
Allowed category values are `电影`, `剧集`, `动漫`. IDs must be unique ASCII identifiers (`[a-zA-Z0-9._-]`), 1–64 chars. `title` required, ≤80 chars. Media URLs are **not taken from this JSON**; playback requires a separately entered explicit test stream URL.

## First-time use
1. Open **设置** using TV D-pad.
2. Optionally enter a catalog JSON URL, or leave blank for HERRIN demo films.
3. Optionally enter an HTTPS media stream for which you own/have playback rights.
4. Choose **保存媒体配置**. The directory is fetched off the main thread and cached privately. Search and shelves use supplied metadata if valid.
5. Choose **▶ 播放演示** on any title to exercise the configured test stream. The screen clearly states the stream does not represent the selected film.

### Security and operational limits
- TLS HTTPS required; no HTTP cleartext or redirected URL; userinfo and fragments rejected; no embedded API credentials or passwords.
- No automatic DRM support, subtitles, EPG, user accounts, per-episode providers, tokens or cross-device sync yet.
- The poster cache is limited to images ≤4 MB with basic image dimension checks; still a demo-quality implementation, not a complete image CDN client.
- Any catalog image and stream must be legally usable. HERRIN's bundled fictional art is always available offline.
- API 30 emulator run verifies original M1 behavior and new settings surfaces. Actual licensed streaming playback is **not proven** until a rights-authorized test stream is supplied and separately checked; manufacturer TCL T7L Ultra remains untested.

## Verification commands
```sh
cd android-tv
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
GitHub Actions builds and runs the existing Android TV API30/x86 instrumentation and D-pad smoke tests. New JVM tests cover HTTPS URL security and JSON catalog validation. Do not interpret a green APK build as proof of a stream decoding test.

## Architecture next steps
- Complete per-film episode/line `PlaybackSource` adapter for a specifically licensed provider and negotiate API schema with the provider.
- Add playback error UI / subtitles / audio track selectors / content-resume and streaming performance telemetry.
- Evaluate GPL-3.0 FongMi/TV in a **separate** integration PR. Don't copy GPL files until complying with license and verifying availability of its bundled AAR dependencies.
