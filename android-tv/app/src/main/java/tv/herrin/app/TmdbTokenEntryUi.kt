package tv.herrin.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** This navigation is only enabled in the compact Android TV settings form. */
internal data class CompactTmdbRemoteNavigation(
    val requester: (String) -> FocusRequester,
    val onNavFocus: (String) -> Unit,
    val onEditingFocus: (Boolean) -> Unit,
    val onMoveUp: (String) -> Unit,
    val onMoveDown: (String) -> Unit,
    val onToggleFocus: () -> Unit
)

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
    onThird: (String) -> Unit,
    remoteNavigation: CompactTmdbRemoteNavigation? = null
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvButton(if (splitMode) "改为单框输入" else "跨屏输入限200字？切换分段粘贴",
            modifier = Modifier.testTag(if (compact) "compact-tmdb-split-toggle" else "tmdb-split-toggle")
                .then(if (compact && remoteNavigation != null)
                    Modifier.focusRequester(remoteNavigation.requester("compact-tmdb-split-toggle"))
                    else Modifier),
            reduced = reducedMotion,
            onFocus = { remoteNavigation?.onToggleFocus() },
            onClick = onToggle)
        if (splitMode) {
            Text("每段最多200字，不要求第1段填满。按顺序粘贴，自动拼接。" +
                "测试阶段明文显示，请检查是否漏字或多字。",
                color = Color(0xFFCDD8E6),
                modifier = Modifier.testTag(if (compact) "compact-tmdb-split-help" else "tmdb-split-help"))
            listOf(first, second, third).forEachIndexed { index, value ->
                val change = when (index) { 0 -> onFirst; 1 -> onSecond; else -> onThird }
                val tag = if (compact) "compact-tmdb-part-${index + 1}"
                    else "tmdb-token-part-${index + 1}"
                val label = "Token 第${index + 1}段${if (index == 2) "（可选）" else ""}：${value.length}/200"
                val changeBounded: (String) -> Unit = { next ->
                    if (next.length <= TmdbTokenEntry.PART_LIMIT) change(next)
                }
                if (compact && remoteNavigation != null) {
                    val id = "compact-tmdb-part-${index + 1}"
                    TvSourceInput(value, changeBounded, label,
                        fieldTag = tag, navTag = "${id}-nav",
                        navRequester = remoteNavigation.requester(id),
                        editRequester = remoteNavigation.requester("${id}-editor"),
                        onNavFocus = { remoteNavigation.onNavFocus(id) },
                        onEditingFocus = remoteNavigation.onEditingFocus,
                        onMoveUp = { remoteNavigation.onMoveUp(id) },
                        onMoveDown = { remoteNavigation.onMoveDown(id) })
                } else {
                    OutlinedTextField(value, changeBounded,
                        modifier = Modifier.fillMaxWidth().testTag(tag),
                        label = { Text(label) },
                        visualTransformation = VisualTransformation.None, singleLine = true)
                }
            }
        } else {
            if (compact && remoteNavigation != null) {
                val id = "compact-tmdb-token"
                TvSourceInput(single, { onSingle(it.take(TmdbTokenEntry.MAX_LENGTH)) },
                    "TMDB API Read Access Token（测试明文）",
                    fieldTag = id, navTag = "compact-tmdb-token-nav",
                    navRequester = remoteNavigation.requester(id),
                    editRequester = remoteNavigation.requester("compact-tmdb-token-editor"),
                    onNavFocus = { remoteNavigation.onNavFocus(id) },
                    onEditingFocus = remoteNavigation.onEditingFocus,
                    onMoveUp = { remoteNavigation.onMoveUp(id) },
                    onMoveDown = { remoteNavigation.onMoveDown(id) })
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
}
