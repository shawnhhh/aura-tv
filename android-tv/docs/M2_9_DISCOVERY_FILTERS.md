# M2.9 — TV-friendly catalog search filters

- Search stays fully on device, against the currently selected offline demo or
  authorized catalog. Queries are trimmed and case-insensitive in title and subtitle.
- Four D-pad focusable category filters: All, Movies, Series, Anime.
- Optional newest-first ordering with stable alphabetical and ID tie-breaking,
  while default catalog order remains unchanged.
- Filter and order survive temporary detail/playback navigation with Compose
  saveable state and have clear focused-versus-selected visual states.
- Live TV channels are not included in film-search results; their provider
  selection is explicit and separate.
- JVM tests cover filtering, case-folding, stable source order and sort;
  emulator tests exercise native remote chips and restore.
- No remote search endpoint, telemetry or personal data collection.
