package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmbConfigSecurityTest {
    @Test fun encryptedRecordRetainsSameServerCredentialsAndNeverCopiesThemToAnotherServer() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=HerrinSmbConfigStore(context)
        val secrets=HerrinPersonalCredentialStore(context,"smb")
        val old=secrets.loadPayload()
        val prefs=context.getSharedPreferences("herrin",0)
        val marker=prefs.getString("smb_revision",null)
        try {
            store.save(JSONObject().put("url","smb://nas/media").put("username","nas-user")
                .put("password","private-nas-password").put("domain","private-domain").put("encryption",true))
            assertEquals("private-nas-password",store.account()!!.password)
            val raw=context.getSharedPreferences("herrin_personal_secrets",0).all.toString()+prefs.all.toString()
            assertFalse(raw.contains("private-nas-password"));assertFalse(raw.contains("private-domain"))
            val public=store.publicConfig()
            for(key in listOf("username","password","domain"))assertFalse(public.has(key))
            store.save(JSONObject().put("url","smb://nas/media").put("password",""))
            assertEquals("private-nas-password",store.account()!!.password)
            assertTrue(store.account()!!.encryption)
            store.save(JSONObject().put("url","smb://another/media"))
            assertEquals("",store.account()!!.password);assertEquals("",store.account()!!.username)
            prefs.edit().putString("smb_revision","incomplete-update").commit()
            assertNull("Incomplete configuration updates must fail closed",store.account())
        }finally {
            if(old==null)store.clear() else {
                assertTrue(secrets.savePayload(old))
                prefs.edit().apply{if(marker==null)remove("smb_revision") else putString("smb_revision",marker)}.commit()
            }
        }
    }
}
