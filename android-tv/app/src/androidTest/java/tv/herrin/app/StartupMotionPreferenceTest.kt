package tv.herrin.app

import android.Manifest
import android.provider.Settings
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Disposable CI AVD: observe the actual MainActivity write after the home underlay commits. */
@RunWith(Parameterized::class)
class StartupMotionPreferenceTest(private val systemScale: Float, private val herrinReduced: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="system={0}, herrinReduced={1}")
        fun cases(): List<Array<Any>> = listOf(arrayOf<Any>(0f, false), arrayOf<Any>(1f, true))
    }
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private var oldScale: String? = null
    private var beforeValues: Map<String, *> = emptyMap<String, Any?>()

    private fun writeScale(value: String?) {
        inst.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Global.putString(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, value))
        } finally {
            inst.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @get:Rule(order=0) val fixture = object: ExternalResource() {
        override fun before() {
            val prefs = context.getSharedPreferences("herrin",0)
            beforeValues = prefs.all
            oldScale = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
            prefs.edit().clear().commit()
            Preferences(context).reducedMotion = herrinReduced
            writeScale(systemScale.toString())
        }
        override fun after() {
            writeScale(oldScale)
            val edit = context.getSharedPreferences("herrin",0).edit().clear()
            beforeValues.forEach { (key,value) -> when(value) {
                is String -> edit.putString(key,value)
                is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value)
                is Long -> edit.putLong(key,value)
                is Float -> edit.putFloat(key,value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>) }
            } }
            edit.commit()
        }
    }
    @get:Rule(order=1) val compose = createAndroidComposeRule<MainActivity>()

    private fun presentation(): StartupPresentation {
        // Read the real lifecycle-owned state. Never inject readiness or alter the Activity.
        val field = MainActivity::class.java.getDeclaredField("presentation")
        field.isAccessible = true
        return field.get(compose.activity) as StartupPresentation
    }

    @Test fun homeCompositionCannotReplaceTheExplicitStartupMotionPreference() {
        compose.waitUntil(15_000) {
            presentation().restoreBrowseFocus != null // Assigned by the actual home SideEffect.
        }
        compose.runOnIdle {
            assertEquals(systemScale, Settings.Global.getFloat(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f), 0f)
            assertEquals(herrinReduced, Preferences(context).reducedMotion)
            assertEquals("Home underlay must retain the explicit preference, independently of system scale",
                herrinReduced, presentation().reduced)
        }
    }
}
