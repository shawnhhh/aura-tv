package tv.herrin.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** D-pad accessible, deliberate correction using the public ID shown on a TMDB film/TV page. */
@Composable
internal fun MetadataCorrectionDialog(
    film: Film,
    currentMatch: TmdbManualMatch?,
    onConfirm: (TmdbManualMatch?) -> Unit,
    onDismiss: () -> Unit
) {
    var type by remember(film.id) { mutableStateOf(currentMatch?.mediaType ?: ArtworkTitle.identify(film).mediaType ?: "movie") }
    var id by remember(film.id) { mutableStateOf(currentMatch?.id?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("纠正影片资料") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${film.originalDisplay?.title ?: film.title}\n填写 TMDB 作品页面的数字 ID；仅替换展示资料，播放线路保持不变。")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { type = "movie" }, modifier = Modifier.testTag("metadata-type-movie")) {
                        Text(if (type == "movie") "✓ 电影" else "电影")
                    }
                    Button(onClick = { type = "tv" }, modifier = Modifier.testTag("metadata-type-tv")) {
                        Text(if (type == "tv") "✓ 电视剧" else "电视剧")
                    }
                }
                OutlinedTextField(id, { id = it.filter(Char::isDigit).take(9) },
                    label = { Text("TMDB 数字 ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("metadata-correction-id"))
                Button(onClick = { onConfirm(null) }, modifier = Modifier.testTag("metadata-correction-reset")) {
                    Text("清除纠错，恢复自动匹配")
                }
            }
        },
        confirmButton = {
            Button(onClick = { id.toIntOrNull()?.takeIf { it > 0 }?.let { onConfirm(TmdbManualMatch(type, it)) } },
                enabled = (id.toIntOrNull() ?: 0) > 0,
                modifier = Modifier.testTag("metadata-correction-save")) { Text("保存并重新刮削") }
        },
        dismissButton = { Button(onClick = onDismiss, modifier = Modifier.testTag("metadata-correction-cancel")) { Text("取消") } },
        modifier = Modifier.padding(16.dp).testTag("metadata-correction-dialog")
    )
}
