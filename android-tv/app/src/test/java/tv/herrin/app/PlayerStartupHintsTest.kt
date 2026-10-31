package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PlayerStartupHintsTest {
    @Test fun commonSpiderPlaybackFailuresExplainCorrectRootCause() {
        assertTrue(PlayerStartupHints.forSpiderFailure("此线路需要网页解析器，请切换返回直链的授权线路")
            .contains("网页解析"))
        assertTrue(PlayerStartupHints.forSpiderFailure("Spider 没有返回本次授权的 HTTP(S) 直链")
            .contains("未返回"))
        assertTrue(PlayerStartupHints.forSpiderFailure("Spider 操作超时，请重新应用站点")
            .contains("超时"))
        assertTrue(PlayerStartupHints.forSpiderFailure("Spider 子进程异常退出")
            .contains("进程"))
    }

    @Test fun arbitraryProviderErrorNeverLeaksTokenOrPrivateUrl() {
        val confidential = "https://private.example/path?token=MY_SECRET_VALUE"
        val result = PlayerStartupHints.forSpiderFailure(
            "Spider operation failed: " + confidential + " Authorization: Bearer SECRET")
        assertFalse(result.contains("MY_SECRET_VALUE"))
        assertFalse(result.contains("private.example"))
        assertFalse(result.contains("SECRET"))
        assertTrue(result.contains("影视来源"))
    }
}
