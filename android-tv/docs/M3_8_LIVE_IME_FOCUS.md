# M3.8 — Live-search TV keyboard handoff

The M3.7 Live tab's optional local search uses Android TV's real native IME.
M3.8 gives the search field an explicit **Done** action and ensures the
next remote key is delivered to HERRIN instead of an IME's stale connection.

- On **Done**, hide the system keyboard and move real Compose focus to the
  first currently filtered channel (`live-0`). If the search has no matches,
  focus the nearby **清空关键词** button instead of a missing lazy-list item.
- On **Back** while the Live search field remains focused, return to the
  filtered rail without unexpectedly leaving the Live tab. Normal Back
  behavior outside the search editor remains unchanged.
- Every group, favorites or keyword change resets the channel rail's
  horizontal scroll to the first result; finishing input also scrolls before
  requesting focus. One frame of attachment plus a bounded retry handles
  Compose LazyRow content becoming focusable after filtering.
- The focus transition updates the global top-tab focus ID; it must not leave
  a ghost white underline or duplicate selected pill on the header.
- The Android TV UI test uses a temporary synthetic HTTPS M3U and asserts
  **search -> IME Done -> first matching channel focused -> actual OS D-pad
  Right -> favorite button -> OS D-pad Center**. No network request may be
  caused by moving or typing.

Build: `0.3.8-alpha` / code 8. The TCL T7L Ultra keyboard vendor, remote
repeat behavior and actual 120 Hz animation fluidity must still be verified
on physical hardware; an API30 emulator is necessary but not sufficient.
This feature contains no third-party IPTV provider or FongMi GPL source.
