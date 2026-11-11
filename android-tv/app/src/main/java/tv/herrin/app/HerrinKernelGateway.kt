/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import android.os.Handler
import android.os.Looper
import tv.herrin.kernel.Kernel
import tv.herrin.kernel.KernelCutover
import java.util.concurrent.CompletionStage

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
        cutover.requireKernel().load(access)

    fun search(siteKey: String, query: String): CompletionStage<List<Kernel.Movie>> =
        cutover.requireKernel().search(siteKey, query)

    fun details(movieHandle: String): CompletionStage<Kernel.Detail> =
        cutover.requireKernel().details(movieHandle)

    fun play(episodeHandle: String, positionMs: Long = -1L): CompletionStage<Void> =
        cutover.requireKernel().play(episodeHandle, positionMs)

    fun pause(): CompletionStage<Void> = cutover.requireKernel().pause()
    fun resume(): CompletionStage<Void> = cutover.requireKernel().resume()
    fun seek(positionMs: Long): CompletionStage<Void> = cutover.requireKernel().seek(positionMs)
    fun stop(): CompletionStage<Void> = cutover.requireKernel().stop()
    fun snapshot(): Kernel.State = cutover.requireKernel().snapshot()
    fun observe(observer: (Kernel.State) -> Unit) = cutover.requireKernel().observe(observer)
    fun observeDetail(observer: (Kernel.Detail) -> Unit) = cutover.requireKernel().observeDetail(observer)
    override fun close() = cutover.close()
}
