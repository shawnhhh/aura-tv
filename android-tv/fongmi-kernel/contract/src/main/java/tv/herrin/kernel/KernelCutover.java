/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** One-way installation boundary. No old backend factory exists here. */
public final class KernelCutover implements AutoCloseable {
    public enum Status { NOT_INSTALLED, RETIRING_LEGACY, INSTALLED, FAILED, CLOSED }
    private final Executor lifecycle;
    private Status status = Status.NOT_INSTALLED;
    private Kernel kernel;
    private CompletableFuture<Void> installation;

    public KernelCutover(Executor lifecycle) { this.lifecycle = Objects.requireNonNull(lifecycle); }
    public synchronized Status status() { return status; }

    /** The host supplies completion only after all old sessions have retired.
     * New allocation occurs afterwards on the Android main-thread executor.
     * A failed transition is terminal: no silent old-backend restart. */
    public CompletionStage<Void> install(Supplier<CompletionStage<Void>> retireLegacy,
                                         Supplier<Kernel> originalKernelFactory) {
        Objects.requireNonNull(retireLegacy);
        Objects.requireNonNull(originalKernelFactory);
        CompletableFuture<Void> installed = new CompletableFuture<>();
        synchronized (this) {
            if (status != Status.NOT_INSTALLED)
                throw new IllegalStateException("Kernel cutover already attempted: " + status);
            status = Status.RETIRING_LEGACY;
            installation = installed;
        }
        installed.whenComplete((value, failure) -> {
            if (installed.isCancelled()) close();
        });
        // Both executor dispatches are failure boundaries: a rejected Android
        // main-thread dispatch must fail the installation instead of leaving the
        // process forever in RETIRING_LEGACY with a pending Future.
        try {
            lifecycle.execute(() -> {
                try {
                    synchronized (this) {
                        if (status == Status.CLOSED) return;
                    }
                    Objects.requireNonNull(retireLegacy.get()).whenComplete((ignored, failure) -> {
                        try {
                            lifecycle.execute(() -> finishRetirement(installed, failure, originalKernelFactory));
                        } catch (RuntimeException rejection) {
                            failInstallation(installed, rejection);
                        }
                    });
                } catch (RuntimeException | LinkageError error) {
                    failInstallation(installed, error);
                }
            });
        } catch (RuntimeException rejection) {
            failInstallation(installed, rejection);
        }
        return installed;
    }

    private void finishRetirement(CompletableFuture<Void> installed, Throwable failure,
                                  Supplier<Kernel> originalKernelFactory) {
        synchronized (this) {
            if (status == Status.CLOSED) {
                installed.completeExceptionally(new IllegalStateException("Cutover closed"));
                return;
            }
            if (failure != null) {
                failInstallation(installed, failure);
                return;
            }
        }
        Kernel created;
        try {
            created = Objects.requireNonNull(originalKernelFactory.get());
        } catch (RuntimeException | LinkageError error) {
            failInstallation(installed, error);
            return;
        }
        synchronized (this) {
            if (status != Status.CLOSED) {
                kernel = created;
                status = Status.INSTALLED;
                installed.complete(null);
                return;
            }
        }
        created.close();
    }

    private void failInstallation(CompletableFuture<Void> installed, Throwable failure) {
        synchronized (this) {
            if (status != Status.CLOSED) status = Status.FAILED;
        }
        installed.completeExceptionally(failure);
    }

    public synchronized Kernel requireKernel() {
        if (status != Status.INSTALLED) throw new IllegalStateException("Kernel unavailable: " + status);
        return kernel;
    }
    @Override public void close() {
        Kernel current;
        synchronized (this) {
            if (status == Status.CLOSED) return;
            status = Status.CLOSED;
            current = kernel;
            kernel = null;
            if (installation != null && !installation.isDone())
                installation.completeExceptionally(new IllegalStateException("Cutover closed"));
        }
        if (current != null) lifecycle.execute(current::close);
    }
}
