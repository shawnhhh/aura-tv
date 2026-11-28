# HERRIN — repository instructions for Codex

## Product and scope
- In-app brand and startup logo: **HERRIN** (all caps); Android TV launcher display label: **Herrin** (title case). App icon is a minimalist white **H** on charcoal/near-black. Design language is restrained **Apple TV+ inspired**, not a Netflix clone (no red N / Netflix marks).
- Target: **TCL T7L Ultra**, Android TV-compatible 16:9 landscape display and D-pad remote.
- The current site at the repository root (index.html, icon.svg, manifest.webmanifest, apple-touch-icon.png) is an approved interactive design reference. **Do not break, replace, or move the GitHub Pages site.** It is not the Android implementation.
- Develop a real native Android TV project under **android-tv/**. Avoid wrapping the site inside WebView.

## UX requirements
- Edge-to-edge 16:9 cinematic hero art as the dominant home-screen visual. Overlay modest left/bottom contrast gradients, movie text, play/details buttons.
- Dark translucent top pill navigation: Home, Movies, Series, Anime, Live, My; searchable from the toolbar.
- Horizontal landscape artwork rails with D-pad focus, appropriate selected-state scaling and highlight, smooth scrolling, category change, and Back behavior.
- Film detail surface with synopsis, metadata, actions, favorites and demo episodes/lines. For M1, demo content must be clearly labeled.
- Startup splash: centered **HERRIN** progressively collapses/fades letter by letter to **H**; then transition to home in approximately 2.5–3.5 seconds. Support skipping, app startup reliability, and reduced-motion preference; do not rerun on every navigation.
- No network image dependency for first render: bundle permitted/original sample hero and cards, or use deterministic generated graphic assets. Never use copyrighted film poster artwork without permission. A gradient-only hero **does not** meet the visual requirement.
- TV ergonomics: D-pad Up/Down/Left/Right/OK/Back, visible focus, focus restoration, safe overscan margins, smooth interactions, no pointer-only functionality.

## Android engineering
- Prefer Kotlin + modern native Android TV UI (Compose for TV / compatible Compose Material), Media3 later. Choose versions supported by the selected Gradle/AGP and Android SDK.
- Use a separate package/applicationId, e.g. `tv.herrin.app`; label it Herrin in the launcher; include Android TV leanback manifest launcher, banner, and proper app icons.
- Architect UI / data / player with explicit interfaces so a real catalog and playback source can replace the M1 demo data.
- M1 should be a functional Android TV shell, **not** a claim of connected video playback. Demo play UI is acceptable if clearly labeled.
- Examine https://github.com/FongMi/TV for later integration: its `app/src/main` is shared logic and `app/src/leanback` is TV UI; its build may require non-repository `lib-*.aar` files. Do not misrepresent its build as reproducible if files are missing. Decide whether to migrate the shell or create an integration adapter, and document the tradeoff.
- FongMi/TV is GPL-3.0. Do not silently paste GPL sources into an incompatible-license app or include unavailable/proprietary AARs. Document licensing obligations for any derivative distribution. Do not bundle unauthorized streams or scrapers.
- No production API keys, signing passwords, or keystore files in git.

## Deliverables and verification
- Android Studio-openable Gradle project in `android-tv/`, with a Gradle wrapper and README of setup/build/run steps.
- At least one build attempt (`./gradlew :app:assembleDebug` from `android-tv/`) with honest output; report exact blockers if build cannot be completed.
- Prefer GitHub Actions Android debug compile workflow and upload the debug APK as a workflow artifact if permissible; no signing secrets for debug builds.
- Document emulator / TCL remote D-pad test checklist and acceptance results; do not claim real-device validation if unavailable.
- Continue on the assigned feature branch and existing PR. Keep its Draft state when specified. Do not create a new PR, merge the default branch, or force-push.

## Development discipline
- Use this sequence for all HERRIN work: concentrated development, batches of related commits, consolidated tests, targeted fixes and verification, final full regression, then APK delivery. Use `[skip ci]` on intermediate development commits. Run the final verification on the actual delivery commit.
- Work serially. Do not use Superpowers or subagents. Keep developing independent items when a module completes or an external dependency is blocked.
- Start with the Git diff, relevant symbols, actual call paths and existing failure evidence. Avoid repeated repository-wide scans, large log reads, and historical document review. Reuse existing implementation and suitable licensed open-source components. Avoid redundant implementations, artificial commits and retries of known incompatible dependencies.
- Run full tests and builds after primary development. Diagnose a failing test, fix its cause and run its targeted verification before the final full regression. Keep effective assertions, acceptance criteria, security and provider authorization intact. Maintain the necessary quality and test coverage.
- Reuse the TV-tested APK from the final successful CI when it matches the delivery commit, and verify its source and checksum. Report code completion, executed automated checks and physical device results separately.
- Keep status records brief: issue ID, changes, commit, remaining blocker and next action. At a genuine resource, permission or environment limit, commit finished changes and leave a usable checkpoint.
- Prefer native Android UI. Keep root GitHub Pages assets intact.

## Playback and user-facing information

- Keep a single playback session architecture. Prefer compatible, maintainable components with clear provenance and licenses. Full FongMi migration is optional. Keep actual Spider resolution, provider authorization, account restrictions and DRM intact.
- Show only text needed to operate the app. Prefer a quiet loading indicator. Keep search, resolution, node and scan counts and diagnostic details in the background.
- Present the instructions needed for the actual provider and authorization method. Do not forward arbitrary plugin toasts. Keep detailed failures in safe logs. Never log or disclose tokens, cookies, QR authorization payloads or sensitive request parameters.
- Keep existing navigation and visual conventions. Avoid redundant controls, confirmations and technical explanations.
- On TV, D-pad navigation focuses a text field without opening the keyboard. OK enters editing. Done or Back closes the editor while retaining its value and focus. Back outside editing follows the current screen's navigation. Keep standard touch editing on the phone.
