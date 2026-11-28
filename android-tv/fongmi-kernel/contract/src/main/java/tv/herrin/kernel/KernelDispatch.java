/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/** Main-thread admission shared by Android hosts; owns no player or worker. */
public final class KernelDispatch {
    private KernelDispatch() {}

    public static <T> CompletionStage<T> submit(Executor main, Supplier<Kernel> kernel,
                                                Function<Kernel, CompletionStage<T>> operation) {
        var result = new CompletableFuture<T>();
        try {
            main.execute(() -> {
                if (result.isCancelled()) return;
                try {
                    var delegated = operation.apply(kernel.get()).toCompletableFuture();
                    result.whenComplete((value, failure) -> {
                        if (result.isCancelled()) delegated.cancel(false);
                    });
                    delegated.whenComplete((value, failure) -> {
                        if (failure == null) result.complete(value);
                        else result.completeExceptionally(failure);
                    });
                } catch (RuntimeException | LinkageError failure) {
                    // Missing original classes must fail the UI request rather
                    // than escape the looper and leave its Future unresolved.
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }
}
