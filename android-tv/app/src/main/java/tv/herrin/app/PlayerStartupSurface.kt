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
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
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
                    else -> "请重试，或返回选集切换播放线路。"
                }, color = Color(0xFFBCCADB), fontSize = 16.sp)
                if (retryAvailable) {
                    TvButton("重新播放",
                        modifier = Modifier.testTag("player-startup-retry"),
                        reduced = reducedMotion, onClick = onRetry)
                }
            }
            TvButton(if (cmsFailed) "返回片库" else "返回选集",
                modifier = Modifier.testTag("player-startup-back"),
                reduced = reducedMotion, onClick = onBack)
        }
    }
}
