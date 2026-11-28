package tv.herrin.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpiderPasswordAuthorizationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tvCloudSharePasswordFormOpensAndReturnsInputWithoutRequiringQRCode() {
        val owner = Any()
        var returned: String? = null
        compose.setContent { MaterialTheme { SpiderAuthorizationHost() } }
        compose.runOnUiThread {
            SpiderAuthorizationUi.receive(owner, 137, 4,
                """{"inputs":[{"name":"share_pass","type":"password","required":true,
                      "maxLength":20}],"buttons":[{"id":"confirm","label":"确认"}]}""") {
                returned = it
                SpiderAuthorizationUi.receive(owner, 137, 4, null) { }
            }
        }
        compose.onNodeWithTag("spider-authorization-dialog").assertIsDisplayed()
        compose.onNodeWithTag("spider-authorization-input-0").assertIsDisplayed()
        compose.onNodeWithTag("spider-authorization-qr").assertDoesNotExist()
        compose.onNodeWithTag("spider-authorization-action-0").performClick()
        compose.onNodeWithTag("spider-authorization-form-feedback").assertIsDisplayed()
        compose.onNodeWithTag("spider-authorization-input-0").performTextReplacement("1234AB")
        compose.onNodeWithTag("spider-authorization-action-0").performClick()
        compose.waitForIdle()
        val actual = JSONObject(requireNotNull(returned))
        assertEquals("confirm", actual.getString("button"))
        assertEquals("1234AB", actual.getJSONObject("values").getString("share_pass"))
    }
    @Test fun refreshWithBlankRequiredFieldSurvivesIpcAndEnablesTheLoginStep() {
        val owner = Any()
        var completed: String? = null
        lateinit var broker: SpiderAuthorizationSession
        broker = SpiderAuthorizationSession { id, raw ->
            SpiderAuthorizationUi.receive(owner, 138, id, raw) { event -> broker.action(id, event) }
        }
        compose.setContent { MaterialTheme { SpiderAuthorizationHost() } }
        compose.runOnUiThread {
            val future = broker.show("""{"inputs":[{"name":"pass","type":"password","required":true}],
                "buttons":[{"id":"refresh","label":"刷新","close":false,"validate":[]},
                {"id":"login","label":"确认","enabled":false}]}""") { event ->
                java.util.concurrent.CompletableFuture.completedFuture(if (JSONObject(event).getString("button") == "refresh")
                    """{"close":false,"buttons":[{"id":"refresh","enabled":false},{"id":"login","enabled":true}]}"""
                    else """{"close":true}""")
            }
            future.thenAccept { completed = it }
        }
        try {
            compose.onNodeWithTag("spider-authorization-action-0").performClick()
            compose.onNodeWithTag("spider-authorization-form-feedback").assertDoesNotExist()
            compose.onNodeWithTag("spider-authorization-action-0").assertIsNotEnabled()
            compose.onNodeWithTag("spider-authorization-action-1").assertIsEnabled()
            compose.onNodeWithTag("spider-authorization-input-0").performTextReplacement("original-test-code")
            compose.onNodeWithTag("spider-authorization-action-1").performClick()
            compose.waitForIdle()
            assertEquals("login", JSONObject(requireNotNull(completed)).getString("button"))
        } finally { compose.runOnUiThread { broker.cancel(); SpiderAuthorizationUi.clear(owner) } }
    }

}
