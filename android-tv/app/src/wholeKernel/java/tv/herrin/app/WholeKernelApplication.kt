/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import androidx.media3.ui.PlayerView
import tv.herrin.kernel.android.FongMiKernel
import tv.herrin.kernel.android.KernelApplication

/** Opt-in host source. Not in the stable main source set until the complete
 * original classpath is buildable; no reflection or stock-Media3 substitution. */
class WholeKernelApplication : KernelApplication(), WholeKernelInstaller {
    override fun onCreate() {
        super.onCreate()
        CrashJournal.install(this)
        HerrinKernelRuntime.attach(this)
    }
    override fun createWholeKernel(): WholeKernelPlayer {
        val core = FongMiKernel()
        return object : WholeKernelPlayer {
            override val kernel = core
            override val player get() = core.playerForHost()
            override fun bind(view: PlayerView?) = core.bind(view)
            override fun unbind(view: PlayerView) = core.unbindForHost(view)
            override fun speed(value: Float) = core.speedForHost(value)
            override fun cycleTrack(type: Int) = core.cycleTrackForHost(type)
            override fun selectTrack(type: Int, group: Int, index: Int) = core.selectTrackForHost(type, group, index)
            override fun repeatOne(enabled: Boolean) = core.repeatForHost(enabled)
            override fun skipSilence(enabled: Boolean) = core.skipSilenceForHost(enabled)
            override val skipSilenceEnabled get() = core.skipSilenceForHost()
        }
    }
}
