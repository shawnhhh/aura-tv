package tv.herrin.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A focus target in a LazyColumn footer does not exist until its item is
 * composed. Scroll the actual list before requesting focus; never trap Down.
 */
internal object DetailCastFocus {
    fun move(
        scope: CoroutineScope,
        list: LazyListState,
        castAvailable: Boolean,
        firstActor: FocusRequester,
        focused: () -> Boolean
    ): Boolean {
        if (!castAvailable) return false
        scope.launch {
            val last = list.layoutInfo.totalItemsCount - 1
            if (last < 0) return@launch
            list.scrollToItem(last)
            repeat(8) {
                withFrameNanos { }
                if (runCatching { firstActor.requestFocus() }.isSuccess && focused())
                    return@launch
            }
        }
        return true
    }
}
