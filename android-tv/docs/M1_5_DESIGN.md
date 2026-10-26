# HERRIN M1.5 — Cinematic TV UI (in review)

## Branch / status
This branch layers visual-only work on top of the **M1 baseline** `88aad4333a285e03919780c62384fd3b7e1067f0`.
It is intentionally **not merged**. It must pass M1's existing full Android TV emulator suite before advancing.

## What changed
- Four new **original, algorithmically composed, bundled, offline PNG images** (1280×720) replace the repeated minimalist sample vectors: `cinema_orbit.png`, `cinema_tide.png`, `cinema_dawn.png`, `cinema_night.png`.
- Each image depicts a distinct cinematic-inspired fictional setting (planetary landscape, ocean/lighthouse, mountains at sunrise, distant city at night). These are synthetic illustrations **not** studio movie stills or claimed movie posters. No third-party image license is required.
- Full-screen images keep center-right highlights with graduated left and bottom readability scrims.
- Compact H + HERRIN brand at top left, centered dark-glass rounded category navigation, rounded controls.
- Stronger 48sp hero movie title, restrained English metadata and title/kicker, larger artwork-first 238×146dp posters with bottom text scrim, visible D-pad focus border and scaling.
- All original demo film IDs, catalog queries, favorites, back behavior and `RemoteTrace` diagnostics are retained. The Poster continues using exactly one Foundation `clickable` activation path; `onPreviewKeyEvent` is **non-consuming**.
- App version now `0.1.5-cinematic-demo`; playback remains the explicitly labeled M1 placeholder.

## Scope and legal boundary
All programmatically created landscape art is original and can be modified or replaced without FongMi/TV code or licensed movie artwork.
**Do not** copy copyrighted movie posters or studio key art into the APK without permission.
These images are a branded art direction and layout test; they are not yet movie photography. Film-provider artwork ingestion, caching and license tracking belong to future catalog integration. FongMi/TV engine integration and real streams remain M2.

## Verification gate
- GitHub Actions `HERRIN Android Debug`: Gradle assembleDebug, JUnit, lint.
- GitHub Actions `HERRIN Android TV Emulator`: API30/x86, launch, screenshot, remote smoke and 8 instrumentation tests.
- Inspect the **actual** emulator home screenshot `build/emulator-evidence/home.png` and detail screenshot(s), check legibility, cinema scene fill/crop, card sizes and focus.
- Do not call UI or TCL real-device validation complete if emulator CI fails or physical TV was not tested.
- Keep `feat/herrin-android-tv-m1` and `master` unchanged. Review this stacked PR separately.


## M1.6 visual-parity correction (requested by product owner)
The M1.5 artwork was too flat and its single poster rail did not reproduce the previously approved Apple TV+ inspired full-screen movie UI. That visual direction has been rejected.

New demo visuals: six locally packaged **fictional, AI-generated photo-style stills** cropped from AI-generated design studies. They include no baked-in application interface, film title, poster logos or navigation. They are **visual placeholders**, not licensed studio film art; provenance and commercial-use review are still required.

Native UI now features:
- Wide cinematic character-and-scenery hero replacing large flat planetary illustrations.
- Shared translucent top navigation, text-only inactive categories, white selected category.
- Two genuine focusable horizontally scrolling shelves: 继续观看 and 热门推荐.
- Progress treatment for the first shelf, more compact cards and reduced empty vertical space.
- Preserved functional catalog IDs, search, remote controls, IME handoff, favorites and M1 splash.

Visual QA gate: inspect actual Android TV emulator home PNG at 1920x1080, ensure both shelves and images are actually visible and not off-screen. A passing instrumentation suite alone is not a visual approval. If both shelves don't fit, adjust dimensions and retest. Physical TCL validation remains pending; actual playback integration remains a separate M2 task.
