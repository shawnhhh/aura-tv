package tv.herrin.app
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AdditionalServiceConfigsTest {
    @Test fun batchDeduplicatesRejectsInvalidAtomicallyAndSupportsEditingAndRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("herrin", 0).edit().remove("services_sources").commit()
        val store = AdditionalServiceConfigs(context)
        try {
            store.batchSources("https://one.invalid/config\nhttps://one.invalid/config\nhttp://two.invalid/config")
            assertEquals(2, store.list("sources").size)
            try { store.batchSources("https://three.invalid/config\nfile:///bad/config"); fail() }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, store.list("sources").size)
            val entry = store.list("sources").first()
            store.save("sources", JSONObject().put("id",entry.id).put("name","已修改")
                .put("url", entry.url).put("enabled", false))
            assertEquals(1,store.enabled("sources").size)
            store.remove("sources",entry.id)
            assertEquals(1,store.list("sources").size)
        } finally { context.getSharedPreferences("herrin",0).edit().remove("services_sources").commit() }
    }
}
