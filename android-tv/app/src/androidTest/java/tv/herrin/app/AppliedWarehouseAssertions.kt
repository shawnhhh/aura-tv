package tv.herrin.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Assert persisted, validated configuration rather than a removed technical status row. */
internal fun ComposeContentTestRule.awaitAppliedWarehouse(timeoutMs: Long = 15_000, active: Boolean = true) {
    val expected = onNodeWithTag("tvbox-warehouse-url").fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text
    assertTrue("Fixture must submit a real configuration address", expected.isNotBlank())
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    waitUntil(timeoutMs) {
        val preferences = Preferences(context)
        preferences.tvBoxWarehouseUrl == expected && (!active || preferences.tvBoxLastWorkingRootUrl == expected)
    }
    onNodeWithTag("settings-source-sites").assertExists()
}

/** Node activation follows an explicit UI selection, independently of config validation. */
internal fun ComposeContentTestRule.awaitSelectedWarehouse(timeoutMs: Long = 15_000) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val expected = Preferences(context).tvBoxWarehouseUrl
    assertTrue("A selected node needs a persisted warehouse", expected.isNotBlank())
    waitUntil(timeoutMs) {
        val prefs = Preferences(context)
        prefs.tvBoxWarehouseUrl == expected && prefs.tvBoxLastWorkingRootUrl == expected &&
            prefs.tvBoxLastWorkingSiteId.isNotBlank()
    }
}
