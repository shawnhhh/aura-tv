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
        boolean retryRejected = false;
        try { failed.install(() -> CompletableFuture.completedFuture(null), () -> closeCounter(closes)); }
        catch (IllegalStateException expected) { retryRejected = true; }
        failed.close();
        require(retryRejected, "Failed transition silently retried");

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

        // Android's Looper/Handler executor may reject a dispatch during
        // shutdown. A rejected initial dispatch or completion dispatch cannot
        // strand a partially retired process or create a second engine.
        var firstDispatchRejected = new KernelCutover(task -> {
            throw new java.util.concurrent.RejectedExecutionException("handler quit");
        });
        var firstRejected = firstDispatchRejected.install(
            () -> { throw new AssertionError("Retirement ran after executor rejection"); },
            () -> { throw new AssertionError("Original kernel allocated after executor rejection"); })
            .toCompletableFuture();
        require(firstRejected.isCompletedExceptionally()
                && firstDispatchRejected.status() == KernelCutover.Status.FAILED,
            "Rejected initial dispatch must terminally fail the cutover");
        firstDispatchRejected.close();

        AtomicInteger dispatches = new AtomicInteger(), rejectedAllocations = new AtomicInteger();
        var secondDispatchRejected = new KernelCutover(task -> {
            if (dispatches.incrementAndGet() == 2)
                throw new java.util.concurrent.RejectedExecutionException("handler quit after retirement");
            task.run();
        });
        var delayedRetirement = new CompletableFuture<Void>();
        var secondRejected = secondDispatchRejected.install(
            () -> delayedRetirement,
            () -> { rejectedAllocations.incrementAndGet(); return closeCounter(closes); })
            .toCompletableFuture();
        require(!secondRejected.isDone(), "Retirement unexpectedly completed early");
        delayedRetirement.complete(null);
        require(secondRejected.isCompletedExceptionally()
                && secondDispatchRejected.status() == KernelCutover.Status.FAILED
                && rejectedAllocations.get() == 0,
            "Rejected completion dispatch must never allocate the new kernel");
        secondDispatchRejected.close();

        var missingLegacyClass = new KernelCutover(Runnable::run);
        var thrownInRetirement = missingLegacyClass.install(
            () -> { throw new NoClassDefFoundError("missing legacy retirement dependency"); },
            () -> { throw new AssertionError("Allocated despite retirement linkage failure"); })
            .toCompletableFuture();
        require(thrownInRetirement.isCompletedExceptionally()
                && missingLegacyClass.status() == KernelCutover.Status.FAILED,
            "Retirement LinkageError must also fail closed");
        missingLegacyClass.close();

        var cancellation = new KernelCutover(Runnable::run);
        var pendingRetirement = new CompletableFuture<Void>();
        var cancelledAllocation = new AtomicInteger();
        var pendingInstall = cancellation.install(
            () -> pendingRetirement,
            () -> { cancelledAllocation.incrementAndGet(); return closeCounter(closes); })
            .toCompletableFuture();
        require(pendingInstall.cancel(false), "Pending install did not cancel");
        pendingRetirement.complete(null);
        require(cancellation.status() == KernelCutover.Status.CLOSED && cancelledAllocation.get() == 0,
            "Cancelled cutover revived its engine after legacy retirement");

        new Kernel.WarehouseAccess(URI.create("http://example.com/tv"), true, true, List.of("chosen"));
        try { new Kernel.WarehouseAccess(URI.create("http://example.com/tv"), false, true, List.of("chosen"));
            throw new AssertionError("Implicit HTTP authorization");
        } catch (IllegalArgumentException expected) { }
        try { new Kernel.WarehouseAccess(URI.create("https://example.com/tv"), false, false, List.of("chosen"));
            throw new AssertionError("Implicit code execution authorization");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: exclusivity, scheduler rejection, lifecycle races, terminal failures and explicit grants; no playback tested");
    }
}
