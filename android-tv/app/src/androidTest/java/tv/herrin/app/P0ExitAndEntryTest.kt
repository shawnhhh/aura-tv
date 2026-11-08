package tv.herrin.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

class P0ExitAndEntryTest {
    @get:Rule(order=0) val reset=object:ExternalResource(){ override fun before(){
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("herrin",0).edit().clear().commit()
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun ready(){ compose.waitUntil(15000){ runCatching { compose.onNodeWithTag("splash-curtain").assertDoesNotExist() }.isSuccess } }
    private fun back(){ SystemClock.sleep(320); InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle() }
    @Test fun everyTopLevelCategoryConfirmsAndCancelRetainsItsActualFocus(){
        ready()
        for(cat in listOf("首页","电影","剧集","动漫","综艺","纪录片","Top 250","我的","直播")){
            val nav=compose.onNodeWithTag("nav-$cat")
            nav.performSemanticsAction(SemanticsActions.RequestFocus){it()};nav.performClick()
            back()
            compose.onNodeWithTag("exit-dialog").assertIsDisplayed()
            compose.onNodeWithTag("exit-cancel").assertIsFocused()
            compose.onNodeWithTag("exit-cancel").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("exit-dialog").assertDoesNotExist()
            nav.assertIsFocused()
            assertFalse(compose.activity.isFinishing)
        }
    }
    @Test fun identicalGrayButtonsNavigateBothWaysAndBackCancels(){
        ready();back()
        val a=compose.onNodeWithTag("exit-confirm"); val b=compose.onNodeWithTag("exit-cancel")
        b.assertIsFocused()
        val left=a.captureToImage().toPixelMap();val right=b.captureToImage().toPixelMap()
        val first=left[(left.width*.2f).toInt(),left.height/2]
        val second=right[(right.width*.2f).toInt(),right.height/2]
        assertEquals(ExitButtonBackground.toArgb(), first.toArgb());assertEquals(first.toArgb(),second.toArgb())
        assertEquals(a.fetchSemanticsNode().boundsInRoot.size,b.fetchSemanticsNode().boundsInRoot.size)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);compose.waitForIdle();a.assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);compose.waitForIdle();b.assertIsFocused()
        val i=InstrumentationRegistry.getInstrumentation()
        i.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/herrin-test-evidence").close()
        i.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/herrin-test-evidence/exit-confirmation.png").use { fd->java.io.FileInputStream(fd.fileDescriptor).use{it.readBytes()} }
        // Fixed offline demo only. Never export a QR or a user's service form.
        val screen=i.uiAutomation.takeScreenshot()
        val preview=android.graphics.Bitmap.createScaledBitmap(screen,640,360,true)
        val bytes=java.io.ByteArrayOutputStream()
        preview.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,bytes)
        val chunks=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP).chunked(3000)
        chunks.forEachIndexed { index,chunk->android.util.Log.i("HerrinExitPreview","EXIT_FRAME ${index+1}/${chunks.size} $chunk") }
        if(preview!==screen) preview.recycle()
        screen.recycle()
        back();compose.onNodeWithTag("exit-dialog").assertDoesNotExist();assertFalse(compose.activity.isFinishing)
    }
    @Test fun explicitExitFinishesActivityWithoutErasingSettings(){
        ready()
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("herrin",0).edit().putString("exit-test-marker","preserved").commit()
        back();compose.onNodeWithTag("exit-confirm").performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(compose.activity.isFinishing || compose.activity.isDestroyed)
        assertEquals("preserved",c.getSharedPreferences("herrin",0).getString("exit-test-marker",""))
    }
    @Test fun detailsReturnDoesNotConfirmAndNormalSettingsExposeOneSharedConfigCenter(){
        ready();compose.onNodeWithTag("card-orbit").performClick()
        compose.onNodeWithTag("film-detail-list").assertExists();back()
        compose.onNodeWithTag("exit-dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("phone-config-main").assertIsDisplayed()
        compose.onNodeWithTag("phone-config-main").performSemanticsAction(SemanticsActions.RequestFocus){it()}
        compose.onNodeWithTag("phone-config-main").performClick()
        compose.onNodeWithTag("phone-config-dialog").assertIsDisplayed()
        compose.onNodeWithTag("phone-config-close").assertIsDisplayed()
        back();compose.onNodeWithTag("phone-config-dialog").assertDoesNotExist()
        compose.onNodeWithTag("exit-dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tmdb-phone-config"))
        compose.onNodeWithTag("tmdb-phone-config").performClick()
        compose.onAllNodesWithTag("phone-config-dialog").assertCountEquals(1)
        back();compose.onNodeWithTag("settings-list").assertExists()
    }
    @Test fun rapidAndRepeatedBackNeverFinishesOrStacksDialogs(){
        ready()
        val i=InstrumentationRegistry.getInstrumentation()
        repeat(5){i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)}
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("exit-dialog").fetchSemanticsNodes().size<=1)
        assertFalse(compose.activity.isFinishing)
        val down=SystemClock.uptimeMillis()
        repeat(12){ i.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,it)) }
        i.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0))
        compose.waitForIdle();assertFalse(compose.activity.isFinishing)
    }

    @Test fun phoneHttpSaveRefreshesTvWithoutRemoteApply() {
        ready()
        okhttp3.mockwebserver.MockWebServer().use { fixture ->
            fixture.start()
            fixture.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"sites":[{"name":"扫码刷新样本","api":"${fixture.url("/cms")}"}]}"""))
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("phone-config-main").performClick()
            compose.onNodeWithTag("phone-config-http").performClick()
            compose.waitUntil(5000) {
                runCatching { compose.onNodeWithTag("phone-config-qr").assertIsDisplayed() }.isSuccess &&
                    runCatching { compose.onNodeWithTag("phone-config-address").assertTextContains("http://", substring=true) }.isSuccess
            }
            val pixels = compose.onNodeWithTag("phone-config-qr").captureToImage().toPixelMap()
            val colors = IntArray(pixels.width * pixels.height) { i -> pixels[i % pixels.width, i / pixels.width].toArgb() }
            val qr = com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(
                com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(pixels.width, pixels.height, colors)))).text
            val origin = qr.substringBefore("/#")
            val client = okhttp3.OkHttpClient()
            fun post(path: String, data: org.json.JSONObject, key: String = ""): org.json.JSONObject =
                client.newCall(okhttp3.Request.Builder().url(origin + path).header("Origin", origin)
                    .header("X-Herrin-Session", key).post(data.toString().toRequestBody("application/json".toMediaType())).build())
                    .execute().use { assertEquals(200, it.code); org.json.JSONObject(it.body!!.string()) }
            val pair = org.json.JSONObject().put("pairing", qr.substringAfter('#'))
            assertTrue(post("/pair", pair).getBoolean("pending"))
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("phone-config-approve").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithTag("phone-config-approve").performClick()
            val key = post("/pair", pair).getString("session")
            post("/sources", org.json.JSONObject().put("url", fixture.url("/box.json").toString()), key)
            compose.waitUntil(15000) { fixture.requestCount > 0 }
            compose.onNodeWithTag("phone-config-close").performClick()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("vod-apply-feedback"))
            compose.waitUntil(15000) { runCatching { compose.onNodeWithTag("vod-apply-feedback").assertTextEquals("已导入配置") }.isSuccess }
            assertEquals("/box.json", fixture.takeRequest().path)
        }
    }
}
