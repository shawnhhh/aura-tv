/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM boundary checks, no media fixture or playback-success simulation. */
public final class KernelCutoverContractCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Kernel closeCounter(AtomicInteger closes) {
        return (Kernel) Proxy.newProxyInstance(Kernel.class.getClassLoader(), new Class<?>[]{Kernel.class},
            (proxy, method, args) -> {
                if (method.getName().equals("close")) { closes.incrementAndGet(); return null; }
                throw new AssertionError("No media operation should run in lifecycle checks");
            });
    }
    public static void main(String[] args) {
        AtomicInteger allocations = new AtomicInteger(), closes = new AtomicInteger();
        var retirement = new CompletableFuture<Void>();
        var cutover = new KernelCutover(Runnable::run);
        var result = cutover.install(() -> retirement, () -> {
            allocations.incrementAndGet(); return closeCounter(closes);
        }).toCompletableFuture();
        require(allocations.get() == 0 && !result.isDone(), "New engine allocated before old retirement");
        retirement.complete(null);
        result.join();
        require(allocations.get() == 1, "Expected exactly one new owner");
        cutover.requireKernel();
        cutover.close(); cutover.close();
        require(closes.get() == 1, "Repeated close released another owner");

        var failed = new KernelCutover(Runnable::run);
        var failure = failed.install(() -> CompletableFuture.failedFuture(new IllegalStateException("old still alive")),
            () -> { throw new AssertionError("Allocated a competing engine after retirement failure"); });
        require(failure.toCompletableFuture().isCompletedExceptionally(), "Retirement failure was hidden");
        try { failed.install(() -> CompletableFuture.completedFuture(null), () -> closeCounter(closes));
            throw new AssertionError("Failed transition silently retried");
        } catch (IllegalStateException expected) { }
        failed.close();

        var closing = new KernelCutover(Runnable::run);
        var stillRetiring = new CompletableFuture<Void>();
        var pending = closing.install(() -> stillRetiring,
            () -> { throw new AssertionError("Allocated after closing"); }).toCompletableFuture();
        closing.close();
        require(pending.isCompletedExceptionally(), "Close left installation pending");
        stillRetiring.complete(null);

        var unavailable = new KernelCutover(Runnable::run);
        var linkage = unavailable.install(() -> CompletableFuture.completedFuture(null),
            () -> { throw new NoClassDefFoundError("missing original engine"); }).toCompletableFuture();
        require(linkage.isCompletedExceptionally() && unavailable.status() == KernelCutover.Status.FAILED,
            "Missing original dependency did not fail terminally");
        unavailable.close();

        new Kernel.WarehouseAccess(URI.create("http://example.com/tv"), true, true, List.of("chosen"));
        try { new Kernel.WarehouseAccess(URI.create("http://example.com/tv"), false, true, List.of("chosen"));
            throw new AssertionError("Implicit HTTP authorization");
        } catch (IllegalArgumentException expected) { }
        try { new Kernel.WarehouseAccess(URI.create("https://example.com/tv"), false, false, List.of("chosen"));
            throw new AssertionError("Implicit code execution authorization");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: lifecycle exclusivity, terminal failures, pending close and explicit grants; no playback tested");
    }
}
