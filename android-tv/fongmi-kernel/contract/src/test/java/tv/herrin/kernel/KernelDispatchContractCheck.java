/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks request completion and cancellation without simulating media. */
public final class KernelDispatchContractCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var queued = new ArrayList<Runnable>();
        var entered = new AtomicInteger();
        var cancelled = KernelDispatch.submit(queued::add, () -> {
            entered.incrementAndGet(); return null;
        }, kernel -> CompletableFuture.completedFuture("unexpected"));
        cancelled.toCompletableFuture().cancel(false);
        queued.remove(0).run();
        require(entered.get() == 0, "Cancelled UI request entered kernel");

        var delegated = new CompletableFuture<String>();
        var result = KernelDispatch.submit(Runnable::run, () -> null, kernel -> delegated);
        result.toCompletableFuture().cancel(false);
        require(delegated.isCancelled(), "Cancellation did not retire delegated request");

        var rejected = KernelDispatch.submit(task -> { throw new RejectedExecutionException(); },
            () -> null, kernel -> CompletableFuture.completedFuture("unexpected"));
        require(rejected.toCompletableFuture().isCompletedExceptionally(), "Rejected dispatch hung");
        var missing = KernelDispatch.submit(Runnable::run, () -> null,
            kernel -> { throw new NoClassDefFoundError("original backend"); });
        require(missing.toCompletableFuture().isCompletedExceptionally(), "Missing original class hung");
        var missingOwner = KernelDispatch.submit(Runnable::run,
            () -> { throw new IllegalStateException("Not installed"); },
            kernel -> CompletableFuture.completedFuture("unexpected"));
        require(missingOwner.toCompletableFuture().isCompletedExceptionally(), "Missing owner hung");
        var pending = new CompletableFuture<String>();
        var success = KernelDispatch.submit(Runnable::run, () -> null, kernel -> pending);
        pending.complete("admitted");
        require("admitted".equals(success.toCompletableFuture().join()), "Completion lost");
        System.out.println("Kernel dispatch contract PASS (not media playback)");
    }
}
