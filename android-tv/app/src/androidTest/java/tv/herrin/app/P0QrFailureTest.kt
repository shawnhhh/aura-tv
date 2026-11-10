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

    @Test fun reopeningKeepsHttpsFingerprintWithFreshPairingAndDismissClosesListener() {
        val servers=java.util.concurrent.CopyOnWriteArrayList<LanConfigServer>()
        var open by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if(open) LanConfigDialog({open=false},{},serverFactory={c,cb->
                LanConfigServer(c,"127.0.0.1",cb).also{servers.add(it)}
            })
        } }
        compose.waitUntil(5000){servers.firstOrNull()?.isAlive==true && runCatching {
            compose.onNodeWithTag("phone-config-address").assertTextContains("https://",substring=true)
        }.isSuccess}
        compose.onNodeWithTag("phone-config-http").assertDoesNotExist()
        compose.onNodeWithTag("phone-config-address").assertTextContains("https://",substring=true)
        val first=servers.first()
        compose.onNodeWithTag("phone-config-close").performClick()
        compose.waitForIdle();assertFalse(first.isAlive)
        compose.runOnIdle { open=true }
        compose.waitUntil(5000){servers.size==2 && servers.last().isAlive && runCatching {
            compose.onNodeWithTag("phone-config-address").assertTextContains("https://",substring=true)
        }.isSuccess}
        assertEquals(first.fingerprint,servers.last().fingerprint)
        assertNotEquals(first.pairing.pairing,servers.last().pairing.pairing)
        compose.onNodeWithTag("phone-config-address").assertTextContains("https://",substring=true)
        compose.onNodeWithTag("phone-config-close").performClick()
        compose.waitForIdle();assertFalse(servers.last().isAlive)
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
        compose.waitUntil(5000) {
            server?.isAlive == true && runCatching {
                compose.onNodeWithTag("phone-config-address").assertIsDisplayed()
            }.isSuccess
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        assertFalse(server!!.isAlive)
    }
}
