/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import android.app.Application
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import tv.herrin.kernel.Kernel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Implemented only by the compile-ready whole-core Application variant. */
interface WholeKernelInstaller {
    fun createWholeKernel(): WholeKernelPlayer
}

/** Android rendering/control port. No media resolver, proxy or parser here. */
interface WholeKernelPlayer {
    val kernel: Kernel
    val player: Player
    fun bind(view: PlayerView?)
    fun unbind(view: PlayerView)
    fun speed(value: Float): Float
    fun cycleTrack(type: Int): Boolean
    fun repeatOne(enabled: Boolean)
    fun skipSilence(enabled: Boolean)
    val skipSilenceEnabled: Boolean
}

data class KernelPlaybackTicket(val session: String, val episode: String, val replay: Boolean = false)

/** Process lifetime owner. Disabled builds never allocate a whole-core player. */
internal class HerrinKernelRuntime private constructor(private val installer: WholeKernelInstaller?) {
    val gateway = HerrinKernelGateway()
    private var installed: CompletionStage<Void>? = null
    private var port: WholeKernelPlayer? = null
    private val legacyPlayers = linkedMapOf<Any, () -> Unit>()
    val pages: HerrinKernelPages? = installer?.let { HerrinKernelPages(this) }
    val enabled get() = installer != null
    fun registerLegacy(owner: Any, release: () -> Unit) { legacyPlayers[owner] = release }
    fun unregisterLegacy(owner: Any) { legacyPlayers.remove(owner) }
    fun retireLegacy(): CompletionStage<Void> {
        legacyPlayers.values.toList().forEach { it() }
        check(legacyPlayers.isEmpty()) { "Legacy player retirement did not complete" }
        return CompletableFuture.completedFuture(null)
    }
    fun retireWholePlaybackForPersonal() {
        if (gateway.status != tv.herrin.kernel.KernelCutover.Status.INSTALLED) return
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val stopped = gateway.stop().toCompletableFuture()
        check(stopped.isDone) { "Original player retirement must finish before another renderer is allocated" }
        stopped.join()
    }

    @Synchronized fun activate(retireLegacy: () -> CompletionStage<Void>): CompletionStage<Void> {
        installed?.let { return it }
        val provider = requireNotNull(installer) { "Whole kernel is disabled in this build" }
        return gateway.installAfterLegacyRetirement(retireLegacy) {
            provider.createWholeKernel().also { port = it }.kernel
        }.also { installed = it }
    }
    fun player(ticket: KernelPlaybackTicket): WholeKernelPlayer {
        require(pages?.owns(ticket) == true) { "Retired kernel playback ticket" }
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        retireLegacy().toCompletableFuture().join()
        return requireNotNull(port) { "Whole kernel is not installed" }
    }
    companion object {
        @Volatile private var instance: HerrinKernelRuntime? = null
        fun attach(application: Application) {
            check(instance == null) { "Kernel runtime already attached" }
            instance = HerrinKernelRuntime(application as? WholeKernelInstaller)
        }
        fun get(context: Context): HerrinKernelRuntime = requireNotNull(instance) {
            "Application has not attached kernel runtime"
        }
        fun find(): HerrinKernelRuntime? = instance
        fun completedRetirement(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    }
}
