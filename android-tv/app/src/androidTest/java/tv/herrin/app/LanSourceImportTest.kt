package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LanSourceImportTest {
    @Test fun failedCandidatePreservesWorkingRootAndTrustAndSuccessfulActivationSurvivesRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("herrin", 0)
        val before = prefs.all
        try {
            prefs.edit().putString("tvbox_warehouse_url", "https://original.invalid/box")
                .putString("tvbox_trusted_spider_root", "https://original.invalid/box")
                .putString("tvbox_trusted_spider_jar", "old.jar;md5;123").commit()
            val store = LanSourceImport(context)
            val url = AdditionalServiceConfigs.sourceAddress("http://www.饭太硬.net/tv")
            val pending = store.save(url)
            assertEquals(url, LanSourceImport(context).current()!!.url)
            assertEquals("SAVED", store.status(pending.id).getString("state"))
            store.update(pending.id, "FAILED", "SOURCE_HTTP_403")
            assertEquals("https://original.invalid/box", Preferences(context).tvBoxWarehouseUrl)
            assertEquals("old.jar;md5;123", Preferences(context).tvBoxTrustedSpiderJarSpec)
            val next = store.save("https://next.invalid/box")
            assertFalse("a stale import must not activate a newer submission", store.activate(pending.id))
            assertEquals("SUPERSEDED", store.status(pending.id).getString("state"))
            assertTrue(store.update(next.id, "AWAITING_SITE"))
            assertEquals("https://original.invalid/box", Preferences(context).tvBoxWarehouseUrl)
            assertTrue(store.activate(next.id))
            assertEquals("ACTIVE", LanSourceImport(context).status(next.id).getString("state"))
            assertEquals("https://next.invalid/box", Preferences(context).tvBoxWarehouseUrl)
            assertEquals("", Preferences(context).tvBoxTrustedSpiderJarSpec)
            assertTrue("Catalog completion may recognize the same committed import",store.isActive(next.id))
            assertFalse(store.isActive(pending.id))
            val replacement = store.save("https://replacement.invalid/box")
            assertFalse("A later phone submission revokes the old completion",store.isActive(next.id))
            assertFalse(store.isActive(replacement.id))
            assertEquals("https://next.invalid/box",Preferences(context).tvBoxWarehouseUrl)
        } finally {
            val edit = prefs.edit().clear()
            before.forEach { (k,v) -> when(v) {
                is String -> edit.putString(k,v); is Boolean -> edit.putBoolean(k,v)
                is Int -> edit.putInt(k,v); is Long -> edit.putLong(k,v); is Float -> edit.putFloat(k,v)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>) }
            } }
            edit.commit()
        }
    }
    @Test fun failureStagesRemainDistinctAndNeverContainUserAddresses() {
        assertEquals("SOURCE_HTTP_403", LanSourceImport.failure("WAREHOUSE_HTTP_ERROR：配置读取 HTTP 403"))
        assertEquals("SOURCE_BODY_INVALID", LanSourceImport.failure("WAREHOUSE_FORMAT_INVALID：正文无效"))
        assertEquals("SOURCE_URL_INVALID", LanSourceImport.failure("WAREHOUSE_URL_INVALID：格式错误"))
        assertEquals("SOURCE_WAREHOUSE_PARSE_FAILED", LanSourceImport.failure("无法解析仓库"))
        assertEquals("SOURCE_HTTP_404", LanSourceImport.failure("WAREHOUSE_HTTP_ERROR：配置读取 HTTP 404"))
        assertEquals("SOURCE_TIMEOUT", LanSourceImport.failure("WAREHOUSE_TIMEOUT：配置读取超时"))
        assertEquals("SOURCE_FETCH_FAILED", LanSourceImport.failure("WAREHOUSE_DNS_ERROR：域名解析失败"))
    }
}
