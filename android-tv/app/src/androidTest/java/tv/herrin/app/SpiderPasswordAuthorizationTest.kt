package tv.herrin.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
        compose.onNodeWithTag("spider-authorization-input-0").performTextInput("1234AB")
        compose.onNodeWithTag("spider-authorization-action-0").performClick()
        compose.waitForIdle()
        val actual = JSONObject(requireNotNull(returned))
        assertEquals("confirm", actual.getString("button"))
        assertEquals("1234AB", actual.getJSONObject("values").getString("share_pass"))
    }
}
