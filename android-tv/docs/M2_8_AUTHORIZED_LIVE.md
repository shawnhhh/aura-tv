# M2.8 — Authorized live TV M3U foundation

## Scope

- Users may enter one **HTTPS IPTV M3U** list URL in Settings; no default
  provider, credentials, playlist, scraper or channel source is included.
- Only `#EXTM3U` + `#EXTINF` named channel entries are accepted, using
  absolute HTTPS playback links. Non-HTTPS URLs are ignored; HLS segment
  manifests (`#EXT-X-`) are rejected as *channel lists*.
- Channel name/group data is parsed with bounds; duplicates are removed.
  Limit is 250 channels and 512 KiB per playlist.
- HTTPS loading uses bounded 9-second connect/read timeouts, disables redirects
  and limits response body size and MIME types.
- The existing `直播` tab shows status and a D-pad navigable channel rail.
  Choosing a channel opens the existing Media3 player, and Back returns to
  the channel list. All network loading occurs on the IO dispatcher.
- Empty/invalid source gracefully shows how to configure one, with the
  previously approved offline movie UI still available.

## Testing

- JVM tests: HTTPS-only channels, quoted-comma groups, URL deduplication,
  malformed and HLS-manifest rejection, 250-item bound.
- Emulator: live-tab empty state, settings entry controls, existing remote
  navigation and player regression suite.
- No real-world IPTV service is tested or bundled; users need rights to their
  own channels. EPG, multiple channel-list profiles, favorite channels,
  DRM/conditional access and TCL 120Hz behavior remain future work.
