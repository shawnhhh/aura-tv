# M3.9 — Authorized live playback acceptance

This stage adds a privacy-safe **查看实播状态** button to the real Media3
player, and expands end-to-end verification. HERRIN still does not ship any
public IPTV source, scraper, account credentials or unlicensed content.

## Playback verification panel

From any authorized video or live player, navigate to **查看实播状态** and press
OK. HERRIN updates the panel once per second while it is visible:

- Media3 state: preparing, buffering, ready without frames, **video frames
  actually output**, ended, or error.
- Video resolution reported by the decoded stream (not the TV output mode).
- Decoder output frame and dropped-buffer counts, plus discovered audio and
  subtitle tracks.
- No URLs, tokens, authorization headers, source account IDs or raw EPG data.

This is decoder evidence. The panel **does not measure the TCL panel's 120 Hz
refresh, UI FPS or perceived motion smoothness**, and a positive audio-track
count alone does not prove the TV speakers output sound.

## Automated offline / private-TLS acceptance

The Android TV API30 emulator uses only original FFmpeg synthetic test media
bundled under `app/src/androidTest/assets` and a temporary self-signed TLS
MockWebServer trusted only by the instrumented process:

1. Existing regression: HTTPS MP4 and HLS video decoders initialize and
   output real frames; HLS reads and decodes HTTPS WebVTT cues; remote Back
   releases the player.
2. New integration: load a user-style HTTPS M3U with two local source links,
   fetch a matching HTTPS XMLTV programme, pair it by `tvg-id`, decode the
   first source, switch the real player to another source, assert the old
   decoder is released and the new HLS stream outputs frames, then Back
   releases the new decoder.
3. New recovery: the test server initially returns HTTP 503; assert a real
   player failure and visible retry button, then enable the fixture and use
   actual D-pad Center on **重试播放**. The player must fetch again and output
   frames, then release on Back.

The test URL never points at an external streaming provider, and the test
trust factory is restored after each test. No fixture URLs or certificates
are accepted in production settings without normal HTTPS validation.

## TCL T7L Ultra physical acceptance checklist

Use only a stream you own or have permission to access. The following requires
the owner to perform it on the actual 120 Hz TCL television:

1. Back up needed local settings and install the matching-signed debug APK.
2. Enter your HTTPS M3U (and optional XMLTV) under Settings. Refresh Live;
   verify actual channel names, `tvg-id` programme preview and filtering.
3. Play a live HLS/MP4 channel. After 5 seconds open **查看实播状态**; verify
   `视频已出帧`, nonzero decoder output, expected resolution and no errors.
4. Listen for audio and check sound/picture sync; manually cycle available
   audio and subtitle tracks. Confirm subtitles are visibly correct.
5. Back to channel list, switch to another channel and confirm the first
   stream no longer plays; switch back and check recovery.
6. Disconnect and reconnect the network for an authorized test stream;
   check the error message, **重试播放**, eventual frame recovery and Back.
7. Check long D-pad repeats, focus highlights, splash and tab movement on
   TCL's 120 Hz output mode. Report actual display refresh separately from
   decoded stream FPS and subjective UI smoothness.

Record pass/fail for each item and a privacy-redacted screenshot of the
verification panel. Real CDN, DRM, token-expiration, network quality, Dolby
Vision/Atmos and panel frame pacing cannot be certified by a local emulator.

Build: `0.3.9-alpha` (versionCode 9). Keep a stacked Draft PR, do not merge
without owner review and a licensing decision for any future FongMi adapter.
