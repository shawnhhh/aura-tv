package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonalConnectionsTest {
    @Test fun credentialsAreEncryptedIsolatedAndNeverReusedAfterAuthorityChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PersonalConnections(context)
        fun config(host: String, password: String) = JSONObject().put("name", "Fixture").put("protocol", "http")
            .put("host", host).put("port", 8080).put("username", "fixture-user").put("password", password)
        val first = store.save(config("nas-one.invalid", "first-secret"))
        val second = store.save(config("nas-two.invalid", "second-secret"))
        try {
            assertNotEquals(first.id, second.id)
            assertEquals("first-secret", store.get(first.id)!!.password)
            assertEquals("second-secret", store.get(second.id)!!.password)
            val stored = context.getSharedPreferences("herrin_personal_secrets", 0).all.values.joinToString()
            assertFalse(stored.contains("first-secret")); assertFalse(stored.contains("second-secret"))
            assertFalse(first.publicJson().has("password"))
            store.save(config("nas-one.invalid", "").put("id", first.id))
            assertEquals("first-secret", store.get(first.id)!!.password)
            store.save(config("changed.invalid", "").put("id", first.id))
            assertEquals("", store.get(first.id)!!.password)
            assertEquals("second-secret", store.get(second.id)!!.password)
        } finally { store.delete(first.id); store.delete(second.id) }
    }
    @Test fun cloudCleanupDoesNotRemoveExistingNasOrSmbSecrets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("herrin", 0)
        val oldRemoved = prefs.getBoolean("cloud_removed_v1", false)
        // This test owns only the retired cloud slot, never overwrites existing NAS/SMB credentials.
        val nas = HerrinPersonalCredentialStore(context, "nas").loadPayload()
        val smb = HerrinPersonalCredentialStore(context, "smb").loadPayload()
        check(HerrinPersonalCredentialStore(context, "cloud").save("retired", "retired-secret"))
        prefs.edit().putBoolean("cloud_removed_v1", false).putString("cloud_webdav_url", "https://retired.invalid/").commit()
        Preferences(context)
        assertNull(HerrinPersonalCredentialStore(context, "cloud").loadPayload())
        assertFalse(prefs.contains("cloud_webdav_url"))
        assertEquals(nas, HerrinPersonalCredentialStore(context, "nas").loadPayload())
        assertEquals(smb, HerrinPersonalCredentialStore(context, "smb").loadPayload())
        prefs.edit().putBoolean("cloud_removed_v1", oldRemoved).commit()
    }
}
