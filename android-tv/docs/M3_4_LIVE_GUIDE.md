# M3.4 — Live now/next guide and Android XML compatibility

- The optional user-provided HTTPS XMLTV guide now provides a local-only current and next programme preview for the remote-focused channel. A slim progress bar tracks the current programme once per minute; no network request occurs during D-pad navigation.
- Out-of-order XMLTV records are handled chronologically. At the stop boundary the old programme is no longer active; missing programmes are never fabricated. Channels continue to work without any guide.
- Android API 30's Harmony XML DOM parser does not support every desktop JAXP feature URI (`FEATURE_SECURE_PROCESSING` caused M3.3's 35/36 emulator result). This stage applies supported hardening flags on a best-effort basis, **rejects entity declarations and all unrecognized DOCTYPE declarations before parsing**, strips only the narrow XMLTV `DOCTYPE tv SYSTEM ...` syntax, and installs an entity resolver that always throws on external resolution. The size/record limits remain.
- Unit coverage exercises chronological next-programme selection, progress, future-only schedules, and missing channels; the API 30 instrumentation regression covers accepted harmless XMLTV DTDs and rejected entity/DOCTYPE payloads.
- The Settings version description is updated to reflect already-implemented live TV and the still-pending FongMi licensing decision.

No IPTV provider, copyrighted content, discovery endpoint, credentials or signing keys are bundled. This stage remains a stacked draft; cloud Debug build and full Android TV emulator verification are required before claiming test success. TCL T7L Ultra input latency, 120Hz frame pacing, and real authorized-guide behavior still require owner device tests.
