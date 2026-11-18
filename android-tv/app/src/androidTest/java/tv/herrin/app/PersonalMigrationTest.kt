package tv.herrin.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PersonalMigrationTest {
    /** Private preference records, sharing only the app's normal AndroidKeyStore provider. */
    private fun isolated(block: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "migration-fixture-${UUID.randomUUID()}-"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                names += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        try { block(context) } finally { names.forEach { base.getSharedPreferences(it, 0).edit().clear().commit() } }
    }
    @Test fun legacySmbAndDavKeepCredentialsAndFoldersAndMigrateOnlyOnce() = isolated { context ->
        val old = HerrinSmbConfigStore(context)
        old.save(JSONObject().put("url", "smb://nas.invalid/Movies/films").put("username", "fixture")
            .put("password", "fixture-secret").put("domain", "realm").put("encryption", true))
        val prefs = Preferences(context)
        prefs.nasWebDavUrl = "HTTPS://nas.invalid/dav/"
        assertTrue(HerrinPersonalCredentialStore(context, "nas").save("dav-user", "dav-secret"))
        val extra = AdditionalServiceConfigs(context)
        val extraId = extra.save("nas", JSONObject().put("name", "Disabled DAV").put("url", "https://other.invalid/media/")
            .put("username", "extra-user").put("password", "extra-secret").put("enabled", false))
        val store = PersonalConnections(context)
        val index = PersonalMediaIndex(context)
        try {
            store.migrate(index)
            assertEquals(3, store.list().size)
            val smb = store.list().single { it.protocol == "smb" }
            assertEquals("fixture", smb.username); assertEquals("fixture-secret", smb.password)
            assertEquals("realm", smb.domain); assertTrue(smb.encryption)
            assertEquals("Movies/films", smb.rootPath)
            assertEquals("Movies/films", index.folders(smb.id).single().path)
            val dav = store.list().single { it.host == "nas.invalid" && it.protocol == "https" }
            assertEquals("dav-user", dav.username); assertEquals("dav-secret", dav.password)
            assertEquals("dav", dav.rootPath)
            assertEquals("dav", index.folders(dav.id).single().path)
            assertFalse(store.list().single { it.name == "Disabled DAV" }.enabled)
            store.migrate(index)
            assertEquals(3, store.list().size)
            assertEquals("fixture-secret", old.account()!!.password)
            assertEquals("HTTPS://nas.invalid/dav/", prefs.nasWebDavUrl)
            assertFalse(store.migrationWarning())
        } finally {
            store.list().forEach { c -> index.folders(c.id).forEach { index.removeFolder(it.id) }; store.delete(c.id) }
            extra.remove("nas", extraId)
            index.close()
        }
    }
    @Test fun invalidLegacyConfigurationIsPreservedAndFlaggedWithoutCrashingLibraryStartup() = isolated { context ->
        val raw = context.getSharedPreferences("herrin", 0)
        raw.edit().putString("nas_webdav_url", "https:///").commit()
        val store = PersonalConnections(context)
        val index = PersonalMediaIndex(context)
        try {
            store.migrate(index)
            assertTrue(store.list().isEmpty())
            assertTrue(store.migrationWarning())
            assertEquals("https:///", raw.getString("nas_webdav_url", null))
        } finally { index.close() }
    }
}
