package tv.herrin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen playback-first loading, failure and recovery.
 *
 * Kept separate from Herrin() for two reasons:
 *  1. The main screen had exceeded the JVM 64KiB single-method code limit.
 *  2. Consumer-facing playback does not expose Spider/JAR/CMS debug states.
 * Playback/network authorization still belongs to the caller.
 */
@Composable
internal fun PlayerStartupSurface(
    loading: Boolean,
    offlineDemonstration: Boolean,
    cmsFailed: Boolean,
    retryAvailable: Boolean,
    reducedMotion: Boolean,
    failureHint: String? = null,
    failureDiagnostic: String? = null,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onTryOtherSource: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))
        .testTag("player-startup-surface"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (loading) {
                CircularProgressIndicator(
                    color = Color.White, modifier = Modifier.size(40.dp))
                Text("正在打开影片…", fontSize = 21.sp,
                    modifier = Modifier.testTag("player-startup-label"))
            } else {
                Text(if (offlineDemonstration) "播放演示" else "暂时无法播放",
                    fontSize = 24.sp,
                    modifier = Modifier.testTag(
                        if (offlineDemonstration) "player-demo-status"
                        else "player-startup-error"))
                Text(when {
                    offlineDemonstration ->
                        "当前是离线演示目录。添加你有权使用的影视源后，即可播放影片。"
                    cmsFailed ->
                        "该影视点没有提供可用的播放线路，可重新播放或返回片库更换影片。"
                    failureHint != null -> failureHint
                    else -> "请重试，或返回选集切换播放线路。"
                }, color = Color(0xFFBCCADB), fontSize = 16.sp,
                    modifier = Modifier.testTag("player-startup-failure-hint"))
                if (!failureDiagnostic.isNullOrBlank() && !offlineDemonstration) {
                    Text(failureDiagnostic, color = Color(0xFFFFD0D0), fontSize = 15.sp,
                        modifier = Modifier.testTag("player-startup-diagnostic"))
                    TvButton(if (copied) "诊断已复制" else "复制详细诊断日志",
                        modifier = Modifier.testTag("player-startup-copy-diagnostics"),
                        reduced = reducedMotion) {
                        val clipboard = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "Herrin 播放诊断", failureDiagnostic + "\n" +
                                HerrinPlaybackDiagnosticJournal.export()))
                        copied = true
                    }
                }
                if (retryAvailable) {
                    TvButton(if (failureHint != null) "重新向来源解析" else "重新播放",
                        modifier = Modifier.testTag("player-startup-retry"),
                        reduced = reducedMotion, onClick = onRetry)
                }
                // A provider can fail during CMS/Spider URL parsing, before any
                // ExoPlayer exists. It must expose the same explicit fallback
                // as a decoder failure, without silently starting playback.
                if (!offlineDemonstration) onTryOtherSource?.let { action ->
                    TvButton("查找其他片源",
                        modifier = Modifier.testTag("player-startup-other-source"),
                        reduced = reducedMotion, onClick = action)
                }
            }
            TvButton(if (cmsFailed) "返回片库" else "返回选集",
                modifier = Modifier.testTag("player-startup-back"),
                reduced = reducedMotion, onClick = onBack)
        }
    }
}
