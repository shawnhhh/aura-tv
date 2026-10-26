# M3.3 — Optional XMLTV live programme guide

The user may provide a separate HTTPS XMLTV URL in TV Settings. This is
optional: channels from their M3U playlist work without any EPG data.

## Format and behavior

- Match `#EXTINF tvg-id="..."` from the M3U entry to XMLTV
  `<programme channel="...">`; fall back to the channel's display name
  when no tvg-id was provided.
- Parse timezone-aware XMLTV start/stop fields. Only show the current
  programme title alongside a channel. Refresh current-programme labels on
  the live tab once per minute without network requests.
- A guide snapshot is bounded to 2MB and 800 programme records.
- HTTPS loader enforces response MIME, no redirects and 9-second timeouts.
- XML parser disables DTD processing and external entities. A simple
  standard XMLTV external DTD declaration is stripped without fetching it;
  entity declarations or unsafe doctypes are rejected.
- Loading failures leave the live channel rail and player usable.
- JVM tests use original fictional programme data and explicit +0800 timezone;
  Android TV smoke validates optional settings and no-guide state.

No provider subscription, EPG crawling, local-network discovery, DRM bypass
or copyrighted guide is distributed with HERRIN. Commercial IPTV services
or long multi-day programme archives may exceed the current bounded parser;
this is a deliberately limited first XMLTV adapter.
