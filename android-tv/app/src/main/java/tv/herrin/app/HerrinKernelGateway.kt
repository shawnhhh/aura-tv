/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import android.os.Handler
import android.os.Looper
import tv.herrin.kernel.Kernel
import tv.herrin.kernel.KernelCutover
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture

/** UI/business entry for the whole upstream kernel. No URL resolver or fallback.
 *
 * Installation is deliberately not called by the current Application: original
 * Media3 dependencies and same-package class migration must compile first.
 * TMDB/NAS source ranking stays with the existing router; the selected TVBox
 * site and exact upstream selection are passed through this boundary.
 */
internal class HerrinKernelGateway : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val cutover = KernelCutover { operation ->
        if (Looper.myLooper() == Looper.getMainLooper()) operation.run()
        else main.post(operation)
    }

    val status: KernelCutover.Status get() = cutover.status()

    fun installAfterLegacyRetirement(
        retireLegacy: () -> CompletionStage<Void>,
        createOriginalKernel: () -> Kernel
    ): CompletionStage<Void> = cutover.install(retireLegacy, createOriginalKernel)

    fun load(access: Kernel.WarehouseAccess): CompletionStage<List<Kernel.Site>> =
        submit { it.load(access) }

    fun search(siteKey: String, query: String): CompletionStage<List<Kernel.Movie>> =
        submit { it.search(siteKey, query) }
    fun home(siteKey: String): CompletionStage<List<Kernel.Movie>> = submit { it.home(siteKey) }

    fun details(movieHandle: String): CompletionStage<Kernel.Detail> =
        submit { it.details(movieHandle) }

    fun play(episodeHandle: String, positionMs: Long = -1L): CompletionStage<Void> =
        submit { it.play(episodeHandle, positionMs) }

    fun pause(): CompletionStage<Void> = submit { it.pause() }
    fun resume(): CompletionStage<Void> = submit { it.resume() }
    fun seek(positionMs: Long): CompletionStage<Void> = submit { it.seek(positionMs) }
    fun stop(): CompletionStage<Void> = submit { it.stop() }
    fun snapshot(): CompletionStage<Kernel.State> = submit { CompletableFuture.completedFuture(it.snapshot()) }
    fun observe(observer: (Kernel.State) -> Unit): CompletionStage<Void> = submit {
        it.observe(observer)
        CompletableFuture.completedFuture(null)
    }
    fun observeDetail(observer: (Kernel.Detail) -> Unit): CompletionStage<Void> = submit {
        it.observeDetail(observer)
        CompletableFuture.completedFuture(null)
    }

    // Source aggregation may call from IO; upstream lifecycle and player APIs
    // are always entered on the main thread, without creating another worker.
    private fun <T> submit(operation: (Kernel) -> CompletionStage<T>): CompletionStage<T> {
        val result = CompletableFuture<T>()
        val task = Runnable {
            if (!result.isCancelled) {
                try {
                    val delegated = operation(cutover.requireKernel())
                    delegated.whenComplete { value, failure ->
                        if (failure == null) result.complete(value)
                        else result.completeExceptionally(failure)
                    }
                    result.whenComplete { _, _ ->
                        if (result.isCancelled) delegated.toCompletableFuture().cancel(false)
                    }
                } catch (failure: RuntimeException) {
                    result.completeExceptionally(failure)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
        return result
    }
    override fun close() = cutover.close()
}
