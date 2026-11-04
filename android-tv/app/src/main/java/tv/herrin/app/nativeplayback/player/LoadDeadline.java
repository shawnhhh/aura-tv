/*
 * FongMi/TV PlayerManager deadline contract, pinned 305dff233cde512e2c83f71529b7c3ecfd61fefc.
 * Original author: FongMi; GPL-3.0-only; android-tv/LICENSE-GPL-3.0.md.
 * 2026-11-04 Herrin modification: generation-checked host deadlines prevent stale
 * Android callbacks from stopping a replacement source or a background session.
 */
package tv.herrin.app.nativeplayback.player;

/** Single-threaded monotonic deadline state used by the real player manager. */
public final class LoadDeadline {
    private long generation;
    private long expiresAt;
    private boolean active;

    public long begin(long nowMs, long timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Positive timeout required");
        generation++;
        expiresAt = nowMs > Long.MAX_VALUE - timeoutMs ? Long.MAX_VALUE : nowMs + timeoutMs;
        active = true;
        return generation;
    }

    public void cancel() {
        active = false;
        generation++;
    }

    public boolean isExpired(long attempt, long nowMs) {
        return active && generation == attempt && nowMs >= expiresAt;
    }
}
