# M3.2 — Local IPTV channel favorites

- Every authorized M3U channel has a separate D-pad focusable star action.
- Favorites are stored only on the TV and scoped to the exact configured
  playlist URL. Neither the playlist nor per-channel stream URLs are saved
  in the favorites data: only SHA-256 identifier digests.
- A simple `只看收藏` filter keeps the original live-list ordering.
- Removing the last favorite shows an empty state with a way back to all
  channels; the underlying source list and user-supplied URL remain intact.
- Persistent storage and scope isolation are verified by an Android
  instrumentation test; no account or external syncing service is involved.

This does not add an IPTV channel provider or share/discover source URLs.
