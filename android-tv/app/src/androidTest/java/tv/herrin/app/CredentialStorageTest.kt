package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual Android Keystore tests, using synthetic credentials only. */
class CredentialStorageTest {
    @Test fun unicodeNasCredentialRoundTripsWithoutPlaintextPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slot = "config-${UUID.randomUUID()}"
        val vault = HerrinPersonalCredentialStore(context, slot)
        val username = "测试用户名"
        val password = "密".repeat(1200)
        try {
            assertTrue(vault.save(username, password))
            val encoded = context.getSharedPreferences("herrin_personal_secrets", 0).getString(slot, "")!!
            assertFalse(encoded.contains(username))
            assertFalse(encoded.contains(password))
            assertEquals(username to password, HerrinPersonalCredentialStore(context, slot).load())
        } finally { vault.clear() }
        assertEquals("" to "", vault.load())
    }
    @Test fun personalTmdbTokenIsEncryptedAndReadableAcrossPreferencesInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Preferences(context)
        val token = "mockReadAccessCredential".repeat(5)
        try {
            prefs.tmdbReadToken = token
            assertFalse(context.getSharedPreferences("herrin", 0).contains("tmdb_read_token"))
            val encoded = context.getSharedPreferences("herrin_personal_secrets",0).getString("tmdb", "")!!
            assertFalse(encoded.contains(token))
            assertEquals(token, Preferences(context).tmdbReadToken)
        } finally { prefs.tmdbReadToken = "" }
        assertEquals("", Preferences(context).tmdbReadToken)
    }
}
