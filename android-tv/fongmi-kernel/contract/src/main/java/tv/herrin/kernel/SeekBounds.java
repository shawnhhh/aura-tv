/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel;

/** Bounded transport arithmetic, including unknown/live duration and repeated remote seeks. */
public final class SeekBounds {
    private SeekBounds() {}
    public static long offset(long position, long delta, long duration) {
        long start = Math.max(0, position);
        long target = delta > 0 && start > Long.MAX_VALUE - delta ? Long.MAX_VALUE : start + delta;
        return Math.min(Math.max(0, target), duration > 0 ? duration : Long.MAX_VALUE);
    }
}
