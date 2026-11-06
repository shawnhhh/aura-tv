package tv.herrin.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

internal object DetailRegionFocus {
    /** Attach the target's LazyColumn item before requesting it; caller cancels superseded moves. */
    suspend fun move(list: LazyListState, target: FocusRequester, down: Boolean) {
        repeat(16) {
            withFrameNanos { }
            if (runCatching { target.requestFocus(); true }.getOrDefault(false)) return
            val next = if (down) (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return) + 1
                else list.firstVisibleItemIndex - 1
            if (next !in 0 until list.layoutInfo.totalItemsCount) return
            list.scrollToItem(next)
        }
    }
}
