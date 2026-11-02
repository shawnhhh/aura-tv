package tv.herrin.app

import android.content.Context
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Validates public upstream JNI signatures without initializing/loadLibrary. */
@RunWith(AndroidJUnit4::class)
class M467MpvNativeContractTest {
    private fun mpvClass(): Class<*> =
        Class.forName("is.xyz.mpv.MPVLib", false, javaClass.classLoader)

    @Test fun nativeFunctionsMatchThePinnedUpstreamJniAbi() {
        val type = mpvClass()
        val signatures = listOf(
            "create" to arrayOf(Context::class.java),
            "init" to emptyArray(),
            "destroy" to emptyArray(),
            "attachSurface" to arrayOf(Surface::class.java),
            "detachSurface" to emptyArray(),
            "command" to arrayOf(Array<String>::class.java),
            "setOptionString" to arrayOf(String::class.java, String::class.java),
            "getPropertyString" to arrayOf(String::class.java)
        )
        signatures.forEach { (name, params) ->
            val method = type.getDeclaredMethod(name, *params)
            assertTrue("Missing JNI native modifier on $name", Modifier.isNative(method.modifiers))
        }
    }

    @Test fun nativePresenceCheckIsIndependentOfJniObjectInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = MpvNativeAvailability.inspect(context)
        assertEquals(4, MpvNativeAvailability.requiredLibraries.size)
        assertEquals(evidence.missingLibraries.isEmpty(), evidence.available)
        assertTrue(evidence.missingLibraries.all {
            it in MpvNativeAvailability.requiredLibraries
        })
        // Deliberately DO NOT access MPVLib.INSTANCE or invoke JNI before
        // matching ELF assets are compiled, packaged and validated.
    }
}
