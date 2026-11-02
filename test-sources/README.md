# Herrin Android TV · Public playback test sources

This directory contains **static test fixtures** designed for the user's TV testing only. There are no unauthorized movie portals, premium subscriptions, API tokens, or Spider JARs.

## Paste into Herrin Settings → 影视源 (TVBox single warehouse)

`https://cdn.jsdelivr.net/gh/shawnhhh/aura-tv@herrin-test-20261102/test-sources/tvbox-open-test.json`

The single warehouse lists a static TVBox-compatible CMS hosted by a public CDN and a playlist of test videos. CMS catalog API query parameters may be ignored because the CMS payload is an intentionally fixed test list. A successful static test **does not verify arbitrary real TVBox sources**.

## Alternate Herrin version-1 catalog (高级设置 → HTTPS 目录管理)

`https://cdn.jsdelivr.net/gh/shawnhhh/aura-tv@herrin-test-20261102/test-sources/herrin-test-catalog.json`

## M3U test playlist (高级设置 → 授权 IPTV 列表)

`https://cdn.jsdelivr.net/gh/shawnhhh/aura-tv@herrin-test-20261102/test-sources/test-streams.m3u`

**This playlist contains on-demand technical streams, not actual TV broadcasts.** It is for testing Android TV live-player transport only.

## Provenance

- Mozilla MDN sample flower MP4 (CC0): https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4
- Apple official HLS examples: https://developer.apple.com/streaming/examples/
- Apple AVC TS HLS: https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8
- Apple HEVC HLS: https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8
- Apple fMP4 HLS: https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8

CDN availability and TV networking are external dependencies. No claim of 4K HDR/Dolby output is made by these example sources. 404 sample is deliberately missing.

Source-only branch separate from PR #80 and from the Android APK; no production application code is modified by these test files.
