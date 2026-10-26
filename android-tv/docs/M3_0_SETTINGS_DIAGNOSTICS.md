# M3.0 — TV display diagnostics and bounded maintenance

- Settings can read **actual Android display output mode** and the optional
  requested UI refresh preference, clearly distinguishing these figures from
  rendered FPS. Never claim TCL 120fps without physical measurements.
- A diagnostic clipboard summary reports resolution/Hz, chosen refresh hint,
  number of configured catalogs and presence of a live-list entry. It does
  not contain URLs, authorization headers, history, or favorite titles.
- A separate two-click (15s timeout) poster-cache cleanup touches only files
  inside HERRIN's `authorized-posters` cache directory. No saved sources,
  preferences, video downloads, favorites or playback offsets are deleted.
- New JVM safety test for redacted diagnostics; Android TV UI smoke checks the
  public device-state text. Full player/catalog regression required.

Physical TCL T7L Ultra refresh-mode and frame-time measurement remain a
separate acceptance step. See `TV_UX_120HZ_ACCEPTANCE.md`.
