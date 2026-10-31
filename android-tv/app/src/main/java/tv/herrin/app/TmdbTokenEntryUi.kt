package tv.herrin.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Extracted to keep large Herrin Compose method below JVM bytecode limits.
 * Alpha only: TMDB token is visible on TV while verifying paste correctness.
 * Form state stays in memory and is never rememberSaveable.
 */
@Composable
internal fun TmdbTokenEntryFields(
    compact: Boolean,
    reducedMotion: Boolean,
    splitMode: Boolean,
    onToggle: () -> Unit,
    single: String,
    onSingle: (String) -> Unit,
    first: String,
    onFirst: (String) -> Unit,
    second: String,
    onSecond: (String) -> Unit,
    third: String,
    onThird: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvButton(if (splitMode) "改为单框输入" else "跨屏输入限200字？切换分段粘贴",
            modifier = Modifier.testTag(if (compact) "compact-tmdb-split-toggle" else "tmdb-split-toggle"),
            reduced = reducedMotion, onClick = onToggle)
        if (splitMode) {
            Text("每段最多200字，不要求第1段填满。按顺序粘贴，自动拼接。" +
                "测试阶段明文显示，请检查是否漏字或多字。",
                color = Color(0xFFCDD8E6),
                modifier = Modifier.testTag(if (compact) "compact-tmdb-split-help" else "tmdb-split-help"))
            listOf(first, second, third).forEachIndexed { index, value ->
                val change = when (index) { 0 -> onFirst; 1 -> onSecond; else -> onThird }
                val tag = if (compact) "compact-tmdb-part-${index + 1}"
                    else "tmdb-token-part-${index + 1}"
                OutlinedTextField(value, { next ->
                    if (next.length <= TmdbTokenEntry.PART_LIMIT) change(next)
                }, modifier = Modifier.fillMaxWidth().testTag(tag),
                    label = { Text("Token 第${index + 1}段${if (index == 2) "（可选）" else ""}：${value.length}/200") },
                    visualTransformation = VisualTransformation.None,
                    singleLine = true)
            }
        } else {
            OutlinedTextField(single, { onSingle(it.take(TmdbTokenEntry.MAX_LENGTH)) },
                modifier = Modifier.fillMaxWidth().testTag(
                    if (compact) "compact-tmdb-token" else "tmdb-token-input"),
                label = { Text("TMDB API Read Access Token（测试明文）") },
                visualTransformation = VisualTransformation.None,
                singleLine = true)
        }
    }
}
