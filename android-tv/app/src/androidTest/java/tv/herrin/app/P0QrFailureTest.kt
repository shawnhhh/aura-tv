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
}
