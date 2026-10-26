# M2.6 — Catalog CRUD and local backup

## User operations

- Select up to 10 user-authorized HTTPS JSON catalog profiles as before.
- Rename a selected profile or edit its HTTPS address atomically. A changed
  address uses its own existing URL-based favorites/history scope; the previous
  scope's data is retained for later recovery, not silently merged.
- Delete the current profile with an explicit two-click confirmation (15-second expiry), falling
  back to offline demo and preserving unrelated profiles, favorites and history.
- Copy a JSON backup of named catalog profiles to the system clipboard only
  on user request. URLs may contain private tokens; never publish the backup.
- Paste and import an existing HERRIN JSON profile array. New URLs are merged;
  existing URLs/selection are never overwritten. An invalid entry or exceeding
  the 10-profile limit rejects the **entire** batch.
- No automatic import over LAN, third-party scraping or bundled sources.

## Acceptance

- Invalid/duplicate edits leave all profiles and active URL unchanged.
- Active URL editing is reflected in the existing remote repository loader.
- Deleting the active source activates offline mode without deleting favorites.
- Import handles IDs from other devices safely by generating new local IDs.
- Reimporting the same JSON only skips duplicates.
- Emulator must keep the existing settings, playback, source isolation and
  D-pad smoke regressions green, including the explicit backup UI.
- Physical TCL clipboard/IME interaction still requires TV validation.
