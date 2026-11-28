package tv.herrin.app

import android.content.ComponentName
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Owns each launch Intent for its whole Scenario, including diagnostic relaunches. */
class MainActivityScenarioRule : TestRule {
    lateinit var scenario: ActivityScenario<MainActivity>
        private set
    val activity: MainActivity
        get() {
            var current: MainActivity? = null
            scenario.onActivity { current = it }
            return checkNotNull(current)
        }

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            scenario = ActivityScenario.launch(Intent.makeMainActivity(ComponentName(context, MainActivity::class.java)))
            try { base.evaluate() } finally { closeCurrent() }
        }
    }

    fun openDiagnostics() {
        // ActivityScenario matches lifecycle callbacks against its original Intent.
        // Mutating Activity.intent then recreating makes subsequent callbacks fail
        // filterEquals, leaving the Scenario stuck at its old RESUMED transition.
        closeCurrent()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .setAction("tv.herrin.app.DIAGNOSTICS"))
    }

    private fun closeCurrent() {
        if (scenario.state == Lifecycle.State.DESTROYED) return
        try {
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                activity.currentFocus?.clearFocus()
            }
        } finally { scenario.close() }
    }
}

internal fun mainActivityComposeRule() = AndroidComposeTestRule(MainActivityScenarioRule()) { it.activity }
