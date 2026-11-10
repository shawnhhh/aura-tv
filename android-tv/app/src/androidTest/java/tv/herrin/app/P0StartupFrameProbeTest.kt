package tv.herrin.app

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/** No Compose test clock. Observe real VSync/window frames over fixed measurement windows.
 * The same test APK can measure the 0.4.67 baseline: no new app methods are invoked. */
class P0StartupFrameProbeTest {
    @Test fun actualWindowFramesSeparateRevealFromHomeHandoff(){
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.targetContext.getSharedPreferences("herrin",0).edit().clear().commit()
        val reveal=Collections.synchronizedList(mutableListOf<Long>())
        val breathing=Collections.synchronizedList(mutableListOf<Long>())
        val handoff=Collections.synchronizedList(mutableListOf<Long>())
        val thread=HandlerThread("herrin-startup-observer").apply{start()}
        var window:Window?=null
        var first=0L
        var hz=60f
        val listener=Window.OnFrameMetricsAvailableListener{_,frame,_->
            val timestamp=frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if(first==0L)first=timestamp
            val since=(timestamp-first)/1_000_000
            val duration=frame.getMetric(FrameMetrics.TOTAL_DURATION)
            if(duration>0 &&since in 0..1799)reveal.add(duration)
            else if(duration>0 &&since in 1800..4199)breathing.add(duration)
            else if(duration>0 &&since in 4200..6500)handoff.add(duration)
        }
        val callback=ActivityLifecycleCallback{activity:Activity,stage:Stage->
            if(activity.javaClass.name=="tv.herrin.app.MainActivity" &&stage==Stage.CREATED){
                window=activity.window
                @Suppress("DEPRECATION")
                hz=activity.windowManager.defaultDisplay.refreshRate
                activity.window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper))
            }
        }
        val monitor=ActivityLifecycleMonitorRegistry.getInstance()
        inst.runOnMainSync{monitor.addLifecycleCallback(callback)}
        try{
            ActivityScenario.launch(MainActivity::class.java).use{
                SystemClock.sleep(7000) // Observer only; never delays the application.
                assertNotNull(window)
                inst.runOnMainSync{window?.removeOnFrameMetricsAvailableListener(listener)}
                assertTrue("Real frame callbacks required",reveal.size>=3)
                assertTrue("Breathing must have real frame callbacks",breathing.size>=3)
                assertTrue("Home handoff must also draw frames",handoff.isNotEmpty())
                val out=JSONObject().put("version",inst.targetContext.packageManager.getPackageInfo(inst.targetContext.packageName,0).versionName)
                    .put("refresh_hz",hz.toDouble()).put("warm_process_activity_start",true).put("physical_tcl",false)
                for((name,samples)in listOf("reveal" to reveal,"breathing" to breathing,"handoff" to handoff)){
                    val data=synchronized(samples){samples.toList().sorted()}
                    fun percentile(q:Int)=data[(data.size-1)*q/100]/1_000_000.0
                    out.put(name,JSONObject().put("count",data.size).put("p50_ms",percentile(50))
                        .put("p95_ms",percentile(95)).put("p99_ms",percentile(99))
                        .put("over_budget",data.count{it>1_000_000_000.0/hz}))
                }
                android.util.Log.i("HerrinStartupProbe",out.toString())
            }
        }finally{inst.runOnMainSync{monitor.removeLifecycleCallback(callback)};thread.quitSafely()}
    }
}
