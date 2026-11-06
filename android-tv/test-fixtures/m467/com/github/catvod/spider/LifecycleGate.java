// Original HERRIN deterministic test fixture, GPL-3.0-only. No provider code.
package com.github.catvod.spider;

import android.content.Context;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class LifecycleGate {
    @SuppressWarnings("unchecked")
    static Map<String, Object> state(Context context) {
        return (Map<String, Object>) context.getSystemService("herrin.fixture.lifecycle");
    }

    static void count(Map<String, Object> state, String key) {
        if (state != null) ((AtomicInteger) state.get(key)).incrementAndGet();
    }

    static void pause(Map<String, Object> state, String stage, Object value) {
        if (state == null || !state.containsKey(stage + ".entered")) return;
        CountDownLatch proceed = (CountDownLatch) state.get(stage + ".proceed");
        state.put(stage + ".instance", value);
        ((CountDownLatch) state.get(stage + ".entered")).countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean interrupted = false;
        try {
            // Intentionally emulate a plugin that ignores cancellation until
            // the explicit test gate opens. No sleeps or scheduler races.
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new IllegalStateException("Lifecycle fixture gate expired");
                try {
                    if (!proceed.await(left, TimeUnit.NANOSECONDS))
                        throw new IllegalStateException("Lifecycle fixture gate expired");
                    return;
                } catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
