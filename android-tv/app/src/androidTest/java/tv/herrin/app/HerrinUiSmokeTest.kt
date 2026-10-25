package tv.herrin.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator M1 integration checks exercise the real native Compose Activity.
 * They intentionally do not claim physical TCL remote or streaming validation.
 */
@RunWith(AndroidJUnit4::class)
class HerrinUiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun awaitHome() {
        // Do not rely on internal Compose semantics APIs; allow splash to complete naturally.
        compose.waitUntil(10000) {
            runCatching { compose.onNodeWithText("影片详情").assertExists() }.isSuccess
        }
        compose.waitForIdle()
    }

    @Test fun homeCategoriesAndOfflineSearch() {
        compose.onNodeWithText("HERRIN").assertExists()
        compose.onNodeWithText("影片详情").assertExists()
        compose.onNodeWithText("电影").performClick()
        compose.onNodeWithText("精选电影 · 演示片库").assertExists()
        compose.onNodeWithText("搜索").performClick()
        compose.onNodeWithText("搜索 · 离线演示片库").assertExists()
        compose.onNodeWithText("潮汐").performClick()
        compose.onNodeWithText("搜索结果 · 1").assertExists()
        compose.onNodeWithText("潮汐之间").assertExists()
    }

    @Test fun detailAndDemoPlayerNavigateBack() {
        compose.onNodeWithText("影片详情").performClick()
        compose.onNodeWithText("演示选集").assertExists()
        compose.onNodeWithText("▶ 播放演示").performClick()
        compose.onNodeWithText("播放演示").assertExists()
        compose.onNodeWithText("返回详情").performClick()
        compose.onNodeWithText("演示选集").assertExists()
        compose.onNodeWithText("‹ 返回").performClick()
        compose.onNodeWithText("HERRIN").assertExists()
    }

    @Test fun settingsShowReduceMotionAndDisclosure() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("减少动态效果：关").assertExists()
        compose.onNodeWithText("‹ 返回").performClick()
        compose.onNodeWithText("HERRIN").assertExists()
    }
}
