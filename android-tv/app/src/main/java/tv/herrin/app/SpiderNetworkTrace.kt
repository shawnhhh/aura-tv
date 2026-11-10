package tv.herrin.app

import android.content.Context
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Fixed SDK network events only. No request, address, headers, error message or body is persisted. */
internal object SpiderNetworkTrace {
    @Volatile private var active: Session? = null
    fun start(context: Context): AutoCloseable = Session(File(context.filesDir,"spider-network-shape.json")).also { active=it; it.reset() }
    fun event(code: String) { active?.record(code, 0, 0) }
    @JvmStatic fun listener(): EventListener {
        val session=active ?: return EventListener.NONE
        val started=System.nanoTime()
        val stage=SpiderStartupTrace.currentStage?.name ?: "UNKNOWN"
        return object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                session.record("HTTP_"+stage,response.code,(System.nanoTime()-started)/1_000_000)
            }
            override fun callFailed(call: Call, ioe: IOException) {
                val kind=when(ioe) {
                    is java.net.UnknownHostException -> "DNS"
                    is javax.net.ssl.SSLException -> "TLS"
                    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "TIMEOUT"
                    is java.net.ConnectException -> "CONNECT"
                    else -> "IO"
                }
                session.record("FAILED_"+stage+"_"+kind,0,(System.nanoTime()-started)/1_000_000)
            }
        }
    }
    private class Session(private val file: File): AutoCloseable {
        private val rows=JSONArray()
        private var closed=false
        @Synchronized fun reset() { runCatching { file.writeText("[]") } }
        @Synchronized fun record(event: String,status: Int,elapsed: Long) {
            if(closed || active!==this || rows.length()>=40)return
            runCatching {
                rows.put(JSONObject().put("event",event).put("status",status.coerceIn(0,599)).put("elapsed_ms",elapsed.coerceIn(0,160000)))
                file.writeText(rows.toString())
            }
        }
        @Synchronized override fun close() { closed=true; if(active===this)active=null }
    }
}
