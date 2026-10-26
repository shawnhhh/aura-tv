# M2.5 — Fluid top navigation focus motion

## Purpose

Follow-up to TCL T7L Ultra feedback that rapid remote navigation still feels rigid
despite the M2.4 button-scale spring. The visual design stays restrained and
Apple TV+-inspired; the existing top tabs and other modules remain unchanged.

## Implementation

- Six native top tabs stay independently focusable by Android TV D-pad.
- Native focus state, white button fill, action, and focus restoration update immediately.
- A separate, non-interactive 4dp-high Canvas glides beneath the focused top tab.
  Targets come from each real button's measured Compose coordinates and width,
  so indicator alignment does not depend on assumed equal-size labels or screen resolution.
- X position and width use interruptible spring animation (stiffness 580,
  damping 0.90); quick reverse input redirects the in-flight motion.
- The indicator fades out on leaving the navigation area. It never leaves a
  second fully white pill behind and never blocks a D-pad key.
- A reduced-motion user or Android animator scale 0 switches movement and
  visibility to snap semantics.
- Animation redraws a small Canvas only; no animated width measurement,
  hero image decoding, video frame interpolation, display-mode forcing, or
  network dependency is involved.

## Verification

- JVM: indicator geometry stays centered under measured buttons of different widths.
- Android TV emulator: target physically moves from Home to Movies and returns
  to the same coordinate on Left reversal; existing focus, search, settings,
  source-picker, playback and unique-white tests must remain green.
- TCL real-device 60/120Hz feel, frames and input timing remain **unverified**.
  Follow `TV_UX_120HZ_ACCEPTANCE.md` and inspect frame stats at the selected output mode.

## Exclusions

No FongMi/TV source-code copying, media catalogs, auto streaming or external
service integration. This feature does not change the root Pages prototype.
