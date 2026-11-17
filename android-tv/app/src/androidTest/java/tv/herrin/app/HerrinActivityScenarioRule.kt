package tv.herrin.app

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.ConcurrentHashMap

/** Choose the immutable launch Intent before ActivityScenario captures its lifecycle identity. */
class HerrinActivityScenarioRule(private val diagnosticMethods: Set<String>) : TestRule {
    private lateinit var delegate: ActivityScenarioRule<MainActivity>
    val scenario: ActivityScenario<MainActivity> get() = delegate.scenario
    var diagnostic = false; private set
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val inst = InstrumentationRegistry.getInstrumentation()
            diagnostic = description.methodName.substringBefore('[') in diagnosticMethods
            val launch = Intent.makeMainActivity(ComponentName(inst.targetContext, MainActivity::class.java))
            if (diagnostic) launch.action = "tv.herrin.app.DIAGNOSTICS"
            val created = ConcurrentHashMap.newKeySet<Int>()
            val destroyed = ConcurrentHashMap.newKeySet<Int>()
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && activity.intent.action == launch.action) {
                    val id = System.identityHashCode(activity)
                    if (stage == Stage.CREATED) created.add(id)
                    if (stage == Stage.DESTROYED) destroyed.add(id)
                    Log.i("HerrinScenarioLifecycle", "test=${description.methodName} instance=$id action=${launch.action} stage=$stage")
                }
            }
            inst.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback) }
            delegate = ActivityScenarioRule(launch)
            var original: Throwable? = null
            try {
                delegate.apply(base, description).evaluate()
            } catch (failure: Throwable) {
                original = failure
                throw failure
            } finally {
                try {
                    assertEquals("Scenario must really close", Lifecycle.State.DESTROYED, scenario.state)
                    assertTrue("Every created/recreated instance must emit DESTROYED", created.isNotEmpty() && destroyed.containsAll(created))
                } catch (closure: Throwable) {
                    if (original != null) original.addSuppressed(closure) else throw closure
                } finally {
                    inst.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback) }
                }
            }
        }
    }
}

internal fun createHerrinComposeRule(diagnosticMethods: Set<String>) = AndroidComposeTestRule(
    activityRule = HerrinActivityScenarioRule(diagnosticMethods),
    activityProvider = { rule ->
        var activity: MainActivity? = null
        rule.scenario.onActivity { activity = it }
        checkNotNull(activity)
    }
)
