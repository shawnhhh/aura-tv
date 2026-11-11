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
        lifecycle.execute(() -> {
            try {
                synchronized (this) {
                    if (status == Status.CLOSED) return;
                }
                Objects.requireNonNull(retireLegacy.get()).whenComplete((ignored, failure) -> lifecycle.execute(() -> {
                    synchronized (this) {
                        if (status == Status.CLOSED) {
                            installed.completeExceptionally(new IllegalStateException("Cutover closed"));
                            return;
                        }
                        if (failure != null) {
                            status = Status.FAILED;
                            installed.completeExceptionally(failure);
                            return;
                        }
                    }
                    Kernel created;
                    try { created = Objects.requireNonNull(originalKernelFactory.get()); }
                    catch (RuntimeException | LinkageError error) {
                        synchronized (this) { if (status != Status.CLOSED) status = Status.FAILED; }
                        installed.completeExceptionally(error);
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
                }));
            } catch (RuntimeException error) {
                synchronized (this) { if (status != Status.CLOSED) status = Status.FAILED; }
                installed.completeExceptionally(error);
            }
        });
        return installed;
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
