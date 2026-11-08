package tv.herrin.app
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class P0QrFailureTest {
    @get:Rule val compose=createComposeRule()
    @Test fun noIpv4ShowsActionableRetryAndPreservesManualConfiguration(){
        compose.setContent { MaterialTheme { LanConfigDialog({}, {}, serverFactory={_,_->throw NoLanAddress()}) } }
        compose.waitUntil(5000){runCatching{compose.onNodeWithTag("phone-config-status").assertTextContains("IPv4", substring=true)}.isSuccess}
        compose.onNodeWithTag("phone-config-retry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("phone-config-close").assertIsDisplayed()
    }
    @Test fun qrFailureClosesServerAndRetryRendersRealQrInModal(){
        var attempts=0
        var server:LanConfigServer?=null
        var open by mutableStateOf(true)
        compose.setContent { MaterialTheme { if(open) LanConfigDialog({open=false},{},
            serverFactory={c,callback->LanConfigServer(c,"127.0.0.1",callback).also{server=it}},
            qrFactory={value-> if(attempts++==0)throw IllegalArgumentException("private value must not be displayed") else configurationQr(value)}) } }
        compose.waitUntil(5000){runCatching{compose.onNodeWithTag("phone-config-retry").assertIsDisplayed()}.isSuccess}
        assertFalse(server!!.isAlive)
        compose.onNodeWithTag("phone-config-status").assertTextContains("失败", substring=true)
        compose.onNodeWithTag("phone-config-retry").performClick()
        compose.waitUntil(5000){runCatching{compose.onNodeWithTag("phone-config-qr").assertIsDisplayed()}.isSuccess}
        compose.onNodeWithTag("phone-config-close").assertIsDisplayed().performClick()
        compose.waitForIdle();assertFalse(server!!.isAlive)
    }

    @Test fun switchingToHttpClosesHttpsAndDismissClosesHttp() {
        var secure: LanConfigServer? = null
        var quick: LanConfigServer? = null
        var open by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (open) LanConfigDialog({ open = false }, {},
                serverFactory = { c, cb -> LanConfigServer(c, "127.0.0.1", cb).also { secure = it } },
                quickServerFactory = { c, cb -> LanConfigServer(c, "127.0.0.1", true, cb).also { quick = it } })
        } }
        compose.waitUntil(5000) { secure?.isAlive == true }
        compose.onNodeWithTag("phone-config-http").performClick()
        compose.waitUntil(5000) { quick?.isAlive == true }
        assertFalse(secure!!.isAlive)
        compose.onNodeWithTag("phone-config-address").assertTextContains("http://", substring = true)
        compose.onNodeWithTag("phone-config-close").performClick()
        compose.waitForIdle()
        assertFalse(quick!!.isAlive)
    }

    @Test fun backgroundingOwnerClosesListener() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        var server: LanConfigServer? = null
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                MaterialTheme { LanConfigDialog({}, {},
                    serverFactory = { c, cb -> LanConfigServer(c, "127.0.0.1", cb).also { server = it } }) }
            }
        }
        compose.waitUntil(5000) { server?.isAlive == true }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        assertFalse(server!!.isAlive)
    }
}
