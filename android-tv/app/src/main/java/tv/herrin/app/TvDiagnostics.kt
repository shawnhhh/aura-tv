package tv.herrin.app

import android.content.Context
import java.io.File

/** Safe, bounded device-state summary: never includes catalog URLs, stream URLs or tokens. */
internal fun deviceStatusSummary(
    current: UiDisplayMode,
    preferredHz: Float?,
    savedCatalogs: Int,
    liveConfigured: Boolean
): String {
    val width = current.width.coerceAtLeast(0)
    val height = current.height.coerceAtLeast(0)
    val refresh = "%.2f".format(java.util.Locale.ROOT, current.hz)
    val hint = preferredHz?.let { "%.2fHz".format(java.util.Locale.ROOT, it) } ?: "由系统决定"
    return "当前屏幕输出：${width}×${height} @ ${refresh}Hz\n" +
        "HERRIN 高刷请求：${hint}（提示值不等于实际帧率）\n" +
        "已保存目录：${savedCatalogs.coerceIn(0, 10)} 个\n" +
        "直播列表：${if (liveConfigured) "已配置" else "未配置"}\n" +
        "不收集或包含播放地址、授权令牌、收藏记录"
}

/** User-triggered eviction of *poster cache files only*, not settings or media history. */
internal class PosterCacheMaintenance(context: Context) {
    private val directory = File(context.cacheDir, "authorized-posters")

    fun sizeBytes(): Long = directory.listFiles().orEmpty()
        .filter { it.isFile }
        .sumOf { it.length() }

    fun clearFiles(): Int = directory.listFiles().orEmpty()
        .count { it.isFile && it.delete() }
}
