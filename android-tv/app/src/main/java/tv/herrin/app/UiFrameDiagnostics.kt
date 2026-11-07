package tv.herrin.app

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/** Bounded real rendered-window frame samples, including dynamic display frame budget. */
internal class UiFrameDiagnostics(private val window: Window, private val refreshRate: () -> Float) {
    private data class Sample(val vsync: Long, val duration: Long, val budget: Double)
    private val samples = ArrayDeque<Sample>(600)
    private val thread = HandlerThread("herrin-frame-metrics").apply { start() }
    private var platformDropped = 0L
    private val revealFrames=ArrayList<Long>(300)
    private var revealReported=false
    private val initialGcTime = android.os.Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull()
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        val rate = refreshRate().takeIf { it.isFinite() && it > 0 } ?: 60f
        val sample = Sample(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
            metrics.getMetric(FrameMetrics.TOTAL_DURATION), 1_000_000_000.0 / rate)
        synchronized(samples) {
            if(!StartupTiming.wordmarkIsComplete() &&revealFrames.size<600)revealFrames.add(sample.duration)
            else if(!revealReported &&revealFrames.isNotEmpty()){
                revealReported=true
                val values=revealFrames.sorted()
                fun p(n:Int)=values[(values.size-1)*n/100]/1_000_000.0
                android.util.Log.i("HerrinStartupFrames","count=${values.size} p50_ms=${p(50)} p95_ms=${p(95)} p99_ms=${p(99)} over_budget=${values.count{it>sample.budget}}")
            }
            if (samples.size == 600) samples.removeFirst()
            samples.addLast(sample)
            platformDropped += dropped
        }
    }
    init { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
    fun summary(): String = synchronized(samples) {
        if (samples.size < 2) return "窗口渲染：样本不足（非面板标称帧率）"
        val durations = samples.map { it.duration }.sorted()
        val span = (samples.last().vsync - samples.first().vsync) / 1_000_000_000.0
        val cadence = if (span > 0) (samples.size - 1) / span else 0.0
        val overBudget = samples.count { it.duration > it.budget }
        fun percentile(p: Int) = durations[((durations.size - 1) * p / 100)] / 1_000_000.0
        "窗口渲染样本=${samples.size} 回调频率=${"%.1f".format(cadence)}fps（含空闲间隔）" +
            "\n帧耗时 P50/P95/P99=${"%.2f".format(percentile(50))}/${"%.2f".format(percentile(95))}/${"%.2f".format(percentile(99))}ms" +
            "\n超过当前刷新周期=$overBudget/${samples.size}；指标回调丢失=$platformDropped；不等同显示扫描或光子延迟" +
            "\n进程累计 GC 时间增量=" + (android.os.Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull()?.let {
                initialGcTime?.let { start -> it - start }
            }?.toString() ?: "未提供") + "ms（非单次停顿分布）"
    }
    fun close() { window.removeOnFrameMetricsAvailableListener(listener); thread.quitSafely() }
}
