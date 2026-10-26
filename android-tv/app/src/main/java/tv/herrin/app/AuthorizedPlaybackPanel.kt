package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * M2: real Android TV playback for an explicitly configured, licensed HTTPS test stream.
 * Does not guess episode URLs or access third-party provider streams.
 */
@OptIn(UnstableApi::class)
@Composable
fun AuthorizedPlaybackPanel(media: PlaybackMedia, onBack: () -> Unit) {
    val context = LocalContext.current
    var errorMessage by remember(media.uri) { mutableStateOf<String?>(null) }
    val player = remember(media.uri) {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(media.headers)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        ExoPlayer.Builder(context).setMediaSourceFactory(factory).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errorMessage = "媒体播放失败：${error.errorCodeName}"
                }
            })
            val item = MediaItem.Builder().setUri(media.uri).apply {
                when {
                    media.uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ->
                        setMimeType(MimeTypes.APPLICATION_M3U8)
                    media.uri.substringBefore('?').endsWith(".mpd", ignoreCase = true) ->
                        setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }.build()
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.stop(); player.release() }
    }
    BackHandler { onBack() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply {
                useController = true
                controllerAutoShow = true
                keepScreenOn = true
                this.player = player
            } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        Column(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = onBack) { Text("返回详情") }
            Text("用户授权的 HTTPS 测试媒体 · 不代表当前影片片源", color = Color.White)
            if (errorMessage != null) Text(errorMessage!!, color = Color(0xFFFFD0D0))
        }
    }
}
