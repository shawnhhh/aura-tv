package tv.herrin.app

import android.app.Service
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors

/** Debug-only original fixture. Never accepts arbitrary plugin code, credentials or external endpoints. */
class SpiderAuthorizationFixtureService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var caller: Messenger? = null
    private var operation = 0
    private val session = SpiderAuthorizationSession { id, payload ->
        caller?.send(Message.obtain(null, if (payload == null) SpiderIpc.AUTH_CLOSE else SpiderIpc.AUTH_SHOW).apply {
            arg1 = operation; arg2 = id; replyTo = incoming
            data = Bundle().apply { if (payload != null) putString("payload", payload) }
        })
    }
    private val incoming: Messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == SpiderIpc.AUTH_ACTION) {
            if (operation == message.arg1) session.action(message.arg2, message.data.getString("button"))
        } else {
            caller = message.replyTo
            operation = message.arg1
            val payload = message.data.getString("payload").orEmpty()
            worker.execute {
                // Only test loopback is allowed. The caller embeds an original mock endpoint in extension.
                val endpoint = JSONObject(payload).getJSONObject("site").getString("ext")
                require(URL(endpoint).host == "127.0.0.1")
                com.github.catvod.Runtime.set(applicationContext)
                com.github.catvod.Runtime.setDialog { options, callback -> session.show(options, callback) }
                val result = com.github.catvod.utils.Dialog().use { dialog ->
                    dialog.show(JSONObject().put("qrcode", "https://example.invalid/herrin-original-qr")
                        .put("buttons", org.json.JSONArray().put(JSONObject().put("id", "check")
                            .put("label", "确认").put("close", false)))) {
                        val response = URL(endpoint).openConnection().apply { connectTimeout = 2000; readTimeout = 2000 }
                            .getInputStream().bufferedReader().use { JSONObject(it.readText()) }
                        // Authorization value stays here, never in IPC responses, logs or UI.
                        JSONObject().put("close", response.optBoolean("authorized") && response.optString("token").isNotBlank())
                    }
                }
                caller?.send(Message.obtain(null, message.what).apply {
                    arg1 = operation
                    data = Bundle().apply { putString("payload", SpiderIpc.error(if (result == null) "mock-cancelled" else "mock-authorized").toString()) }
                })
            }
        }
        true
    })
    override fun onBind(intent: Intent?): IBinder = incoming.binder
    override fun onDestroy() { session.cancel(); worker.shutdownNow(); super.onDestroy() }
}
