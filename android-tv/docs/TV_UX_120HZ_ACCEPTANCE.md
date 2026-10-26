# HERRIN — TCL TV focus, splash and high-refresh acceptance

## Requested TV fixes (2026-10-26)

- Launcher title is `Herrin`; the in-app brand / splash remains `HERRIN`.
- Splash stays centered, increases lettering from 56sp to up to ~86sp, progressively collapses to H, has no visible skip label and can still be skipped with remote OK/Enter/Back.
- Avoid decoding/drawing the home hero behind the opaque splash. Splash letters use one graphics-layer translation/opacity timeline, not repeated per-letter width relayouts.
- Episode/line/navigation buttons have a **single white remote focus**. A saved/selected value is blue-grey so the old selection cannot resemble a second focus pill. D-pad button focus updates immediately without a 160ms scale animation.
- Native Android UI requests a high refresh rate **only if the Android display API exposes a 90–120Hz mode at the current output resolution**. No resolution downgrade or hard-coded frame loop. The request is advisory; the firmware decides the actual rate.

## Cloud checks

1. `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`
2. Build and run Android TV API 30 emulator instrumentation: splash collapse and invisible skip control; detail episode and line selection vs focus; existing D-pad, catalog, playback regressions.
3. Inspect `splash-HERRIN`, `splash-H`, and `detail-unique-focus` screenshots; the selected + focused buttons must not both be white.

## TCL T7L Ultra real-device checks

1. After installing the new APK, confirm the launcher label `Herrin` and icon H, then force-stop and cold-launch. Splash should show large `HERRIN` ending at centered `H` in ~2.9 seconds, without a bottom prompt. OK and Back should still skip.
2. On a detail page, navigate episode chips and line chips rapidly; **only the button under focus is white**. The chosen episode/line remains tinted until OK commits another selection. Verify no missing or duplicated D-pad steps.
3. In TV display settings, select the actual high-refresh output mode if available. Read `adb logcat -d -s HerrinDisplay:I` to see the modes exposed to the app and its refresh hint. This log is diagnostic; it is **not proof of rendered FPS**.
4. Capture UI frame stats while repeatedly traversing chips:

   ```sh
   adb shell dumpsys gfxinfo tv.herrin.app reset
   # Perform at least 100 D-pad navigation events on the TV.
   adb shell dumpsys gfxinfo tv.herrin.app framestats
   adb shell dumpsys display
   ```

   Compare frame deadlines against the **actual display mode**: 8.33ms at 120Hz, 16.67ms at 60Hz. Record missed-frame proportion and p95 frame time. A 120Hz-capable panel alone does not prove that Android output or the app is running at 120fps.
5. Test playback separately with legal 24, 25, 30 and 60fps samples; source video FPS is not the same as UI refresh. Media3 should manage the video surface's cadence where the firmware supports it. Avoid disruptive automatic whole-display switches during short UI interactions.

Cloud emulator results cannot establish actual TCL/T7L Ultra 120Hz frame pacing; TV measurements remain a release gate.
