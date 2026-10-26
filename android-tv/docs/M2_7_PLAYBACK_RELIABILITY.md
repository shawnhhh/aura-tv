# M2.7 — Playback recovery and status

This milestone only touches legitimate user-supplied HTTPS streams through
Media3. It does not include any built-in stream provider or downloader.

## Changes

- Display a localized, private playback failure description instead of raw
  Media3 error codes; never include full media URL or request headers in UI.
- Provide a focusable `重试播放` button after a failed load. Retry is always
  user-initiated, preserving the last known playback position where possible.
- Surface buffering and completed-playback states.
- Use bounded 12-second connect/read HTTP timeouts instead of allowing a slow
  authorized source to leave the UI unresponsive.
- Recreate ExoPlayer when a stream's request headers change, even if the URL
  stays the same; previous player is released by the existing lifecycle effect.
- Continue respecting resume, subtitles, audio selection, playback transport,
  Back release, source isolation and local-only playback history.

## Tests

- JVM message mapping for timeouts, decoder errors, live-window changes,
  unknown failures and omission of diagnostic strings.
- Full existing Android TV TLS MP4/HLS decoded-frame tests, Media3 track
  tests, remote Back/seek, persistence and screen regression suite.
- TCL physical buffering, remote retry ergonomics and real-provider errors
  remain hardware/source acceptance checks.
